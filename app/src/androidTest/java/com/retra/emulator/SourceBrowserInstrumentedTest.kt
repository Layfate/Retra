package com.retra.emulator

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SourceBrowserInstrumentedTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun websiteSearchEncodesUserTextAndRejectsUnsafeTemplates() {
        val accented = GameMetadata(JSONObject().put("id", "accented").put("title", "Pokémon Quest").put("platform", "GBA"))
        assertEquals(1, BrowseProtocol.page(listOf(accented), "pokemon", GameFilters(), "popular", 1).games.size)
        assertEquals("https://example.org/?q=Pok%C3%A9mon+%26+Harvest+Moon", BrowseProtocol.websiteSearch("https://example.org/?q={query}", "Pokémon & Harvest Moon"))
        for (template in listOf("javascript:{query}", "https://localhost/?q={query}", "https://example.org/?q=missing", "https://example.org/?q={query}&again={query}"))
            rejects { BrowseProtocol.websiteSearch(template, "game") }
        for (name in listOf("Game.GBA", "game.gbc", "game.gb", "game.zip", "game.bps", "game.ups", "game.ips")) assertTrue(SourceBrowserActivity.supportedDownload(name))
        for (name in listOf("game.apk", "game.exe", "game.html", "game.gba.exe", "game")) assertFalse(SourceBrowserActivity.supportedDownload(name))
    }

    @Test fun websiteBrowserKeepsExternalPagesAwayFromLocalFiles() {
        val intent = Intent(target, SourceBrowserActivity::class.java).putExtra("url", "https://example.org/")
            .putExtra("name", "Example").putExtra("searchUrl", "https://example.org/?q={query}")
        ActivityScenario.launch<SourceBrowserActivity>(intent).use { scenario -> scenario.onActivity { activity ->
            assertFalse(activity.browser.settings.allowFileAccess)
            assertFalse(activity.browser.settings.allowContentAccess)
            assertTrue(activity.browser.settings.safeBrowsingEnabled)
            assertTrue(activity.browser.webViewClient.shouldOverrideUrlLoading(activity.browser, object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse("file:///data/data/com.retra.emulator/shared_prefs/")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }))
        } }
    }

    @Test fun downloadsRejectHtmlTruncationAndOversizeAndImportAZippedCartridge() = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("browse/retra-pad.gb").use { it.readBytes() }
        val file = File.createTempFile("website-test-", ".gb", target.cacheDir)
        fun http(type: String = "application/octet-stream", size: Long = fixture.size.toLong()) = object : BrowseHttp() {
            override fun open(url: String, offset: Long) = object : HttpURLConnection(URL(url)) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = type
                override fun getContentLengthLong() = size
                override fun getInputStream() = ByteArrayInputStream(fixture)
            }
        }
        try {
            saveWebsiteDownload(http(), "https://example.org/game.gb", file) { }
            assertArrayEquals(fixture, file.readBytes())
            for (bad in listOf(http("text/html"), http(size = fixture.size + 1L), http(size = MainActivity.MAX_ROM_BYTES + 1))) {
                rejects { runBlocking { saveWebsiteDownload(bad, "https://example.org/game.gb", file) { } } }
                assertFalse("Rejected downloads must be removed", file.exists())
            }
            val zip = File.createTempFile("website-test-", ".zip", target.cacheDir)
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("Retra Pad.gb")); it.write(fixture); it.closeEntry() }
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var activity: MainActivity
                    scenario.onActivity { activity = it }
                    rejects { activity.importUri(android.net.Uri.fromFile(zip), expectedHash = "0".repeat(64)) }
                    rejects { activity.importUri(android.net.Uri.fromFile(zip), expectedPlatform = "GBA") }
                    val game = GameMetadata(JSONObject().put("id", "website-zip-test").put("title", "Retra Pad").put("platform", "GB")
                        .put("romSha256", BrowseProtocol.sha256(fixture)))
                    activity.browseController.associate("test/website", game, android.net.Uri.fromFile(zip))
                    val key = activity.browseController.repository.downloadKey("test/website", game.id)
                    val row = activity.browseController.repository.store.get("library", key)!!
                    val record = activity.romIdentityStore.getById(row.getString("romId"))!!
                    assertTrue(record.fileAvailable)
                    assertEquals("Retra Pad", record.displayName)
                    assertArrayEquals(fixture, File(record.launchPath!!).readBytes())
                    activity.browseController.repository.store.delete("library", key)
                }
            } finally { zip.delete() }
        } finally { file.delete() }
    }
}
