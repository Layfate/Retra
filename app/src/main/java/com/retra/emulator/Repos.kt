package com.retra.emulator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** One downloadable game listed in a repo index. */
data class RepoGame(val title: String, val url: String, val fileName: String, val sha256: String?, val description: String)

data class Repo(val url: String, val name: String, val games: List<RepoGame>)

/**
 * Parses a Retra repo index (format documented in README.md). Entries with an unsupported file type
 * or a non-https link are skipped, so a bad repo can't make Retra download something it can't play.
 */
fun parseRepo(repoUrl: String, json: String): Repo {
    val root = JSONObject(json)
    val list = root.optJSONArray("games") ?: JSONArray()
    val games = (0 until list.length()).mapNotNull { i ->
        val entry = list.optJSONObject(i) ?: return@mapNotNull null
        val url = runCatching { URL(URL(repoUrl), entry.getString("url")) }.getOrNull()
            ?.takeIf { it.protocol == "https" } ?: return@mapNotNull null
        val urlName = URLDecoder.decode(url.path.substringAfterLast('/'), "UTF-8")
        val file = romFileName(entry.optString("file").ifBlank { urlName }) ?: return@mapNotNull null
        RepoGame(
            title = entry.optString("title").ifBlank { file },
            url = url.toString(),
            fileName = file,
            sha256 = entry.optString("sha256").ifBlank { null },
            description = entry.optString("description"),
        )
    }
    return Repo(repoUrl, root.optString("name").ifBlank { URL(repoUrl).host }, games.distinctBy { it.url })
}

/** The user's repo list. Retra ships with none: like Mihon, sources are whatever the user chooses to add. */
object Repos {
    private fun prefs(context: Context) = context.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun urls(context: Context): List<String> = prefs(context).getStringSet("urls", null).orEmpty().sorted()

    /** Returns false if [url] isn't an https URL. */
    fun add(context: Context, url: String): Boolean {
        if (runCatching { URL(url).protocol }.getOrNull() != "https") return false
        prefs(context).edit().putStringSet("urls", (urls(context) + url).toSet()).apply()
        return true
    }

    fun remove(context: Context, url: String) =
        prefs(context).edit().putStringSet("urls", (urls(context) - url).toSet()).apply()

    // shortcut: no size cap on the index body, add one if repos ever get abused to exhaust memory
    fun fetch(url: String): Repo = parseRepo(url, open(url).bufferedReader().use { it.readText() })

    fun open(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Retra")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP ${connection.responseCode} from $url")
        }
        return connection.inputStream
    }
}
