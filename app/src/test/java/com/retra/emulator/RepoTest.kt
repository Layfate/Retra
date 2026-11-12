package com.retra.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoTest {
    private val index = """
        {
          "name": "Homebrew Hub",
          "games": [
            {"title": "Space Demo", "url": "roms/space%20demo.gba", "sha256": "ABC123", "description": "A homebrew shooter"},
            {"url": "https://cdn.example.org/fan-rpg.zip", "file": "Fan RPG.zip"},
            {"title": "Insecure", "url": "http://example.org/x.gba"},
            {"title": "Not a game", "url": "https://example.org/setup.exe"},
            {"title": "Duplicate", "url": "https://example.org/repo/roms/space%20demo.gba"},
            "garbage"
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAndFiltersEntries() {
        val repo = parseRepo("https://example.org/repo/index.json", index)
        assertEquals("Homebrew Hub", repo.name)
        assertEquals(2, repo.games.size) // http, .exe, duplicate and garbage entries dropped

        val space = repo.games[0]
        assertEquals("Space Demo", space.title)
        assertEquals("https://example.org/repo/roms/space%20demo.gba", space.url) // relative URL resolved
        assertEquals("space demo.gba", space.fileName)
        assertEquals("ABC123", space.sha256)

        val rpg = repo.games[1]
        assertEquals("Fan RPG.zip", rpg.title) // falls back to the file name
        assertEquals("Fan RPG.zip", rpg.fileName)
        assertNull(rpg.sha256)
    }

    @Test
    fun repoNameFallsBackToHost() {
        assertEquals("example.org", parseRepo("https://example.org/index.json", """{"games": []}""").name)
    }
}
