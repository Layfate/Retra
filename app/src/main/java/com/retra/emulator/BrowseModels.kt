package com.retra.emulator

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

enum class GamePlatform { GBA, GBC, GB }
data class ExtensionRepository(val url: String, val name: String, val trusted: Boolean)
data class ExtensionMetadata(val json: JSONObject) {
    val id = json.getString("id")
    val version = json.getInt("versionCode")
    fun compatible() = json.optString("format") == "retra.catalog" &&
        json.getInt("apiMin") <= BrowseProtocol.API && json.getInt("apiMax") >= BrowseProtocol.API &&
        (0 until json.getJSONArray("platforms").length()).all {
            json.getJSONArray("platforms").getString(it) in GamePlatform.entries.map { platform -> platform.name }
        }
}
data class GameMetadata(val json: JSONObject) {
    val id = json.getString("id")
    val platform = GamePlatform.valueOf(json.getString("platform"))
}
data class GameDetails(val metadata: GameMetadata)
data class GameFilters(val values: Map<String, String> = emptyMap())
data class GamePage(val games: List<GameMetadata>, val page: Int, val hasNext: Boolean)
data class DownloadState(val status: String, val bytes: Long = 0, val total: Long = -1, val error: String = "")
data class LibraryEntry(val sourceId: String, val gameId: String, val romId: String, val romUri: String?)

interface GameSource {
    val id: String
    val name: String
    val supportedPlatforms: List<GamePlatform>
    suspend fun getPopularGames(page: Int): GamePage
    suspend fun getLatestGames(page: Int): GamePage
    suspend fun searchGames(query: String, filters: GameFilters, page: Int): GamePage
    suspend fun getGameDetails(gameId: String): GameDetails
}

