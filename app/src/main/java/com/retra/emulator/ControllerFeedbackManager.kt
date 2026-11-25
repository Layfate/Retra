package com.retra.emulator

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Owns all on-screen controller feedback.
 *
 * Input controllers report a press and move on immediately; this class applies
 * optional sound + haptics afterward. Keeping feedback outside touch handling
 * prevents audio/haptic policy from leaking into pointer ownership and lets
 * rapid multi-touch be rate-limited consistently in one place.
 *
 * Haptics drive the vibrator directly with one short, light pulse. The view
 * haptic constants used before (CLOCK_TICK / SEGMENT_FREQUENT_TICK) are the
 * faintest effects Android has: many phones (Xiaomi/HyperOS among them) play
 * them too weakly to feel or not at all, and they are muted whenever the
 * system "Touch feedback" setting is off. Retra's own Controller haptics
 * switch decides instead.
 *
 * The click plays from a bundled sample on the game audio stream (media
 * volume). The system key-click it replaces is silent when the phone's
 * "Touch sounds" setting is off (the default on many phones) or the ringer is
 * on silent.
 */
internal class ControllerFeedbackManager(
    context: Context,
    private val soundEnabled: () -> Boolean,
    private val hapticsEnabled: () -> Boolean
) {
    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** One short pulse at a fixed, light strength: a crisp tick on every motor. */
    private val tickEffect: VibrationEffect? = vibrator?.let {
        val amplitude = if (it.hasAmplitudeControl()) TICK_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
        runCatching { VibrationEffect.createOneShot(TICK_MS, amplitude) }.getOrNull()
    }

    // Game/media usage: controller haptics follow Retra's switch, not the
    // touch-feedback or ringer settings that mute UI ticks.
    private val vibrationAttributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA) else null
    private val legacyVibrationAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(legacyVibrationAudioAttributes)
        .build()
    @Volatile private var clickLoaded = false
    private val clickSoundId: Int = runCatching {
        soundPool.setOnLoadCompleteListener { _, _, status -> clickLoaded = status == 0 }
        soundPool.load(appContext, R.raw.controller_click, 1)
    }.getOrDefault(0)

    private var lastSoundAtMs = 0L
    private var lastHapticAtMs = 0L
    private var lastDpadHapticAtMs = 0L

    /** One light, consistent profile for every controller button. */
    fun perform(view: View) {
        val now = SystemClock.uptimeMillis()

        if (hapticsEnabled() && now - lastHapticAtMs >= HAPTIC_GAP_MS) {
            lastHapticAtMs = now
            tick(view)
        }
        click(now)
    }

    /**
     * D-pad feedback: one light tick when a direction is first pressed and one
     * per change of direction (for example Right -> Up-Right -> Up). Nothing
     * repeats while a direction is held, and releasing is silent.
     *
     * The D-pad keeps its own rate limit so a tick for A/B never swallows a
     * direction change, and a fast thumb roll through a diagonal produces a
     * few crisp ticks instead of a buzz. Sound plays only on the first press.
     */
    fun performDpad(view: View, previousMask: Int, nextMask: Int) {
        if (nextMask == 0 || nextMask == previousMask) return
        val now = SystemClock.uptimeMillis()
        if (hapticsEnabled() && dpadTickDue(previousMask, nextMask, now, lastDpadHapticAtMs)) {
            lastDpadHapticAtMs = now
            tick(view)
        }
        if (previousMask == 0) click(now)
    }

    private fun tick(view: View) {
        val effect = tickEffect
        val motor = vibrator
        if (motor != null && effect != null) {
            val played = runCatching {
                if (Build.VERSION.SDK_INT >= 33 && vibrationAttributes != null) {
                    motor.vibrate(effect, vibrationAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    motor.vibrate(effect, legacyVibrationAudioAttributes)
                }
            }.isSuccess
            if (played) return
        }
        // No usable vibrator service: fall back to the view's system haptic.
        val feedback = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        runCatching { view.performHapticFeedback(feedback) }
    }

    private fun click(now: Long) {
        if (!soundEnabled() || !clickLoaded || now - lastSoundAtMs < SOUND_GAP_MS) return
        lastSoundAtMs = now
        runCatching { soundPool.play(clickSoundId, CLICK_VOLUME, CLICK_VOLUME, 1, 0, 1f) }
    }

    /** Called when controls are bound; construction loads the click sample. */
    fun prepare() = Unit

    fun release() {
        runCatching { vibrator?.cancel() }
        runCatching { soundPool.release() }
    }

    companion object {
        private const val HAPTIC_GAP_MS = 26L
        internal const val DPAD_HAPTIC_GAP_MS = 40L

        /** Pulse length and strength (of 255): short and light, but felt on LRA and ERM motors. */
        internal const val TICK_MS = 14L
        internal const val TICK_AMPLITUDE = 140

        /**
         * Whether a D-pad mask change earns a haptic tick: a press or a change
         * to another direction (never a release or a repeat of the held mask),
         * at most one per [DPAD_HAPTIC_GAP_MS].
         */
        internal fun dpadTickDue(previousMask: Int, nextMask: Int, nowMs: Long, lastTickMs: Long): Boolean =
            nextMask != 0 && nextMask != previousMask && nowMs - lastTickMs >= DPAD_HAPTIC_GAP_MS

        private const val SOUND_GAP_MS = 28L
        /** Relative to media volume; the sample itself is a soft 18 ms tick. */
        private const val CLICK_VOLUME = 0.45f
    }
}
