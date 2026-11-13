package com.retra.emulator

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/** External pages never share Retra's privileged WebView or its AndroidBridge. */
class SourceBrowserActivity : AppCompatActivity() {
    internal lateinit var browser: WebView
    private lateinit var status: TextView
    // shortcut: website transfers end with this activity; use a foreground worker for restartable downloads.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var download: Job? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = runCatching { BrowseProtocol.https(intent.getStringExtra("url").orEmpty()) }.getOrNull()
        if (url == null) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = MaterialToolbar(this).apply {
            title = intent.getStringExtra("name") ?: "Website"
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            navigationContentDescription = "Close website"
            setNavigationOnClickListener { closeBrowser() }
            menu.add("Reload").setOnMenuItemClickListener { browser.reload(); true }
        }
        root.addView(toolbar)
        val searchUrl = intent.getStringExtra("searchUrl").orEmpty()
        if (runCatching { BrowseProtocol.websiteSearch(searchUrl, "game") }.isSuccess) {
            root.addView(EditText(this).apply {
                hint = "Search games on website"
                contentDescription = hint
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, action, _ ->
                    if (action != EditorInfo.IME_ACTION_SEARCH) false else {
                        browser.loadUrl(BrowseProtocol.websiteSearch(searchUrl, text.toString()))
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .hideSoftInputFromWindow(windowToken, 0)
                        true
                    }
                }
            })
        }
        status = TextView(this).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.buildUpon().fragment(null).build().toString()
                    val blocked = runCatching { BrowseProtocol.https(target) }.isFailure
                    if (blocked && request.isForMainFrame) status.text = "Only HTTPS website links are supported."
                    return blocked
                }
                override fun onPageFinished(view: WebView, url: String) {
                    toolbar.subtitle = android.net.Uri.parse(url).host
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress.progress = newProgress }
            }
            setDownloadListener { target, agent, disposition, mime, _ ->
                val name = URLUtil.guessFileName(target, disposition, mime).substringAfterLast('/').substringAfterLast('\\')
                if (!supportedDownload(name)) {
                    status.text = "Choose a GBA, GB, GBC, ZIP, IPS, UPS or BPS file."
                } else if (download?.isActive != true) {
                    AlertDialog.Builder(this@SourceBrowserActivity).setTitle("Download into Retra?")
                        .setMessage(name).setNegativeButton("Cancel", null)
                        .setPositiveButton("Download") { _, _ -> downloadFile(target, name, agent, this.url.orEmpty()) }.show()
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (browser.canGoBack()) browser.goBack() else closeBrowser()
        }
        browser.loadUrl(url)
    }

    private fun downloadFile(url: String, name: String, agent: String, page: String) {
        status.text = "Downloading $name…"
        val cookies = CookieManager.getInstance()
        download = scope.launch {
            var file: File? = null
            try {
                val http = BrowseHttp { target -> buildMap {
                    put("User-Agent", agent)
                    cookies.getCookie(target)?.let { put("Cookie", it) }
                    put("Referer", page)
                } }
                val dir = File(cacheDir, "website-downloads").apply { mkdirs() }
                val target = File.createTempFile("game-", ".${name.substringAfterLast('.').lowercase()}", dir)
                file = target
                saveWebsiteDownload(http, url, target) { count ->
                    runOnUiThread { status.text = "Downloading $name: ${count / 1024} KiB" }
                }
                coroutineContext.ensureActive()
                runOnUiThread {
                    if (isDestroyed || isFinishing) { target.delete(); return@runOnUiThread }
                    setResult(RESULT_OK, Intent().putExtra("path", target.absolutePath).putExtra("fileName", name))
                    finish()
                }
                file = null // MainActivity imports and removes the completed file.
            } catch (error: Exception) {
                if (error !is CancellationException) runOnUiThread { status.text = error.message ?: "Download failed. Please retry." }
            } finally { file?.delete() }
        }
    }

    private fun closeBrowser() {
        if (download?.isActive != true) finish() else AlertDialog.Builder(this)
            .setMessage("Cancel this download and close the website?").setNegativeButton("Keep downloading", null)
            .setPositiveButton("Close") { _, _ -> download?.cancel(); finish() }.show()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::browser.isInitialized) { browser.stopLoading(); browser.destroy() }
        super.onDestroy()
    }

    companion object {
        internal fun supportedDownload(name: String) = name.substringAfterLast('.', "").lowercase() in
            setOf("gba", "gbc", "gb", "zip", "ips", "ups", "bps", "mgba")
    }
}

internal suspend fun saveWebsiteDownload(http: BrowseHttp, url: String, target: File, progress: (Long) -> Unit) {
    try {
        val connection = http.open(url)
        try {
            require(connection.responseCode == 200) { "Unexpected partial download" }
            require(connection.contentLengthLong <= MainActivity.MAX_ROM_BYTES) { "Download too large" }
            require(!connection.contentType.orEmpty().contains("text/html", true)) { "This is a web page. Follow its download link." }
            var count = 0L
            var reported = 0L
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= MainActivity.MAX_ROM_BYTES) { "Download too large" }
                    output.write(buffer, 0, read)
                    if (count - reported >= 262144) { reported = count; progress(count) }
                }
            } }
            require(count > 0 && (connection.contentLengthLong < 0 || count == connection.contentLengthLong)) { "Incomplete download. Please retry." }
        } finally { connection.disconnect() }
    } catch (error: Exception) { target.delete(); throw error }
}
