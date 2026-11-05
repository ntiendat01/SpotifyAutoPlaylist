package com.spotifyautoplaylist.auth

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FileSmokeJournalTest {
    @Test fun `atomic journal restores write intent after reopening and never stores credentials`() {
        val directory = Files.createTempDirectory("replay-smoke-journal").toFile()
        try {
            val scope = "a".repeat(64)
            val store = FileSmokeJournal(directory)
            val original = store.load(scope)
            val pending = original.copy(playlistId = "P".repeat(22), creationAttempted = true,
                trackId = "T".repeat(22), writeState = SmokeWriteState.VERIFY_REMOTE)
            store.save(scope, pending)
            assertEquals(pending, FileSmokeJournal(directory).load(scope))
            val json = directory.resolve("$scope.json").readText()
            for (sensitive in listOf("access_token", "refresh_token", "dummy-owner", "dummy-access-token", "played_at")) {
                assertFalse(json.contains(sensitive))
            }
            assertFalse(pending.toString().contains(pending.trackId!!))
            assertFalse(directory.resolve("$scope.tmp").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `new scope gets independent marker and does not reuse another account playlist`() {
        val directory = Files.createTempDirectory("replay-smoke-scope").toFile()
        try {
            val store = FileSmokeJournal(directory)
            val first = store.load("a".repeat(64))
            store.save("a".repeat(64), first.copy(playlistId = "P".repeat(22)))
            val other = store.load("b".repeat(64))
            assertNotEquals(first.marker, other.marker)
            assertNull(other.playlistId)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corruption unsupported schema and orphaned temporary file fail closed without reset`() {
        val directory = Files.createTempDirectory("replay-smoke-corrupt").toFile()
        try {
            val scope = "a".repeat(64)
            val store = FileSmokeJournal(directory)
            for (contents in listOf("not-json", """{"version":99}""", "x".repeat(16_385))) {
                directory.resolve("$scope.json").writeText(contents)
                val error = assertThrows(ProbeException::class.java) { store.load(scope) }
                assertEquals(ProbeFailure.JOURNAL, error.reason)
                assertNull(error.cause)
                assertEquals(contents, directory.resolve("$scope.json").readText())
            }
            directory.resolve("$scope.json").delete()
            directory.resolve("$scope.tmp").writeText("interrupted-write")
            assertEquals(ProbeFailure.JOURNAL, assertThrows(ProbeException::class.java) { store.load(scope) }.reason)
            assertFalse(directory.resolve("$scope.json").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `invalid filename or inconsistent record cannot be persisted`() {
        val directory = Files.createTempDirectory("replay-smoke-invalid").toFile()
        try {
            val store = FileSmokeJournal(directory)
            assertEquals(ProbeFailure.JOURNAL, assertThrows(ProbeException::class.java) { store.load("../unsafe") }.reason)
            val record = store.load("a".repeat(64))
            assertEquals(ProbeFailure.JOURNAL, assertThrows(ProbeException::class.java) {
                store.save("a".repeat(64), record.copy(writeState = SmokeWriteState.PRESENT))
            }.reason)
        } finally { directory.deleteRecursively() }
    }
}
