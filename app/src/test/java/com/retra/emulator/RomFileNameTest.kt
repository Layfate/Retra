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
    fun resolvesPickerDocumentIds() {
        val primary = java.io.File("/storage/emulated/0")
        assertEquals(java.io.File("/storage/emulated/0/Games/My RPG/Game.exe"), documentIdToFile("primary:Games/My RPG/Game.exe", primary))
        assertEquals(java.io.File("/storage/1A2B-3C4D/RPG/Game.exe"), documentIdToFile("1A2B-3C4D:RPG/Game.exe", primary))
        assertEquals(java.io.File("/storage/emulated/0/Download/Game.exe"), documentIdToFile("raw:/storage/emulated/0/Download/Game.exe", primary))
        assertNull(documentIdToFile("12345", primary))
    }

    @Test
    fun followsGameLinks() {
        val dir = Files.createTempDirectory("link").toFile()
        try {
            val game = dir.resolve("Far Away RPG").apply { mkdirs(); resolve("Game.ini").writeText("[Game]") }
            val link = dir.resolve("Far Away RPG.$LINK_EXTENSION").apply { writeText(game.absolutePath) }
            assertEquals(Core.MKXPZ, coreFor(link))
            assertEquals(game.resolve("Game.ini"), contentPath(link))
            game.deleteRecursively()
            assertNull(contentPath(link)) // folder moved or deleted
        } finally {
            dir.deleteRecursively()
        }
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
