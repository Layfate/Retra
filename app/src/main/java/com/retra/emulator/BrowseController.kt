package com.retra.emulator

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle-owned async bridge. No network or catalog parsing runs on the WebView thread. */
class BrowseController(private val activity: MainActivity) {
    val repository = BrowseRepository(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutations = Mutex()
    private val imageRequests = Semaphore(3)
    private val downloads = ConcurrentHashMap<String, Job>()
    private val recovered = scope.launch { repository.recoverDownloads() }
    private val store get() = repository.store

    fun close() = scope.cancel()
    private fun emit(function: String, value: JSONObject) {
        activity.runOnUiThread {
            if (!activity.isDestroyed) activity.binding.webView.evaluateJavascript(
                "window.$function && window.$function(${activity.jsQuote(value.toString())})", null)
        }
    }
    fun request(requestId: String, action: String, arguments: String) {
        if (requestId.length > 80 || arguments.length > 16384) return
        if (action == "cancelDownload") {
            runCatching { downloads[JSONObject(arguments).getString("id")]?.cancel() }
            emit("retraBrowseReply", JSONObject().put("id", requestId).put("result", JSONObject()))
            return
        }
        scope.launch {
            recovered.join()
            try {
                val args = JSONObject(arguments)
                val result = if (action in setOf("catalog", "details", "image", "download")) execute(action, args)
                    else mutations.withLock { execute(action, args) }
                emit("retraBrowseReply", JSONObject().put("id", requestId).put("result", result))
            } catch (error: Exception) {
                if (error !is CancellationException) emit("retraBrowseReply", JSONObject().put("id", requestId)
                    .put("error", error.message ?: "Browse operation failed"))
            }
        }
    }
    private fun snapshot(): JSONObject = JSONObject()
        .put("repositories", JSONArray(store.all("repository")))
        .put("installed", JSONArray(store.all("installed")))
        .put("sources", JSONArray(repository.sources()))
        .put("downloads", JSONArray(store.all("download")))

    private suspend fun execute(action: String, args: JSONObject): JSONObject {
        when (action) {
            "state" -> return snapshot()
            "addRepository" -> repository.addRepository(args.getString("url"))
            "removeRepository" -> store.delete("repository", args.getString("url"))
            "trustRepository" -> repository.trust(args.getString("url"), args.getBoolean("trusted"))
            "refresh" -> return snapshot().put("errors", repository.refresh()).also {
                // Return the refreshed rows rather than the snapshot taken before the request.
                it.put("repositories", JSONArray(store.all("repository")))
            }
            "install" -> repository.install(args.getString("url"), args.getString("id"))
            "uninstall" -> store.delete("installed", args.getString("id"))
            "enableSource" -> repository.setSourceEnabled(args.getString("source"), args.getBoolean("enabled"))
            "pin" -> {
                val key = repository.source(args.getString("source")).getString("key")
                if (args.getBoolean("pinned")) store.put("pin", key, JSONObject()) else store.delete("pin", key)
            }
            "image" -> return imageRequests.withPermit { JSONObject().put("url", repository.cachedImage(args.getString("url"))) }
            "catalog" -> {
                val source = repository.JsonGameSource(repository.source(args.getString("source")), args.optBoolean("refresh"))
                val filters = args.optJSONObject("filters") ?: JSONObject()
                val page = source.browse(args.optString("query"), GameFilters(filters.keys().asSequence().associateWith { filters.getString(it) }),
                    args.optString("mode", "popular"), args.optInt("page", 1))
                return JSONObject().put("games", JSONArray(page.games.map { it.json })).put("page", page.page)
                    .put("hasNext", page.hasNext).put("offline", source.offline)
            }
            "details", "addLibrary", "import", "download" -> {
                val source = args.getString("source")
                val gameId = args.getString("gameId")
                val key = repository.downloadKey(source, gameId)
                // Saved metadata remains usable after an extension or repository is removed.
                val saved = store.get("library", key)
                val definition = runCatching { repository.source(source) }.getOrNull()
                val game = if (definition != null) runCatching { repository.JsonGameSource(definition).getGameDetails(gameId).metadata }
                    .getOrElse { saved?.getJSONObject("game")?.let { row -> GameMetadata(row) } ?: throw it }
                    else saved?.getJSONObject("game")?.let { GameMetadata(it) } ?: error("Source unavailable")
                when (action) {
                    "details" -> return JSONObject().put("game", game.json).put("library", libraryState(key))
                    "addLibrary" -> { addLibrary(source, game, definition?.optString("name") ?: saved?.optString("sourceName").orEmpty()); libraryChanged() }
                    "import" -> {
                        store.put("pending", "import", JSONObject().put("source", source).put("gameId", gameId).put("game", game.json))
                        activity.runOnUiThread { activity.browseRomPicker.launch(arrayOf("*/*")) }
                    }
                    "download" -> {
                        require(definition != null) { "Reinstall the source to download" }
                        val repo = store.get("repository", definition.getString("repository"))
                        require(repo?.optBoolean("trusted") == true) { "Trust the source repository before downloading" }
                        require(!downloads.containsKey(key)) { "Download already running" }
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val file = repository.download(source, game) { emit("retraBrowseProgress", it) }
                                associate(source, game, Uri.fromFile(file))
                                repository.completeDownload(source, gameId)
                                file.delete()
                                store.get("download", key)?.let { emit("retraBrowseProgress", it) }
                            } catch (error: Exception) {
                                store.get("download", key)?.let { state ->
                                    state.put("status", "interrupted").put("error", error.message ?: "Interrupted")
                                    store.put("download", key, state)
                                    emit("retraBrowseProgress", state)
                                }
                            } finally { downloads.remove(key) }
                        }
                        require(downloads.putIfAbsent(key, job) == null) { "Download already running" }
                        job.start()
                        return JSONObject().put("id", key)
                    }
                }
                return JSONObject().put("library", libraryState(key))
            }
            else -> error("Unknown Browse operation")
        }
        return snapshot()
    }
    private fun libraryState(key: String): JSONObject? {
        val row = store.get("library", key) ?: return null
        val record = activity.romIdentityStore.getById(row.getString("romId")) ?: return null
        return JSONObject(row.toString()).put("inLibrary", !record.archived)
            .put("fileAvailable", record.fileAvailable && record.launchPath?.let { File(it).isFile } == true)
            .put("fileName", record.fileName)
    }
    internal fun addLibrary(source: String, game: GameMetadata, sourceName: String): JSONObject {
        val key = repository.downloadKey(source, game.id)
        val previous = store.get("library", key)
        val knownHash = game.json.optString("romSha256").ifBlank { game.json.optJSONObject("download")?.optString("sha256").orEmpty() }
        val existing = previous?.optString("romId")?.let { activity.romIdentityStore.getById(it) }
            ?: knownHash.takeIf { it.isNotBlank() }?.let { activity.romIdentityStore.findByHash(game.platform.name, it) }
        val romId = existing?.romId ?: "catalog-$key"
        if (existing == null) {
            val now = System.currentTimeMillis()
            activity.romIdentityStore.upsert(RomIdentityStore.Record(romId = romId, contentHash = "",
                platform = game.platform.name, displayName = game.json.getString("title"), fileName = "",
                sourceUri = null, launchPath = null, patchPath = null, fileSize = 0,
                archived = false, fileAvailable = false, createdAt = now, updatedAt = now))
        } else activity.romIdentityStore.setArchived(romId, false)
        val row = JSONObject().put("sourceId", source).put("sourceName", sourceName).put("gameId", game.id)
            .put("romId", romId).put("game", game.json).put("romUri", previous?.optString("romUri").orEmpty())
        store.put("library", key, row)
        return row
    }
    private fun libraryChanged() = emit("retraBrowseLibraryChanged", JSONObject())
    fun importResult(uri: Uri?) {
        scope.launch {
            val pending = store.get("pending", "import") ?: return@launch
            store.delete("pending", "import")
            if (uri == null) return@launch
            try { associate(pending.getString("source"), GameMetadata(pending.getJSONObject("game")), uri) }
            catch (error: Exception) { emit("retraBrowseImportError", JSONObject().put("error", error.message ?: "Import failed")) }
        }
    }
    internal suspend fun associate(source: String, game: GameMetadata, uri: Uri) = mutations.withLock {
        val extension = activity.fileOps.queryDisplayName(uri)?.substringAfterLast('.')?.uppercase()
        require(extension == game.platform.name) { "Select a ${game.platform.name} ROM file" }
        val expected = game.json.optString("romSha256").ifBlank { game.json.optJSONObject("download")?.optString("sha256").orEmpty() }
        if (expected.isNotBlank()) {
            BrowseProtocol.hash(expected)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MainActivity.MAX_ROM_BYTES) { "ROM too large" }
                    digest.update(buffer, 0, read)
                }
            } ?: error("Cannot read ROM")
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected.lowercase()) { "ROM does not match this catalog game" }
        }
        val key = repository.downloadKey(source, game.id)
        val previous = store.get("library", key)
        val item = activity.importUri(uri)
        activity.persistNativeItem(item)
        val row = previous ?: JSONObject().put("sourceId", source).put("sourceName", source.substringAfter('/'))
            .put("gameId", game.id).put("game", game.json)
        val oldId = previous?.optString("romId")
        store.put("library", key, row.put("romId", item.id).put("romUri", item.launchPath?.let { Uri.fromFile(File(it)).toString() } ?: uri.toString()))
        mergePlaceholder(oldId, item.id)
        activity.runOnUiThread { activity.notifyWebImported(item) }
    }
    /** Called only from native Room hydration; source strings are rendered as text by the UI. */
    fun metadata(romId: String): JSONObject? = store.all("library").firstOrNull { it.optString("romId") == romId }

    fun notifyImported(romId: String) {
        scope.launch { if (metadata(romId) != null) libraryChanged() }
    }

    fun writeMetadata(target: JSONObject, catalog: JSONObject?) {
        if (catalog == null) return
        target.put("catalogSource", catalog.optString("sourceId"))
            .put("catalogGameId", catalog.optString("gameId"))
            .put("catalogSourceName", catalog.optString("sourceName"))
            .put("catalogCover", catalog.getJSONObject("game").optString("cover"))
    }

    internal fun reconcileImported(item: NativeLibraryItem) {
        store.all("library").forEach { row ->
            val game = row.getJSONObject("game")
            val hash = game.optString("romSha256").ifBlank { game.optJSONObject("download")?.optString("sha256").orEmpty() }
            if (hash.isNotBlank() && hash.equals(item.contentHash, true) && game.optString("platform") == item.system) {
                val oldId = row.optString("romId")
                row.put("romId", item.id).put("romUri", item.launchPath?.let { Uri.fromFile(File(it)).toString() }.orEmpty())
                store.put("library", repository.downloadKey(row.getString("sourceId"), row.getString("gameId")), row)
                mergePlaceholder(oldId, item.id)
            }
        }
    }
    private fun mergePlaceholder(oldId: String?, romId: String) {
        if (oldId == null || oldId == romId || !oldId.startsWith("catalog-")) return
        val old = activity.romIdentityStore.getById(oldId) ?: return
        val current = activity.romIdentityStore.getById(romId) ?: return
        val categories = (listOf(old.categoriesJson, current.categoriesJson).flatMap { json ->
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        }).distinct()
        activity.romIdentityStore.updateLibraryMetadata(romId, old.favorite || current.favorite, JSONArray(categories).toString())
        activity.romIdentityStore.setArchived(oldId, true)
    }
}
