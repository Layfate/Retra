package com.retra.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Bounded HTTPS transport shared by repository indexes, data packages and ROM downloads. */
open class BrowseHttp(private val headers: (String) -> Map<String, String> = { emptyMap() }) {
    open fun open(url: String, offset: Long = 0): HttpURLConnection {
        var target = BrowseProtocol.https(url)
        repeat(6) {
            val endpoint = URL(target)
            require(InetAddress.getAllByName(endpoint.host).all { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress && !address.isMulticastAddress &&
                    (address.address.size != 16 || (address.address[0].toInt() and 0xfe) != 0xfc)
            }) { "Repository URLs must use public internet addresses" }
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Retra-Catalog/1")
            headers(target).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                require(!next.isNullOrBlank()) { "Invalid redirect" }
                target = BrowseProtocol.https(URL(endpoint, next).toString())
            } else {
                if (status !in setOf(200, 206)) {
                    connection.disconnect()
                    error("HTTP $status")
                }
                return connection
            }
        }
        error("Too many redirects")
    }
    open fun bytes(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            require(connection.responseCode == 200 && connection.contentLengthLong <= limit) { "Response too large" }
            return connection.inputStream.use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(result.size() + read <= limit) { "Response too large" }
                    result.write(buffer, 0, read)
                }
                result.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}

