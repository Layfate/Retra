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
import kotlin.math.roundToInt

/**
 * Owns all on-screen controller feedback.
 *
 * Input controllers report a press and move on immediately; this class applies
 * optional sound + haptics afterward. Keeping feedback outside touch handling
 * prevents audio/haptic policy from leaking into pointer ownership and lets
 * rapid multi-touch be rate-limited consistently in one place.
 *
 * Haptics belong to the D-pad only: a tick when a direction is first pressed
 * and one each time the thumb slides into another direction, so direction
 * changes can be felt without looking. Buttons (A/B, L/R, Start/Select, menu
 * and extra actions) only click.
 *
 * Ticks drive the vibrator directly with one short pulse whose strength the
 * player sets. The view haptic constants (CLOCK_TICK / SEGMENT_FREQUENT_TICK)
 * are the faintest effects Android has: many phones (Xiaomi/HyperOS among
 * them) play them too weakly to feel or not at all, and they are muted whenever
 * the system "Touch feedback" setting is off. Retra's own Vibration switch
 * decides instead.
 *
 * The click plays from a bundled sample on the game audio stream (media
 * volume). The system key-click it replaces is silent when the phone's
 * "Touch sounds" setting is off (the default on many phones) or the ringer is
 * on silent.
 */
internal class ControllerFeedbackManager(
    context: Context,
    private val soundEnabled: () -> Boolean,
    private val hapticsEnabled: () -> Boolean,
    /** Vibration strength, 0..100 (see [HAPTIC_STRENGTH_DEFAULT]). */
    private val hapticStrength: () -> Int
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
    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true

    // The tick is built once per strength and reused, so a direction change
    // only costs the vibrate() call itself.
    private var cachedStrength = -1
    private var cachedTick: VibrationEffect? = null

    private fun tickEffect(requestedStrength: Int): VibrationEffect? {
        val strength = requestedStrength.coerceIn(HAPTIC_STRENGTH_MIN, HAPTIC_STRENGTH_MAX)
        if (strength != cachedStrength) {
            cachedStrength = strength
            val amplitude = if (amplitudeControl) tickAmplitude(strength) else VibrationEffect.DEFAULT_AMPLITUDE
            cachedTick = runCatching {
                VibrationEffect.createOneShot(tickDurationMs(strength, amplitudeControl), amplitude)
            }.getOrNull()
        }
        return cachedTick
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
    private var lastDpadHapticAtMs = 0L
    private var lastPreviewHapticAtMs = 0L

    /** Buttons: the click sound only. Vibration is reserved for the D-pad. */
    fun perform(@Suppress("UNUSED_PARAMETER") view: View) {
        click(SystemClock.uptimeMillis())
    }

    /**
     * D-pad feedback: one tick when a direction is first pressed and one per
     * change of direction while the thumb stays down (for example Right ->
     * Up-Right -> Up). Nothing repeats while a direction is held, and
     * releasing is silent.
     *
     * The caller reports each mask change once (held MOVE events return before
     * reaching here), and [DPAD_HAPTIC_GAP_MS] turns a fast thumb roll through
     * a diagonal into a few crisp ticks instead of a buzz.
     *
     * Sound and vibration are one event: both pass the same gate, so every
     * tick you feel is a click you hear and vice versa. The click is queued
     * first because the audio path has more output latency than the motor,
     * which lands the two together instead of buzz-then-click.
     */
    fun performDpad(view: View, previousMask: Int, nextMask: Int) {
        if (nextMask == 0 || nextMask == previousMask) return
        val now = SystemClock.uptimeMillis()
        if (!dpadTickDue(previousMask, nextMask, now, lastDpadHapticAtMs)) return
        lastDpadHapticAtMs = now
        click(now, ignoreGap = true)
        if (hapticsEnabled()) tick(view, hapticStrength())
    }

    /**
     * A sample D-pad tick at [strength] while the strength slider moves, so the
     * setting can be felt before it is saved. Call on the main thread.
     */
    fun previewHaptic(view: View, strength: Int) {
        val now = SystemClock.uptimeMillis()
        if (!hapticsEnabled() || now - lastPreviewHapticAtMs < PREVIEW_GAP_MS) return
        lastPreviewHapticAtMs = now
        tick(view, strength)
    }

    private fun tick(view: View, strength: Int) {
        val effect = tickEffect(strength)
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

    private fun click(now: Long, ignoreGap: Boolean = false) {
        if (!soundEnabled() || !clickLoaded) return
        if (!ignoreGap && now - lastSoundAtMs < SOUND_GAP_MS) return
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
        internal const val DPAD_HAPTIC_GAP_MS = 40L
        private const val PREVIEW_GAP_MS = 110L

        /** Vibration strength setting range and default (percent). */
        internal const val HAPTIC_STRENGTH_MIN = 10
        internal const val HAPTIC_STRENGTH_MAX = 100
        internal const val HAPTIC_STRENGTH_DEFAULT = 55

        /** Amplitude (of 255) for a strength: 70 at 10%, the full motor at 100%. */
        internal fun tickAmplitude(strength: Int): Int {
            val s = strength.coerceIn(HAPTIC_STRENGTH_MIN, HAPTIC_STRENGTH_MAX)
            val t = (s - HAPTIC_STRENGTH_MIN) / (HAPTIC_STRENGTH_MAX - HAPTIC_STRENGTH_MIN).toFloat()
            return (70 + t * (255 - 70)).roundToInt().coerceIn(1, 255)
        }

        /**
         * Pulse length. With amplitude control the tick stays short and crisp
         * (12-20 ms) and strength mostly comes from amplitude. Motors without it
         * only run at full power, so length is the strength: 8-22 ms.
         */
        internal fun tickDurationMs(strength: Int, amplitudeControl: Boolean): Long {
            val s = strength.coerceIn(HAPTIC_STRENGTH_MIN, HAPTIC_STRENGTH_MAX)
            val t = (s - HAPTIC_STRENGTH_MIN) / (HAPTIC_STRENGTH_MAX - HAPTIC_STRENGTH_MIN).toFloat()
            return if (amplitudeControl) (12 + t * 8).roundToInt().toLong() else (8 + t * 14).roundToInt().toLong()
        }

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