/** Retra catalog API 1 is data only; manifests never contain executable adapters. */
object BrowseProtocol {
    const val API = 1
    const val PAGE_SIZE = 30
    val filterKeys = listOf("platform", "genre", "year", "region", "language", "gameType", "availability")
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    fun id(value: String): String = value.also { require(identifier.matches(it)) { "Invalid identifier" } }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun hash(value: String): String = value.lowercase().also {
        require(Regex("[a-f0-9]{64}").matches(it)) { "Missing or invalid SHA-256" }
    }
    fun https(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "Use a public HTTPS URL" }
        require(uri.host != "appassets.androidplatform.net" && uri.host != "localhost" &&
            !uri.host.endsWith(".local")) { "Local URLs are not supported" }
        return uri.toASCIIString()
    }
    fun repositoryUrl(value: String): String {
        var url = https(value)
        val uri = URI(url)
        if (uri.host == "github.com" && uri.path.contains("/blob/")) {
            url = "https://raw.githubusercontent.com/" + uri.path.removePrefix("/").replaceFirst("/blob/", "/")
        }
        return if (URI(url).path.endsWith("/")) url + "index.json" else url
    }
    fun extensions(index: JSONObject): List<ExtensionMetadata> {
        require(index.getInt("schemaVersion") == API) { "Unsupported repository schema" }
        require(index.getString("name").isNotBlank()) { "Repository name required" }
        val rows = index.getJSONArray("extensions")
        require(rows.length() <= 500) { "Too many extensions" }
        val result = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            id(row.getString("id")); require(row.getInt("versionCode") > 0)
            require(row.getString("name").isNotBlank() && row.getString("publisher").isNotBlank())
            require(row.getInt("apiMin") > 0 && row.getInt("apiMax") >= row.getInt("apiMin"))
            https(row.getString("packageUrl")); hash(row.getString("sha256"))
            optionalImage(row, "icon")
            val advertised = row.getJSONArray("platforms")
            require(advertised.length() in 1..32)
            for (j in 0 until advertised.length()) require(advertised.getString(j).matches(Regex("[A-Z0-9_]{1,16}")))
            ExtensionMetadata(row)
        }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate extension IDs" }
        return result
    }
    fun platforms(rows: JSONArray): List<GamePlatform> {
        require(rows.length() in 1..3) { "Supported platforms: GBA, GBC, GB" }
        return (0 until rows.length()).map { GamePlatform.valueOf(rows.getString(it)) }.distinct()
    }
    private fun optionalImage(row: JSONObject, key: String) {
        if (row.optString(key).isNotBlank()) https(row.getString(key))
    }
    fun manifest(bytes: ByteArray, metadata: ExtensionMetadata): JSONObject {
        require(sha256(bytes) == hash(metadata.json.getString("sha256"))) { "Package SHA-256 mismatch" }
        require(metadata.compatible()) { "Incompatible extension API or package format" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("apiVersion") == API && json.getString("id") == metadata.id &&
            json.getInt("versionCode") == metadata.version) { "Package identity mismatch" }
        val sources = json.getJSONArray("sources")
        require(sources.length() in 1..50)
        val ids = mutableSetOf<String>()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            require(ids.add(id(source.getString("id")))) { "Duplicate source IDs" }
            require(source.getString("name").isNotBlank())
            val supported = platforms(source.getJSONArray("platforms"))
            require(platforms(metadata.json.getJSONArray("platforms")).containsAll(supported))
            https(source.getString("catalogUrl")); optionalImage(source, "icon")
        }
        return json
    }
    fun catalog(json: JSONObject, supported: List<GamePlatform>): List<GameMetadata> {
        require(json.getInt("apiVersion") == API)
        val rows = json.getJSONArray("games")
        // shortcut: API 1 caps static catalogs at 10,000 games; add server paging for larger sources.
        require(rows.length() <= 10000) { "Catalog exceeds API 1 limit" }
        val ids = mutableSetOf<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            require(ids.add(id(row.getString("id")))) { "Duplicate game IDs" }
            require(row.getString("title").isNotBlank())
            val game = GameMetadata(row)
            require(game.platform in supported) { "Game platform outside source platforms" }
            optionalImage(row, "cover")
            optionalImage(row, "website") // Developer page: any public HTTPS URL.
            if (row.optString("romSha256").isNotBlank()) hash(row.getString("romSha256"))
            row.optJSONArray("screenshots")?.let { images ->
                require(images.length() <= 20)
                for (j in 0 until images.length()) https(images.getString(j))
            }
            row.optJSONObject("download")?.let { file ->
                https(file.getString("url")); hash(file.getString("sha256"))
                https(file.getString("rightsUrl"))
                require(file.getLong("size") in 1..(128L * 1024 * 1024))
                require(file.getString("fileName").matches(Regex("[A-Za-z0-9._ -]+\\.(gba|gbc|gb)")))
                require(file.getString("fileName").substringAfterLast('.').uppercase() == game.platform.name)
            }
            game
        }
    }
    /**
     * Licenses that permit redistribution of the file (SPDX-style ids). A combined license such as
     * "GPL-3.0-or-later AND CC-BY-NC-SA-4.0" is allowed only when every part is. Keep in sync with
     * allowedLicense in browse-ui.js and LICENSE in tools/extension-repo/build-repo.cjs.
     */
    val LICENSE = Regex("(CC0-1\\.0|CC-BY(-NC)?(-SA|-ND)?(-[1-4]\\.0)?|MIT|0BSD|ISC|BSD-[23]-Clause|Apache-2\\.0|" +
        "(L|A)?GPL-[23]\\.0(-only|-or-later)?|MPL-2\\.0|Zlib|Unlicense|WTFPL|FSFAP|public-domain|homebrew-freeware)")
    fun allowedLicense(value: String) = value.length <= 200 && value.split(" AND ").all { LICENSE.matches(it) }
    fun authorized(file: JSONObject?) = file != null && file.optBoolean("authorized") &&
        allowedLicense(file.optString("license"))

    fun page(games: List<GameMetadata>, query: String, filters: GameFilters, mode: String, page: Int): GamePage {
        require(page in 1..10000)
        val filtered = games.filter { game ->
            val row = game.json
            row.getString("title").contains(query, ignoreCase = true) && filters.values.all { (key, value) ->
                require(key in filterKeys)
                val field = if (key == "availability") {
                    if (authorized(row.optJSONObject("download"))) "download" else "catalog"
                } else row.optString(key)
                value.isBlank() || field.split(',').any { it.trim().equals(value, ignoreCase = true) }
            }
        }.sortedWith(if (mode == "latest") compareByDescending { it.json.optLong("updatedAt") }
            else compareByDescending { it.json.optDouble("popularity", 0.0) })
        val start = (page - 1) * PAGE_SIZE
        return GamePage(filtered.drop(start).take(PAGE_SIZE), page, start + PAGE_SIZE < filtered.size)
    }
}
