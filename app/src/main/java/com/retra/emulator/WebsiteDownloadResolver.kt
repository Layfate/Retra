package com.retra.emulator

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Finds the actual ROM / patch file behind a ROM-hack game page (operationidroid, PokéCoders,
 * PokéHarbor, PokeMundo, ...): it ranks the page's download links, follows file hosts
 * (MediaFire, MEGA, Google Drive, GitHub releases, direct links) and checks each candidate
 * really serves a file before choosing it. Pure JVM so it can be tested against the live sites.
 */
internal class WebsiteDownloadResolver(private val http: BrowseHttp) {
    /** A downloadable file. MEGA files are AES-128-CTR encrypted with [aesKey] / [aesIv]. */
    class Resolved(val url: String, val fileName: String?, val size: Long, val host: String,
                   val aesKey: ByteArray? = null, val aesIv: ByteArray? = null) {
        fun decryptor(): Cipher? = aesKey?.let { key ->
            Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(aesIv)) }
        }
    }

    internal class Candidate(val url: String, val host: Host, val score: Double)
    internal enum class Host { DIRECT, MEDIAFIRE, MEGA, DRIVE, GITHUB }

    fun resolve(page: String, title: String): Resolved {
        val html = http.bytes(BrowseProtocol.https(page), PAGE_LIMIT).toString(Charsets.UTF_8)
        val candidates = candidates(page, html, title)
        require(candidates.isNotEmpty()) { "No download link found on the game page" }
        var lastError: Exception? = null
        for (candidate in candidates.take(MAX_ATTEMPTS)) {
            try {
                return probe(follow(candidate))
            } catch (error: Exception) {
                lastError = error
            }
        }
        error(lastError?.message?.let { "Download link unavailable: $it" } ?: "Download link unavailable")
    }

    // ---------------------------------------------------------------- page links

    /** Download links on the page, best first: real buttons, matching file names, reliable hosts. */
    internal fun candidates(page: String, html: String, title: String): List<Candidate> {
        val titleTokens = tokens(title)
        val seen = HashSet<String>()
        return ANCHOR.findAll(html).mapIndexedNotNull { order, match ->
            val attributes = match.groupValues[1]
            val href = HREF.find(attributes)?.groupValues?.get(1)?.let(::unescape) ?: return@mapIndexedNotNull null
            val url = runCatching { URL(URL(page), href).toString() }.getOrNull() ?: return@mapIndexedNotNull null
            val host = classify(url) ?: return@mapIndexedNotNull null
            if (!seen.add(url.substringBefore('#') + "#" + url.substringAfter('#', ""))) return@mapIndexedNotNull null
            // Button classes and visible text, not the URL itself (which always names its host).
            val label = (attributes.replace(HREF, " ") + " " + match.groupValues[2].replace(TAG, " ")).lowercase(Locale.US)
            var score = when (host) {
                Host.DIRECT, Host.MEDIAFIRE -> 30.0
                Host.MEGA -> 26.0
                Host.DRIVE -> 22.0
                Host.GITHUB -> 12.0
            }
            if (LABEL_HINT.containsMatchIn(label)) score += 20
            // Pages often link other hacks; a file name sharing words with the title is the right one.
            val nameTokens = tokens(fileNameFromUrl(url.substringBefore('#')).substringBeforeLast('.'))
            if (titleTokens.isNotEmpty() && nameTokens.isNotEmpty()) {
                score += 25.0 * titleTokens.count { it in nameTokens } / titleTokens.size
            }
            Candidate(url, host, score - order * 0.001)
        }.sortedByDescending { it.score }.toList()
    }

    internal fun classify(url: String): Host? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https") return null
        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return null
        val path = uri.rawPath.orEmpty()
        return when {
            host == "mediafire.com" && (path.startsWith("/file/") || path.startsWith("/file_premium/") || path.startsWith("/download/") ||
                (path.isEmpty() || path == "/") && !uri.rawQuery.isNullOrEmpty()) -> Host.MEDIAFIRE
            (host == "mega.nz" || host == "mega.co.nz") && megaLink(url) != null -> Host.MEGA
            (host == "drive.google.com" || host == "docs.google.com") && driveId(url) != null -> Host.DRIVE
            host == "github.com" && SUPPORTED.any { path.lowercase(Locale.US).endsWith(".$it") } -> Host.DIRECT
            host == "github.com" && GITHUB_REPO.find(path) != null -> Host.GITHUB
            SUPPORTED.any { fileNameFromUrl(url).lowercase(Locale.US).endsWith(".$it") } -> Host.DIRECT
            else -> null
        }
    }

    // ---------------------------------------------------------------- hosts

    private fun follow(candidate: Candidate): Resolved = when (candidate.host) {
        Host.DIRECT -> Resolved(BrowseProtocol.https(candidate.url), null, -1, "direct")
        Host.MEDIAFIRE -> mediafire(candidate.url)
        Host.MEGA -> mega(candidate.url)
        Host.DRIVE -> Resolved(BrowseProtocol.https("https://drive.usercontent.google.com/download?id=${driveId(candidate.url)}&export=download&confirm=t"),
            null, -1, "Google Drive")
        Host.GITHUB -> github(candidate.url)
    }

    private fun mediafire(url: String): Resolved {
        var target = BrowseProtocol.https(url.substringBefore('#'))
        // Some file pages first hand out a one-time "dkey" link back to the file page, which then
        // carries the direct download link.
        repeat(2) {
            val page = http.bytes(target, PAGE_LIMIT).toString(Charsets.UTF_8)
            val direct = MEDIAFIRE_DIRECT.find(page)?.groupValues?.get(1)?.let(::unescape)
                ?: MEDIAFIRE_SCRAMBLED.find(page)?.groupValues?.get(1)?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
            if (direct != null) return Resolved(BrowseProtocol.https(direct), null, -1, "MediaFire")
            val next = MEDIAFIRE_DKEY.find(page)?.groupValues?.get(1)?.let(::unescape) ?: return@repeat
            target = BrowseProtocol.https(URL(URL(target), next).toString())
        }
        error("MediaFire did not provide a download (file removed or unavailable)")
    }

    /** MEGA public file links: id + 256-bit key. The file content is AES-128-CTR encrypted. */
    private fun mega(url: String): Resolved {
        val (id, keyText) = megaLink(url) ?: error("Unsupported MEGA link")
        val raw = Base64.getUrlDecoder().decode(keyText.trimEnd('='))
        require(raw.size == 32) { "Invalid MEGA file key" }
        val words = ByteBuffer.wrap(raw).asIntBuffer().let { buffer -> IntArray(8) { buffer.get(it) } }
        val key = ByteBuffer.allocate(16).apply { for (i in 0 until 4) putInt(words[i] xor words[i + 4]) }.array()
        val iv = ByteBuffer.allocate(16).putInt(words[4]).putInt(words[5]).putLong(0).array()
        val response = http.post("https://g.api.mega.co.nz/cs?id=${System.nanoTime() % 1_000_000_000}",
            "[{\"a\":\"g\",\"g\":1,\"ssl\":2,\"p\":\"$id\"}]", 64 * 1024).toString(Charsets.UTF_8)
        Regex("""^\[\s*(-\d+)\s*]""").find(response.trim())?.let {
            error(when (it.groupValues[1].toInt()) {
                -9 -> "the MEGA file was deleted"
                -6, -16 -> "MEGA has blocked this file"
                -17 -> "MEGA's download quota is used up, try later"
                -3, -4, -18 -> "MEGA is busy, try again in a moment"
                else -> "MEGA file unavailable (error ${it.groupValues[1]})"
            })
        }
        val link = Regex(""""g"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: error("MEGA did not provide a download (quota reached or file removed)")
        val size = Regex(""""s"\s*:\s*(\d+)""").find(response)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val name = Regex(""""at"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)?.let { attributes ->
            runCatching {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                val text = String(cipher.doFinal(Base64.getUrlDecoder().decode(attributes.trimEnd('='))), Charsets.UTF_8)
                Regex(""""n"\s*:\s*"([^"]+)"""").find(text.removePrefix("MEGA"))?.groupValues?.get(1)
            }.getOrNull()
        }
        return Resolved(BrowseProtocol.https(link.replaceFirst("http://", "https://")), name, size, "MEGA", key, iv)
    }

    /** GitHub repository / release page: the latest release's ROM, archive or patch asset. */
    private fun github(url: String): Resolved {
        val match = GITHUB_REPO.find(URI(url).rawPath) ?: error("Unsupported GitHub link")
        val (owner, repo) = match.destructured
        val tag = Regex("""/releases/tag/([^/?#]+)""").find(URI(url).rawPath)?.groupValues?.get(1)
        val api = if (tag != null) "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
            else "https://api.github.com/repos/$owner/$repo/releases/latest"
        val json = http.bytes(api, 2 * 1024 * 1024).toString(Charsets.UTF_8)
        val assets = Regex(""""browser_download_url"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        val best = assets.sortedBy { asset ->
            val ext = asset.substringAfterLast('.', "").lowercase(Locale.US)
            listOf("gba", "gbc", "gb", "zip", "bps", "ups", "ips").indexOf(ext).let { if (it < 0) 99 else it }
        }.firstOrNull { asset -> SUPPORTED.any { asset.lowercase(Locale.US).endsWith(".$it") } }
            ?: error("The GitHub release has no ROM, ZIP or patch file")
        return Resolved(BrowseProtocol.https(best), null, -1, "GitHub")
    }

    /** Opens the file to confirm it is a download (not an HTML error or quota page). */
    private fun probe(resolved: Resolved): Resolved {
        val connection = http.open(resolved.url)
        try {
            val type = connection.contentType.orEmpty()
            require(!type.contains("text/html", true) && !type.contains("application/json", true)) {
                "${resolved.host} returned a web page instead of the file"
            }
            val name = resolved.fileName ?: fileNameFromDisposition(connection.getHeaderField("Content-Disposition"))
                ?: fileNameFromUrl(connection.url.toString()).ifBlank { fileNameFromUrl(resolved.url) }
            require(SUPPORTED.any { name.lowercase(Locale.US).endsWith(".$it") }) {
                "${resolved.host} file \"$name\" is not a GB, GBC, GBA, ZIP or patch file"
            }
            val size = if (resolved.size > 0) resolved.size else connection.contentLengthLong
            return Resolved(resolved.url, name, size, resolved.host, resolved.aesKey, resolved.aesIv)
        } finally { connection.disconnect() }
    }

    // ---------------------------------------------------------------- helpers

    private fun megaLink(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val fragment = uri.rawFragment.orEmpty()
        Regex("""^/file/([A-Za-z0-9_-]{8,})$""").find(uri.rawPath.orEmpty())?.let { path ->
            val key = fragment.takeIf { MEGA_KEY.matches(it) } ?: return null
            return path.groupValues[1] to key
        }
        // Legacy form: https://mega.nz/#!<id>!<key>
        Regex("""^!([A-Za-z0-9_-]{8,})!([A-Za-z0-9_-]{43,44})$""").find(fragment)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    private fun driveId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        Regex("""/file/d/([A-Za-z0-9_-]{10,})""").find(uri.rawPath.orEmpty())?.let { return it.groupValues[1] }
        return Regex("""(?:^|&)id=([A-Za-z0-9_-]{10,})""").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
    }

    private fun tokens(text: String): Set<String> = URLDecoder.decode(text.replace("+", " "), "UTF-8")
        .lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP_WORDS }.toSet()

    private fun unescape(value: String) = value.replace("&amp;", "&").replace("&#038;", "&").replace("&#38;", "&")
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&#x2F;", "/").trim()

    internal companion object {
        const val PAGE_LIMIT = 4 * 1024 * 1024
        const val MAX_ATTEMPTS = 6
        val SUPPORTED = setOf("gba", "gbc", "gb", "zip", "ips", "ups", "bps")
        val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val HREF = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]*>")
        val LABEL_HINT = Regex("""download|mirror|wp-block-button|btn|mediafire|mega|drive""")
        val MEDIAFIRE_DIRECT = Regex("""href\s*=\s*["'](https://download\d*\.mediafire\.com/[^"']+)["']""")
        val MEDIAFIRE_DKEY = Regex("""href\s*=\s*["']((?:https:)?//(?:www\.)?mediafire\.com/file/[^"']*[?&](?:amp;)?dkey=[^"']+)["']""")
        val MEDIAFIRE_SCRAMBLED = Regex("""data-scrambled-url\s*=\s*["']([A-Za-z0-9+/=]+)["']""")
        val MEGA_KEY = Regex("""[A-Za-z0-9_-]{43,44}""")
        val GITHUB_REPO = Regex("""^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/releases(?:/.*)?)?/?$""")
        val STOP_WORDS = setOf("pokemon", "the", "and", "rom", "hack", "gba", "gbc", "version", "download", "file", "zip")
    }
}
