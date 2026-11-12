package com.retra.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class RomFileNameTest {
    @Test
    fun acceptsRomsAndStripsPaths() {
        assertEquals("Pokemon Emerald.gba", romFileName("Pokemon Emerald.gba"))
        assertEquals("hack.GBA", romFileName("../../hack.GBA"))
        assertEquals("tetris.gb", romFileName("tetris.gb"))
        assertEquals("My RPG.zip", romFileName("My RPG.zip"))
        assertEquals("game.mkxpz", romFileName("game.mkxpz"))
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(romFileName(null))
        assertNull(romFileName("patch.ips"))
        assertNull(romFileName("gba"))
        assertNull(romFileName("Game.exe"))
    }

    @Test
    fun picksCoreAndContentPath() {
        val dir = Files.createTempDirectory("rpg").toFile()
        try {
            val game = dir.resolve("My RPG").apply { mkdirs() }
            assertNull(contentPath(game)) // no Game.ini yet
            game.resolve("GAME.INI").writeText("[Game]")
            assertEquals("GAME.INI", contentPath(game)?.name)
            assertEquals(Core.MKXPZ, coreFor(game))

            val rom = dir.resolve("emerald.gba").apply { writeText("") }
            assertEquals(Core.MGBA, coreFor(rom))
            assertEquals(rom, contentPath(rom))
            assertEquals(Core.MKXPZ, coreFor(dir.resolve("rpg.zip")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
