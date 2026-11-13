package com.retra.emulator

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowseInstrumentedTest {
    private val base = "https://example.org/retra/"
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "browse-test-${UUID.randomUUID()}.db"
    private lateinit var database: BrowseDatabase
    private lateinit var context: Context
    private lateinit var store: BrowseStore
    private lateinit var http: FixtureHttp
    private lateinit var repository: BrowseRepository
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("browse/$name").use { it.readBytes() }
    private fun json(name: String) = JSONObject(fixture(name).toString(Charsets.UTF_8))

    @Before fun setup() {
        val files = File(target.cacheDir, databaseName).apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir() = files }
        database = Room.databaseBuilder(target, BrowseDatabase::class.java, databaseName).build()
        store = BrowseStore(database.documents())
        http = FixtureHttp()
        repository = BrowseRepository(context, store, http)
    }
    @After fun cleanup() {
        database.close(); target.deleteDatabase(databaseName)
        context.filesDir.deleteRecursively()
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid metadata to be rejected") } catch (_: IllegalArgumentException) { }
    }
    private fun javascript(scenario: ActivityScenario<MainActivity>, script: String): String {
        val reply = CountDownLatch(1)
        var result = ""
        scenario.onActivity { a -> a.binding.webView.evaluateJavascript(script) { result = it; reply.countDown() } }
        assertTrue(reply.await(10,TimeUnit.SECONDS))
        return result
    }
    private fun waitForJavascript(scenario: ActivityScenario<MainActivity>, condition: String) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            if (javascript(scenario,condition) == "true") return
            Thread.sleep(100)
        }
        fail("Web UI did not reach: $condition")
    }

    @Test fun repositoryValidationAndMalformedMetadata() {
        assertEquals(base + "index.json", BrowseProtocol.repositoryUrl(base))
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/index.json", BrowseProtocol.repositoryUrl("https://github.com/owner/repo/blob/main/index.json"))
        for (url in listOf("http://example.org/index.json", "file:///index.json", "https://user:pass@example.org/", "https://localhost/index.json", "https://appassets.androidplatform.net/assets/")) rejects { BrowseProtocol.repositoryUrl(url) }
        val index = json("index.json")
        assertEquals("org.retra.pad", BrowseProtocol.extensions(index).single().id)
        rejects { BrowseProtocol.extensions(JSONObject(index.toString()).put("schemaVersion", 2)) }
        val duplicate = JSONObject(index.toString())
        duplicate.getJSONArray("extensions").put(duplicate.getJSONArray("extensions").getJSONObject(0))
        rejects { BrowseProtocol.extensions(duplicate) }
        val malformed = json("index.json")
        malformed.getJSONArray("extensions").getJSONObject(0).put("sha256", "bad")
        rejects { BrowseProtocol.extensions(malformed) }
        // A Mihon/Tachiyomi protobuf index gets an explanation, not a JSON parser error.
        try {
            repository.addRepository("https://github.com/keiyoushi/extensions/raw/repo/index.pb")
            fail("Mihon repositories must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Mihon"))
        }
        assertTrue(store.all("repository").isEmpty())
    }

    @Test fun discoveryTrustInstallUpdatesAndCompatibility() {
        val repo = repository.addRepository(base)
        assertFalse(repo.getBoolean("trusted"))
        rejects { repository.install(base + "index.json", "org.retra.pad") }
        repository.trust(base + "index.json", true)
        repository.install(base + "index.json", "org.retra.pad")
        assertEquals("org.retra.pad/homebrew", repository.sources().single().getString("key"))
        assertTrue(repository.sources().single().getBoolean("enabled"))
        repository.setSourceEnabled("org.retra.pad/homebrew", false)
        assertFalse(repository.sources().single().getBoolean("enabled"))
        repository.setSourceEnabled("org.retra.pad/homebrew", true)
        assertTrue(repository.sources().single().getBoolean("enabled"))
        val metadata = BrowseProtocol.extensions(json("index.json")).single()
        assertTrue(metadata.compatible())
        assertFalse(ExtensionMetadata(JSONObject(metadata.json.toString()).put("apiMin", 2).put("apiMax", 2)).compatible())
        rejects { BrowseProtocol.manifest("tampered".toByteArray(), metadata) }
        rejects { BrowseProtocol.manifest(fixture("extension.json"), ExtensionMetadata(JSONObject(metadata.json.toString()).put("versionCode", 2))) }
        store.delete("installed", metadata.id)
        assertTrue(repository.sources().isEmpty())
        store.delete("repository", base + "index.json")
        assertTrue(store.all("repository").isEmpty())
    }

    @Test fun sourceBrowseSearchFiltersPaginationAndOfflineCache() = runBlocking {
        repository.addRepository(base); repository.trust(base + "index.json", true); repository.install(base + "index.json", "org.retra.pad")
        val source = repository.JsonGameSource(repository.sources().single())
        assertEquals("retra-pad", source.getPopularGames(1).games.single().id)
        assertEquals("Retra Pad", source.getGameDetails("retra-pad").metadata.json.getString("title"))
        assertEquals(1, source.searchGames("PAD", GameFilters(mapOf("platform" to "GB", "genre" to "Demo", "year" to "2026", "region" to "World", "language" to "en", "gameType" to "homebrew", "availability" to "download")), 1).games.size)
        assertTrue(source.searchGames("missing",GameFilters(),1).games.isEmpty())
        assertEquals(1,source.getLatestGames(1).games.size)
        http.offline = true
        val offline = repository.JsonGameSource(repository.sources().single(), true)
        assertEquals(1,offline.getPopularGames(1).games.size); assertTrue(offline.offline)
        val games = (0..64).map { GameMetadata(JSONObject().put("id","game-$it").put("title","Test $it").put("platform","GB").put("popularity",it).put("updatedAt",64 - it)) }
        val first = BrowseProtocol.page(games,"",GameFilters(),"popular",1)
        val second = BrowseProtocol.page(games,"",GameFilters(),"popular",2)
        val last = BrowseProtocol.page(games,"",GameFilters(),"popular",3)
        assertEquals(30,first.games.size); assertTrue(first.hasNext)
        assertEquals(30,second.games.size); assertEquals(5,last.games.size); assertFalse(last.hasNext)
        assertTrue(first.games.map { it.id }.intersect(second.games.map { it.id }.toSet()).isEmpty())
        assertEquals("game-0",BrowseProtocol.page(games,"",GameFilters(),"latest",1).games.first().id)
    }

    @Test fun persistenceSurvivesDatabaseReopen() {
        repository.addRepository(base); repository.trust(base + "index.json",true); repository.install(base + "index.json","org.retra.pad")
        store.put("pin","org.retra.pad/homebrew",JSONObject())
        val largeCatalog = JSONObject().put("description", "🌐".repeat(800000))
        store.put("catalog", "large-window", largeCatalog)
        database.close()
        database = Room.databaseBuilder(target,BrowseDatabase::class.java,databaseName).build()
        val reopened = BrowseRepository(context,BrowseStore(database.documents()),http)
        assertTrue(reopened.store.all("repository").single().getBoolean("trusted"))
        assertTrue(reopened.sources().single().getBoolean("pinned"))
        assertEquals(largeCatalog.toString(), reopened.store.get("catalog", "large-window").toString())
    }

    @Test fun interruptedDownloadRecoversAndResumesWithHashValidation() = runBlocking {
        val game = GameMetadata(json("catalog.json").getJSONArray("games").getJSONObject(0))
        http.interrupt = true
        try { repository.download("test/source",game) {}; fail("Expected interruption") } catch (_: IOException) { }
        val key = repository.downloadKey("test/source",game.id)
        assertEquals("interrupted",store.get("download",key)!!.getString("status"))
        assertEquals(16384,store.get("download",key)!!.getInt("bytes"))
        store.put("download",key,store.get("download",key)!!.put("status","downloading"))
        repository = BrowseRepository(context,store,http); repository.recoverDownloads()
        assertEquals("interrupted",store.get("download",key)!!.getString("status"))
        val result = repository.download("test/source",game) {}
        assertEquals(16384L,http.offsets.last()); assertArrayEquals(fixture("retra-pad.gb"),result.readBytes())
        repository.completeDownload("test/source",game.id)
        assertEquals("complete",store.get("download",key)!!.getString("status"))
    }

    @Test fun unauthorizedAndCorruptedRomDownloadsAreRejected() = runBlocking {
        val row = json("catalog.json").getJSONArray("games").getJSONObject(0)
        row.getJSONObject("download").put("authorized",false)
        try { repository.download("test/source",GameMetadata(row)) {}; fail("Unauthorized download") } catch (_: IllegalArgumentException) { }
        row.getJSONObject("download").put("authorized",true).put("sha256","a".repeat(64))
        try { repository.download("test/source",GameMetadata(row)) {}; fail("Corrupt download") } catch (_: IllegalStateException) { }
        assertEquals("interrupted",store.all("download").single().getString("status"))
    }

    @Test fun libraryDuplicatesSafRomAssociationAndWebNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val controller = activity.browseController
            val game = GameMetadata(json("catalog.json").getJSONArray("games").getJSONObject(0))
            val first = controller.addLibrary("test/source",game,"Fixture source")
            val second = controller.addLibrary("test/source",game,"Fixture source")
            assertEquals(first.getString("romId"),second.getString("romId"))
            val oldId = first.getString("romId")
            activity.romIdentityStore.updateLibraryMetadata(oldId,true,"[\"Homebrew\"]")
            val rom = File(activity.cacheDir,"browse-fixture.gb").apply { writeBytes(fixture("retra-pad.gb")) }
            runBlocking { controller.associate("test/source",game,android.net.Uri.fromFile(rom)) }
            val linked = controller.metadata(activity.romIdentityStore.findByHash("GB",game.json.getJSONObject("download").getString("sha256"))!!.romId)!!
            val romId = linked.getString("romId")
            runBlocking { controller.associate("test/source",game,android.net.Uri.fromFile(rom)) }
            assertEquals(romId,controller.metadata(romId)!!.getString("romId"))
            val record = activity.romIdentityStore.getById(romId)!!
            assertTrue(record.fileAvailable); assertTrue(record.favorite); assertEquals("[\"Homebrew\"]",record.categoriesJson)
            assertTrue(File(record.launchPath!!).isFile)
            val expectedHash = game.json.getJSONObject("download").getString("sha256")
            assertEquals(1,activity.romIdentityStore.all().count { it.contentHash == expectedHash && !it.archived })
            val wrong = File(activity.cacheDir,"browse-wrong.gba").apply { writeBytes(fixture("retra-pad.gb")) }
            try { runBlocking { controller.associate("test/source",game,android.net.Uri.fromFile(wrong)) }; fail("Platform mismatch") } catch (_: IllegalArgumentException) { }
            waitForJavascript(scenario,"Boolean(window.RetraBrowse)")
            assertTrue(javascript(scenario,"JSON.stringify(Array.from(document.querySelectorAll('.bottom-nav .nav-item')).map(x => x.dataset.page))").contains("browsePage"))
            val manifest = json("extension.json")
            val metadata = json("index.json").getJSONArray("extensions").getJSONObject(0)
            controller.repository.store.put("installed","org.retra.pad",JSONObject().put("repository",base + "index.json").put("metadata",metadata).put("manifest",manifest))
            controller.repository.store.put("catalog",BrowseProtocol.sha256((base + "catalog.json").toByteArray()),json("catalog.json").put("cachedAt",System.currentTimeMillis()))
            javascript(scenario,"document.querySelector('.nav-item[data-page=browsePage]')?.click()")
            waitForJavascript(scenario,"document.querySelector('#browseSources .browse-open') !== null")
            javascript(scenario,"document.querySelector('#browseSources .browse-open').click()")
            waitForJavascript(scenario,"document.querySelectorAll('#catalogGames .browse-game').length === 1")
            javascript(scenario,"document.querySelector('#catalogGames .browse-game').click()")
            waitForJavascript(scenario,"Boolean(document.querySelector('#catalogDetails .browse-detail-actions'))")
            javascript(scenario,"Array.from(document.querySelectorAll('#catalogDetails button')).find(x => x.textContent === 'Add to library')?.click()")
            waitForJavascript(scenario,"Array.from(document.querySelectorAll('#catalogDetails button')).some(x => x.textContent === 'In library')")
            javascript(scenario,"Array.from(document.querySelectorAll('#catalogDetails button')).find(x => x.textContent === 'Play').click()")
            val launchDeadline = System.currentTimeMillis() + 10000
            while (!activity.romLoaded && System.currentTimeMillis() < launchDeadline) Thread.sleep(100)
            assertTrue("The homebrew cartridge must launch in mGBA",activity.romLoaded)
            controller.repository.store.delete("installed","org.retra.pad")
            rom.delete(); wrong.delete()
        }
    }

    private inner class FixtureHttp : BrowseHttp() {
        var offline = false
        var interrupt = false
        val offsets = mutableListOf<Long>()
        override fun bytes(url: String, limit: Int): ByteArray {
            if (offline) throw IOException("offline")
            if (url.endsWith(".pb")) return byteArrayOf(0x0a, 0x12, 0x08, 0x01, 0x12, 0x0e)
            return fixture(url.substringAfterLast('/'))
        }
        override fun open(url: String, offset: Long): HttpURLConnection {
            offsets.add(offset)
            val bytes = fixture("retra-pad.gb")
            val cut = interrupt.also { interrupt = false }
            return object : HttpURLConnection(URL(url)) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = if (offset > 0) 206 else 200
                override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes $offset-${bytes.size - 1}/${bytes.size}" else null
                override fun getInputStream(): InputStream {
                    val input = ByteArrayInputStream(bytes,offset.toInt(),bytes.size - offset.toInt())
                    if (!cut) return input
                    return object : InputStream() {
                        private var read = 0
                        override fun read(): Int { if (read >= 16384) throw IOException("Connection lost"); read++; return input.read() }
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                            if (read >= 16384) throw IOException("Connection lost")
                            val size = input.read(buffer,off,minOf(len,16384 - read)); read += size; return size
                        }
                    }
                }
            }
        }
    }
}
