package com.retra.emulator

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SaveFilesTest {
    @Test fun extensionsAndPaths() {
        for (ext in SaveFiles.extensions) assertEquals(ext, SaveFiles.extension("game." + ext.uppercase()))
        val dir = File("saves")
        val rom = File("game.gba")
        assertEquals(SaveFiles.file(dir, rom, "sav"), SaveFiles.file(dir, rom, "srm"))
        assertNotEquals(SaveFiles.file(dir, rom, "srm"), SaveFiles.file(dir, File("game.gb"), "srm"))
        assertEquals(10, (0..9).map { SaveFiles.file(dir, rom, "ss$it") }.distinct().size)
        assertTrue(runCatching { SaveFiles.extension("game.ss10") }.isFailure)
    }
    @Test fun validatesData() {
        SaveFiles.validate("sav", ByteArray(131072))
        SaveFiles.validate("srm", ByteArray(8192 + 48))
        SaveFiles.validate("ss0", byteArrayOf(11, 0, 0, 1))
        SaveFiles.validate("ss9", byteArrayOf(3, 0, 64, 0))
        SaveFiles.validate("state", byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        assertTrue(runCatching { SaveFiles.validate("sav", ByteArray(5)) }.isFailure)
        assertTrue(runCatching { SaveFiles.validate("state", ByteArray(32)) }.isFailure)
        assertTrue(runCatching { SaveFiles.validate("srm", byteArrayOf()) }.isFailure)
    }
    @Test fun boundedRead() {
        assertArrayEquals(byteArrayOf(1, 2), SaveFiles.read(ByteArrayInputStream(byteArrayOf(1, 2))))
        assertTrue(runCatching { SaveFiles.read(ByteArrayInputStream(byteArrayOf())) }.isFailure)
        assertTrue(runCatching { SaveFiles.read(ByteArrayInputStream(ByteArray(SaveFiles.MAX_BYTES + 1))) }.isFailure)
    }
    @Test fun migratesAndPreservesExistingSave() {
        val dir = Files.createTempDirectory("retra-saves").toFile()
        try {
            File(dir, "game.srm").writeBytes(byteArrayOf(1))
            SaveFiles.migrate(dir, File("game.gba"))
            val target = SaveFiles.file(dir, File("game.gba"), "srm")
            assertArrayEquals(byteArrayOf(1), target.readBytes())
            target.writeAtomic(byteArrayOf(2))
            SaveFiles.migrate(dir, File("game.gba"))
            assertArrayEquals(byteArrayOf(2), target.readBytes())
            assertTrue(File(dir, "game.srm").exists())
            assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
        } finally { dir.deleteRecursively() }
    }
}
