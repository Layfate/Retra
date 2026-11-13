package com.retra.emulator

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end artwork check against the published Retra repository (needs internet):
 * native fetch + WebP cache, the appassets /browse-images/ route, WebView decoding,
 * and the Browse UI rendering extension icons and catalog covers.
 */
@RunWith(AndroidJUnit4::class)
class BrowseImageInstrumentedTest {
    private val repo = "https://raw.githubusercontent.com/Layfate/Retra/main/extensions/index.json"
    private val boxart = "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/" +
        "Named_Boxarts/Harvest%20Moon%20-%20Friends%20of%20Mineral%20Town%20(USA).png"

    private fun javascript(scenario: ActivityScenario<MainActivity>, script: String): String {
        val reply = CountDownLatch(1)
        var result = ""
        scenario.onActivity { it.binding.webView.evaluateJavascript(script) { value -> result = value; reply.countDown() } }
        assertTrue(reply.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: String, seconds: Int = 45) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (javascript(scenario, condition) == "true") return
            Thread.sleep(250)
        }
        fail("Web UI did not reach: $condition")
    }

    @Test fun coversAndIconsLoadFromTheLiveRepository() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val repository = activity.browseController.repository

            // 1. Native pipeline: download, decode, re-encode to WebP in app storage.
            val local = repository.cachedImage(boxart)
            assertTrue(local, local.startsWith("https://appassets.androidplatform.net/browse-images/"))
            assertTrue(File(repository.imageDir, local.substringAfterLast('/')).length() > 0)

            // 2. WebView: the cached file is served from the app origin and decodes as an image.
            waitFor(scenario, "Boolean(window.RetraBrowse)")
            javascript(scenario, "window.__img = 0; (function(){ const i = new Image(); i.onload = () => window.__img = i.naturalWidth; i.onerror = () => window.__img = -1; i.src = '$local'; })()")
            waitFor(scenario, "window.__img !== 0", 20)
            assertTrue("WebView could not decode the cached cover", javascript(scenario, "window.__img").toInt() > 0)

            // 3. Browse UI: install the live extension, then expect real icons and catalog covers.
            runCatching { repository.addRepository(repo) }
            repository.trust(repo, true)
            val listed = BrowseProtocol.extensions(repository.store.get("repository", repo)!!.getJSONObject("index")).map { it.id }
            val extension = listed.firstOrNull { it == "org.retra.romhackplaza" } ?: listed.first()
            repository.install(repo, extension)
            javascript(scenario, "document.querySelector('.nav-item[data-page=browsePage]').click()")
            waitFor(scenario, "document.querySelector('#browseSources .browse-icon img') !== null")
            javascript(scenario, "document.querySelector('#browseSources .browse-open').click()")
            waitFor(scenario, "document.querySelectorAll('#catalogGames .cover-card > img').length > 0", 60)
            assertTrue(javascript(scenario, "document.querySelector('#catalogGames .cover-card > img').naturalWidth > 0") == "true")

            // 4. Global search: every installed source answers in its own row.
            javascript(scenario, "document.querySelector('#catalogPage .back-btn').click()")
            waitFor(scenario, "document.getElementById('browsePage').classList.contains('active')")
            javascript(scenario, "document.getElementById('searchTab').click()")
            javascript(scenario, "(function(){ const i = document.getElementById('browseSearchInput'); i.value = 'pokemon'; i.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true})); })()")
            waitFor(scenario, "document.querySelectorAll('#browseGlobal .browse-search-section').length > 0 && !document.querySelector('#browseGlobal .browse-spinner')", 60)
            assertTrue("Global search returned no games", javascript(scenario, "document.querySelectorAll('#browseGlobal .browse-game').length > 0") == "true")

            repository.store.delete("installed", extension)
            repository.store.delete("repository", repo)
        }
    }
}
