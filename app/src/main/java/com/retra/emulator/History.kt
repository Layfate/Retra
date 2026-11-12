package com.retra.emulator

import android.content.Context
import java.io.File

/** When each game was last played, keyed by its library path. */
object History {
    private fun prefs(context: Context) = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun record(context: Context, game: File) =
        prefs(context).edit().putLong(game.absolutePath, System.currentTimeMillis()).apply()

    /** Most recent first; games that were removed from the library drop out. */
    fun entries(context: Context): List<Pair<File, Long>> =
        prefs(context).all.mapNotNull { (path, time) -> (time as? Long)?.let { File(path) to it } }
            .filter { it.first.exists() }
            .sortedByDescending { it.second }

    fun remove(context: Context, game: File) = prefs(context).edit().remove(game.absolutePath).apply()

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}

/** Total time spent in each game (ms, counted while the game screen is in the foreground). */
object Playtime {
    private fun prefs(context: Context) = context.getSharedPreferences("playtime", Context.MODE_PRIVATE)

    fun add(context: Context, game: File, ms: Long) {
        if (ms <= 0) return
        val p = prefs(context)
        p.edit().putLong(game.absolutePath, p.getLong(game.absolutePath, 0) + ms).apply()
    }

    /** Most played first; games that were removed from the library drop out. */
    fun all(context: Context): List<Pair<File, Long>> =
        prefs(context).all.mapNotNull { (path, ms) -> (ms as? Long)?.let { File(path) to it } }
            .filter { it.first.exists() && it.second > 0 }
            .sortedByDescending { it.second }
}

/** "2h 05m", "12m", "<1m". */
fun formatPlaytime(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        else -> "%dh %02dm".format(minutes / 60, minutes % 60)
    }
}
