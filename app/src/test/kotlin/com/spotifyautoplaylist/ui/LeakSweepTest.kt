package com.spotifyautoplaylist.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-050H: example content from the eight concept images must never reach production sources. */
class LeakSweepTest {
    private val banned = listOf(
        "Sunset Drive", "ODETARI", "otetari", "Blinding Lights", "Weeknd", "Something About You", "Starfall", "Illenium",
        "Nightcall", "Kavinsky", "Ocean Eyes", "Billie Eilish", "Midnight Horizon", "ODESZA", "Lose Control", "Teddy Swims",
        "142 tracks", "+18%", "+12", "+100%", "8 min ago", "2 min 14", "Jan 26", "Jan 25", "Tomorrow, 10:00",
        "18 tracks checked", "24 tracks", "new tracks added", "Healthy",
    ).map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) } + Regex("\\bLANY\\b")

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists() }

    @Test fun `no concept example data in production sources`() {
        val r = root()
        val files = listOf("app/src/main", "data/src/main", "spotify-api/src/main").map { File(r, it) }.filter { it.exists() }
            .flatMap { d -> d.walk().filter { it.isFile && it.extension in setOf("kt", "xml", "json") }.toList() }
        assertTrue("sweep found no sources", files.size > 20)
        val hits = files.flatMap { f -> f.readLines().withIndex().flatMap { (i, line) -> banned.filter { it.containsMatchIn(line) }.map { "${f.relativeTo(r)}:${i + 1} ${it.pattern}" } } }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}
