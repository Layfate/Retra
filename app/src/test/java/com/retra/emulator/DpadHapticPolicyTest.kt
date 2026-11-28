package com.retra.emulator

import com.retra.emulator.ControllerFeedbackManager.Companion.DPAD_HAPTIC_GAP_MS
import com.retra.emulator.ControllerFeedbackManager.Companion.dpadTickDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadHapticPolicyTest {
    private val up = 1 shl 6
    private val right = 1 shl 4
    private val upRight = up or right

    @Test
    fun pressTicks() {
        assertTrue(dpadTickDue(0, up, nowMs = 1_000, lastTickMs = 0))
    }

    @Test
    fun eachDirectionChangeTicks() {
        assertTrue(dpadTickDue(right, upRight, nowMs = 1_000, lastTickMs = 900))
        assertTrue(dpadTickDue(upRight, up, nowMs = 1_100, lastTickMs = 1_000))
    }

    @Test
    fun holdingAndReleasingNeverTick() {
        assertFalse("held mask repeats", dpadTickDue(up, up, nowMs = 5_000, lastTickMs = 0))
        assertFalse("release", dpadTickDue(up, 0, nowMs = 5_000, lastTickMs = 0))
    }

    @Test
    fun fastThumbRollIsRateLimitedInsteadOfBuzzing() {
        assertFalse(dpadTickDue(right, upRight, nowMs = 1_000 + DPAD_HAPTIC_GAP_MS - 1, lastTickMs = 1_000))
        assertTrue(dpadTickDue(right, upRight, nowMs = 1_000 + DPAD_HAPTIC_GAP_MS, lastTickMs = 1_000))
    }

    @Test
    fun simulatedGestureTicksOncePerDirection() {
        // Press Right, hold for many MOVE events, roll to Up-Right then Up, hold, release.
        val masks = listOf(right, right, right, upRight, upRight, up, up, up, 0)
        var previous = 0
        var last = Long.MIN_VALUE / 2
        var ticks = 0
        masks.forEachIndexed { index, mask ->
            val now = 1_000L + index * 50L
            if (mask != previous && dpadTickDue(previous, mask, now, last)) { ticks++; last = now }
            previous = mask
        }
        assertEquals(3, ticks)
    }

    @Test
    fun strengthScalesAmplitudeWithinMotorRange() {
        assertEquals(70, ControllerFeedbackManager.tickAmplitude(ControllerFeedbackManager.HAPTIC_STRENGTH_MIN))
        assertEquals(255, ControllerFeedbackManager.tickAmplitude(ControllerFeedbackManager.HAPTIC_STRENGTH_MAX))
        assertEquals(70, ControllerFeedbackManager.tickAmplitude(0))
        assertEquals(255, ControllerFeedbackManager.tickAmplitude(500))
        assertTrue(ControllerFeedbackManager.tickAmplitude(30) < ControllerFeedbackManager.tickAmplitude(70))
    }

    @Test
    fun ticksStayShortAndCrisp() {
        for (strength in listOf(10, 55, 100)) {
            assertTrue(ControllerFeedbackManager.tickDurationMs(strength, amplitudeControl = true) in 12L..20L)
            assertTrue(ControllerFeedbackManager.tickDurationMs(strength, amplitudeControl = false) in 8L..22L)
        }
        // Without amplitude control, length carries the strength.
        assertTrue(
            ControllerFeedbackManager.tickDurationMs(20, amplitudeControl = false) <
                ControllerFeedbackManager.tickDurationMs(90, amplitudeControl = false)
        )
    }
}
