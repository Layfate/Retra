package com.retra.emulator

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebsiteDownloadResolverTest {
    private val resolver = WebsiteDownloadResolver(BrowseHttp { mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36") })

    @Test fun ranksButtonsAndMatchingFileNamesAboveOtherLinks() {
        val html = """
            <a href="https://www.mediafire.com/file/aaa/Other+Hack.zip/file">Other hack</a>
            <a class="wp-block-button__link" href="https://mega.nz/file/RYRR3SaA#ZNImd_iFadRizTHccIJameQywmkVvfqVOU2QYWwFCKk">Click Here to Download</a>
            <a class="wp-block-button__link" href="https://www.mediafire.com/file/bbb/Digimon+Emerald+Project+2.0.zip/file">Mirror</a>
            <a href="https://mega.nz/folder/abc#def">Folder</a>
            <a href="https://www.pokecommunity.com/threads/1">Thread</a>
        """.trimIndent()
        val candidates = resolver.candidates("https://example.com/digimon/", html, "Digimon Emerald Project")
        assertEquals(listOf(WebsiteDownloadResolver.Host.MEDIAFIRE, WebsiteDownloadResolver.Host.MEGA, WebsiteDownloadResolver.Host.MEDIAFIRE),
            candidates.map { it.host })
        assertEquals("https://www.mediafire.com/file/bbb/Digimon+Emerald+Project+2.0.zip/file", candidates.first().url)
    }

    @Test fun classifiesFileHosts() {
        assertEquals(WebsiteDownloadResolver.Host.DRIVE, resolver.classify("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?usp=sharing"))
        assertEquals(WebsiteDownloadResolver.Host.MEGA, resolver.classify("https://mega.nz/#!RYRR3SaA!ZNImd_iFadRizTHccIJameQywmkVvfqVOU2QYWwFCKk"))
        assertEquals(WebsiteDownloadResolver.Host.DIRECT, resolver.classify("https://github.com/o/r/releases/download/v1/hack.bps"))
        assertEquals(WebsiteDownloadResolver.Host.GITHUB, resolver.classify("https://github.com/o/r/releases"))
        assertEquals(null, resolver.classify("https://github.com/o/r/issues/4"))
        assertEquals(null, resolver.classify("http://example.com/hack.gba"))
    }

    /**
     * Live check against the catalog sites: RETRA_LIVE_CATALOGS = folder of catalog JSON files.
     * Resolves every game, downloads the first bytes and checks they are a ROM, ZIP or patch.
     */
    @Test fun liveCatalogs() {
        val dir = System.getenv("RETRA_LIVE_CATALOGS")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        val only = System.getenv("RETRA_LIVE_ONLY")?.let(::Regex)
        val pool = Executors.newFixedThreadPool(System.getenv("RETRA_LIVE_THREADS")?.toIntOrNull() ?: 12)
        dir!!.listFiles()!!.filter { it.name.endsWith(".json") && (only == null || only.containsMatchIn(it.name)) }.sortedBy { it.name }.forEach { catalog ->
            val text = catalog.readText()
            val games = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"[\s\S]*?"website"\s*:\s*"([^"]+)"""").findAll(text)
                .filter { Regex(""""gameType"\s*:\s*"official"""").find(text) == null }
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (games.isEmpty()) return@forEach
            val results = games.map { (title, page) -> pool.submit<String> { check(title, page) } }.map { it.get(3, TimeUnit.MINUTES) }
            val summary = results.groupingBy { it.substringBefore(':') }.eachCount()
            println("LIVE ${catalog.name}: ${games.size} games $summary")
            results.zip(games).filter { !it.first.startsWith("OK") }.forEach { (result, game) -> println("LIVE   ${result.take(120)} | ${game.first.take(40)} | ${game.second}") }
        }
        pool.shutdown()
    }

    private fun check(title: String, page: String): String = try {
        val file = resolver.resolve(page, title)
        val head = ByteArray(0x150)
        val connection = BrowseHttp { mapOf("User-Agent" to "Mozilla/5.0", "Range" to "bytes=0-${head.size - 1}") }.open(file.url)
        var read = 0
        try {
            val stream = file.decryptor()?.let { javax.crypto.CipherInputStream(connection.inputStream, it) } ?: connection.inputStream
            stream.use { input -> while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n } }
        } finally { connection.disconnect() }
        val kind = magic(head, read)
        if (kind == null) "BAD-BYTES: ${file.host} ${file.fileName}" else "OK: ${file.host} $kind ${file.fileName}"
    } catch (error: Exception) { "FAIL: ${error.message}" }

    private fun magic(b: ByteArray, n: Int): String? {
        fun at(offset: Int, vararg bytes: Int) = n >= offset + bytes.size && bytes.indices.all { (b[offset + it].toInt() and 0xff) == bytes[it] }
        return when {
            at(0, 0x50, 0x4b, 0x03, 0x04) -> "zip"
            at(0, 0x50, 0x41, 0x54, 0x43, 0x48) -> "ips"
            at(0, 0x55, 0x50, 0x53, 0x31) -> "ups"
            at(0, 0x42, 0x50, 0x53, 0x31) -> "bps"
            at(4, 0x24, 0xff, 0xae, 0x51) -> "gba"
            at(0x104, 0xce, 0xed, 0x66, 0x66) -> "gb"
            at(0, 0x37, 0x7a, 0xbc, 0xaf) -> "7z"
            at(0, 0x52, 0x61, 0x72, 0x21) -> "rar"
            else -> null
        }
    }
}
