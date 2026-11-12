package com.retra.emulator

import android.content.Context
import com.swordfish.libretrodroid.Variable

data class Choice(val value: String, val label: String)

/** One setting. For core settings [key] is the libretro core option key, so values pass straight to the core. */
data class Setting(val key: String, val title: String, val summary: String, val choices: List<Choice>, val default: String)

object Settings {
    val SCREEN_FILTER = Setting(
        "screen_filter", "Screen filter", "How the game image is scaled to your screen",
        listOf(Choice("0", "Sharp"), Choice("1", "Smooth"), Choice("2", "LCD"), Choice("3", "CRT")), "0",
    )

    // values verified against the bundled mGBA core's option strings
    val MGBA = listOf(
        Setting(
            "mgba_color_correction", "Color correction", "Match the colours of the original handheld screen",
            listOf(Choice("OFF", "Off"), Choice("GBA", "GBA screen"), Choice("GBC", "GBC screen"), Choice("Auto", "Auto")), "Auto",
        ),
        Setting(
            "mgba_interframe_blending", "Frame blending", "Fixes flicker-based transparency some games use",
            listOf(
                Choice("OFF", "Off"), Choice("mix", "Simple"), Choice("mix_smart", "Smart"),
                Choice("lcd_ghosting", "LCD ghosting (accurate)"), Choice("lcd_ghosting_fast", "LCD ghosting (fast)"),
            ),
            "mix_smart",
        ),
        Setting(
            "mgba_idle_optimization", "Idle loop removal", "Skips games' busy-wait loops to save CPU and battery",
            listOf(Choice("Remove Known", "Remove known"), Choice("Detect and Remove", "Detect and remove"), Choice("Don't Remove", "Off")),
            "Remove Known",
        ),
        Setting(
            "mgba_frameskip", "Frame skip", "Skip drawing frames when the phone can't keep up",
            listOf(Choice("disabled", "Off"), Choice("auto", "Auto"), Choice("auto_threshold", "Auto (threshold)"), Choice("fixed_interval", "Fixed interval")),
            "disabled",
        ),
    )

    // values from mkxp-z's src/core-options.h at the pinned commit
    val MKXPZ = listOf(
        Setting(
            "mkxp-z_frameSkip", "Frame skip", "Skip drawing frames when the game falls behind",
            listOf(Choice("disabled", "Off"), Choice("enabled", "On")), "disabled",
        ),
        Setting(
            "mkxp-z_threadedAudio", "Threaded audio", "Render audio on its own thread to reduce crackling",
            listOf(Choice("enabled", "On"), Choice("disabled", "Off")), "enabled",
        ),
        Setting(
            "mkxp-z_fontScale", "Font scale", "Make in-game text smaller or larger",
            listOf("0.75", "0.875", "1.0", "1.125", "1.25").map { Choice(it, it.removeSuffix(".0") + "×") }, "1.0",
        ),
        Setting(
            "mkxp-z_subImageFix", "Subimage fix", "Try this if text or the map doesn't show up",
            listOf(Choice("default", "Default"), Choice("enabled", "On"), Choice("disabled", "Off")), "default",
        ),
        Setting(
            "mkxp-z_textureSync", "Texture sync", "Eager is safer for graphics glitches, lazy is faster",
            listOf(Choice("default", "Default"), Choice("eager", "Eager"), Choice("lazy", "Lazy")), "default",
        ),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The stored value, falling back to the default if it's missing or no longer a valid choice. */
    fun get(context: Context, setting: Setting): String =
        prefs(context).getString(setting.key, null)?.takeIf { v -> setting.choices.any { it.value == v } } ?: setting.default

    fun set(context: Context, setting: Setting, value: String) = prefs(context).edit().putString(setting.key, value).apply()

    fun label(context: Context, setting: Setting): String = get(context, setting).let { v -> setting.choices.first { it.value == v }.label }

    /** Core options handed to the core when a game starts. */
    fun coreVariables(context: Context, core: Core): Array<Variable> = when (core) {
        Core.MGBA -> MGBA.map { Variable(it.key, get(context, it)) }
        // mkxp-z states are fixed-size buffers (default 100 MiB); 64 is its minimum and plenty for XP games
        Core.MKXPZ -> MKXPZ.map { Variable(it.key, get(context, it)) } + Variable("mkxp-z_saveStateSize", "64")
    }.toTypedArray()
}
