package com.retra.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class MoreTest {
    @Test
    fun formatsPlaytime() {
        assertEquals("<1m", formatPlaytime(59_000))
        assertEquals("1m", formatPlaytime(60_000))
        assertEquals("59m", formatPlaytime(59 * 60_000L + 59_000))
        assertEquals("1h 00m", formatPlaytime(60 * 60_000L))
        assertEquals("12h 05m", formatPlaytime((12 * 60 + 5) * 60_000L))
    }

    @Test
    fun categoriesRoundTrip() {
        val list = listOf(Category("a", "Favorites"), Category("b", "Pokémon \"hacks\""))
        assertEquals(list, decodeCategories(encodeCategories(list)))
        assertEquals(emptyList<Category>(), decodeCategories(null))
        assertEquals(emptyList<Category>(), decodeCategories("not json"))
    }

    @Test
    fun settingsDefaultsAreValidChoices() {
        for (s in Settings.MGBA + Settings.MKXPZ + Settings.SCREEN_FILTER) {
            assert(s.choices.any { it.value == s.default }) { "${s.key} default ${s.default} isn't a choice" }
        }
    }
}