class BrowseRepository(
    private val context: Context,
    val store: BrowseStore = BrowseStore(BrowseDatabase.get(context).documents()),
    private val http: BrowseHttp = BrowseHttp()
) {
    val imageDir = File(context.filesDir, "browse/images").apply { mkdirs() }
    private val downloadDir = File(context.filesDir, "browse/downloads").apply { mkdirs() }

    /** Downloads and validates an index, explaining Mihon/Tachiyomi (APK/protobuf) repos instead of a parser error. */
    private fun fetchIndex(url: String): JSONObject = try {
        JSONObject(http.bytes(url, 2 * 1024 * 1024).toString(Charsets.UTF_8)).also { BrowseProtocol.extensions(it) }
    } catch (_: org.json.JSONException) {
        throw IllegalArgumentException("Not a Retra repository. Retra needs an index.json made for Retra API 1. " +
            "Mihon and Tachiyomi extension repos (index.pb or APK lists) are not compatible.")
    }

    fun addRepository(url: String): JSONObject {
        val normalized = BrowseProtocol.repositoryUrl(url)
        require(store.get("repository", normalized) == null) { "Repository already added" }
        val index = fetchIndex(normalized)
        val row = JSONObject().put("url", normalized).put("name", index.getString("name"))
            .put("trusted", false).put("index", index).put("checkedAt", System.currentTimeMillis())
        store.put("repository", normalized, row)
        return row
    }
    fun refresh(): JSONArray {
        val errors = JSONArray()
        store.all("repository").forEach { row ->
            val url = row.getString("url")
            try {
                val index = fetchIndex(url)
                row.put("index", index).put("checkedAt", System.currentTimeMillis()).remove("error")
            } catch (error: Exception) {
                row.put("error", error.message ?: "Repository unavailable")
                errors.put("${row.getString("name")}: ${error.message}")
            }
            store.put("repository", url, row)
        }
        return errors
    }
    fun trust(url: String, trusted: Boolean) {
        val row = store.get("repository", url) ?: error("Repository not found")
        store.put("repository", url, row.put("trusted", trusted))
    }
    fun install(url: String, id: String): JSONObject {
        val repo = store.get("repository", url) ?: error("Repository not found")
        require(repo.getBoolean("trusted")) { "Trust this repository before installing" }
        val metadata = BrowseProtocol.extensions(repo.getJSONObject("index")).firstOrNull { it.id == id }
            ?: error("Extension no longer listed")
        require(metadata.compatible()) { "Incompatible extension" }
        val previous = store.get("installed", id)
        require(previous == null || previous.getString("repository") == url) { "Extension ID belongs to another repository" }
        require(previous == null || previous.getJSONObject("metadata").getInt("versionCode") <= metadata.version) { "Extension downgrade rejected" }
        val bytes = http.bytes(metadata.json.getString("packageUrl"), 2 * 1024 * 1024)
        val manifest = BrowseProtocol.manifest(bytes, metadata)
        val row = JSONObject().put("repository", url).put("metadata", metadata.json).put("manifest", manifest)
        store.put("installed", id, row)
        return row
    }
    fun sources(): List<JSONObject> = store.all("installed").flatMap { installed ->
        val extension = installed.getJSONObject("metadata").getString("id")
        val rows = installed.getJSONObject("manifest").getJSONArray("sources")
        (0 until rows.length()).map { i ->
            val key = "$extension/${rows.getJSONObject(i).getString("id")}"
            JSONObject(rows.getJSONObject(i).toString()).put("key", key)
                .put("extensionId", extension).put("repository", installed.getString("repository"))
                .put("pinned", store.get("pin", key) != null)
                .put("enabled", store.get("disabled", key) == null)
        }
    }
    /** Per-source extension setting: disabled sources stay installed but leave the Sources list. */
    fun setSourceEnabled(key: String, enabled: Boolean) {
        val source = source(key).getString("key")
        if (enabled) store.delete("disabled", source) else store.put("disabled", source, JSONObject())
    }
    fun source(key: String) = sources().firstOrNull { it.getString("key") == key } ?: error("Source is not installed")

    inner class JsonGameSource(private val definition: JSONObject, private val reload: Boolean = false) : GameSource {
        override val id = definition.getString("key")
        override val name = definition.getString("name")
        override val supportedPlatforms = BrowseProtocol.platforms(definition.getJSONArray("platforms"))
        var offline = false
        private var loaded: List<GameMetadata>? = null
        private fun games(): List<GameMetadata> {
            loaded?.let { return it }
            val url = definition.getString("catalogUrl")
            val cacheKey = BrowseProtocol.sha256(url.toByteArray())
            val cached = store.get("catalog", cacheKey)
            val json = if (!reload && cached != null && System.currentTimeMillis() - cached.optLong("cachedAt") < 900000) cached else {
                try {
                    val fresh = JSONObject(http.bytes(url, 8 * 1024 * 1024).toString(Charsets.UTF_8))
                    BrowseProtocol.catalog(fresh, supportedPlatforms)
                    fresh.put("cachedAt", System.currentTimeMillis())
                    store.put("catalog", cacheKey, fresh)
                    fresh
                } catch (error: Exception) {
                    offline = true
                    cached ?: throw error
                }
            }
            return BrowseProtocol.catalog(json, supportedPlatforms).also { loaded = it }
        }
        override suspend fun getPopularGames(page: Int) = BrowseProtocol.page(games(), "", GameFilters(), "popular", page)
        override suspend fun getLatestGames(page: Int): GamePage {
            require(definition.optBoolean("latest")) { "Latest is not supported" }
            return BrowseProtocol.page(games(), "", GameFilters(), "latest", page)
        }
        override suspend fun searchGames(query: String, filters: GameFilters, page: Int) = BrowseProtocol.page(games(), query, filters, "popular", page)
        suspend fun browse(query: String, filters: GameFilters, mode: String, page: Int): GamePage {
            require(mode in setOf("popular", "latest"))
            require(mode != "latest" || definition.optBoolean("latest")) { "Latest is not supported" }
            return BrowseProtocol.page(games(), query, filters, mode, page)
        }
        override suspend fun getGameDetails(gameId: String) = GameDetails(games().firstOrNull { it.id == gameId }
            ?: error("Game no longer available"))
    }

    fun cachedImage(url: String): String {
        BrowseProtocol.https(url)
        val name = BrowseProtocol.sha256(url.toByteArray()) + ".webp"
        val target = File(imageDir, name)
        if (!target.exists()) {
            val bytes = http.bytes(url, 4 * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Unsupported image size" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid image")
            val temp = File.createTempFile("image-", ".tmp", imageDir)
            try {
                FileOutputStream(temp).use { require(bitmap.compress(Bitmap.CompressFormat.WEBP, 85, it)) }
                require(temp.renameTo(target) || target.exists())
            } finally { bitmap.recycle(); temp.delete() }
            var size = imageDir.listFiles()?.sumOf { it.length() } ?: 0L
            imageDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
                if (size > 128L * 1024 * 1024 && file != target && file.extension == "webp") {
                    val length = file.length()
                    if (file.delete()) size -= length
                }
            }
        }
        target.setLastModified(System.currentTimeMillis())
        return "https://appassets.androidplatform.net/browse-images/$name"
    }

    fun downloadKey(source: String, game: String) = BrowseProtocol.sha256("$source:$game".toByteArray())
    fun recoverDownloads() {
        store.all("download").filter { it.optString("status") in setOf("downloading", "importing") }.forEach { row ->
            row.put("status", "interrupted").put("error", "Interrupted. Tap Download to resume safely.")
            store.put("download", row.getString("id"), row)
        }
    }
    suspend fun download(source: String, game: GameMetadata, progress: (JSONObject) -> Unit): File {
        val file = game.json.optJSONObject("download")
        require(BrowseProtocol.authorized(file)) { "No authorized distributable file" }
        val spec = requireNotNull(file)
        val key = downloadKey(source, game.id)
        val state = JSONObject().put("id", key).put("source", source).put("gameId", game.id)
            .put("total", spec.getLong("size")).put("status", "downloading")
        val part = File(downloadDir, "$key.part")
        val expected = spec.getLong("size")
        val prior = store.get("download", key)
        if (prior?.optString("sha256") != spec.getString("sha256") || part.length() > expected) part.delete()
        state.put("sha256", spec.getString("sha256"))
        fun report(status: String, error: String = "") {
            state.put("status", status).put("bytes", part.length()).put("error", error)
            store.put("download", key, state)
            progress(JSONObject(state.toString()))
        }
        report("downloading")
        try {
            coroutineContext.ensureActive()
            if (part.length() != expected) {
                val connection = http.open(spec.getString("url"), part.length())
                try {
                    val append = connection.responseCode == 206 && part.length() > 0
                    if (connection.responseCode == 206) {
                        require(connection.getHeaderField("Content-Range")?.startsWith("bytes ${part.length()}-") == true) { "Invalid resume response" }
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(32768)
                            var count = if (append) part.length() else 0L
                            var reported = count
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= expected) { "Download exceeds published size" }
                                output.write(buffer, 0, read)
                                if (count - reported >= 262144) { report("downloading"); reported = count }
                            }
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
            }
            require(part.length() == expected) { "Incomplete download; retry to resume" }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != spec.getString("sha256").lowercase()) {
                part.delete()
                error("ROM SHA-256 mismatch; discarded file")
            }
            val ready = File(File(downloadDir, key).apply { mkdirs() }, spec.getString("fileName"))
            part.copyTo(ready, overwrite = true)
            report("importing")
            return ready
        } catch (error: Exception) {
            report("interrupted", error.message ?: "Interrupted")
            throw error
        }
    }
    fun completeDownload(source: String, game: String) {
        val key = downloadKey(source, game)
        store.get("download", key)?.let { store.put("download", key, it.put("status", "complete").put("error", "")) }
        File(downloadDir, "$key.part").delete()
    }
}
