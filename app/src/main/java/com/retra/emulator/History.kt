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
