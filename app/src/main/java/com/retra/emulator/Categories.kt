package com.retra.emulator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A user-made shelf in the Rom tab. Games can be in several; games in none show under "Default". */
data class Category(val id: String, val name: String)

fun encodeCategories(list: List<Category>): String =
    JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()

fun decodeCategories(json: String?): List<Category> = runCatching {
    val array = JSONArray(json ?: return emptyList())
    (0 until array.length()).map { array.getJSONObject(it).let { o -> Category(o.getString("id"), o.getString("name")) } }
}.getOrDefault(emptyList())

object Categories {
    private fun prefs(context: Context) = context.getSharedPreferences("categories", Context.MODE_PRIVATE)
    private fun save(context: Context, list: List<Category>) = prefs(context).edit().putString("list", encodeCategories(list)).apply()
    private fun gameKey(game: File) = "game:" + game.absolutePath

    fun all(context: Context): List<Category> = decodeCategories(prefs(context).getString("list", null))

    fun add(context: Context, name: String) = save(context, all(context) + Category(UUID.randomUUID().toString(), name))

    fun rename(context: Context, id: String, name: String) =
        save(context, all(context).map { if (it.id == id) it.copy(name = name) else it })

    fun delete(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
        val p = prefs(context)
        val edit = p.edit()
        p.all.filterKeys { it.startsWith("game:") }.forEach { (key, ids) ->
            (ids as? Set<*>)?.let { edit.putStringSet(key, it.filterIsInstance<String>().toSet() - id) }
        }
        edit.apply()
    }

    fun of(context: Context, game: File): Set<String> = prefs(context).getStringSet(gameKey(game), null).orEmpty()

    fun set(context: Context, game: File, ids: Set<String>) = prefs(context).edit().putStringSet(gameKey(game), ids).apply()
}
