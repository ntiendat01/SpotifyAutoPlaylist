package com.spotifyautoplaylist.spike

import com.spotifyautoplaylist.data.auth.SpotifyAuthConfigRepository
import com.spotifyautoplaylist.onboarding.OnboardingStage
import com.spotifyautoplaylist.onboarding.OnboardingViewModel
import com.spotifyautoplaylist.onboarding.RuntimeAuthIds
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** TASK-061 app-layer: DataStore seed/override + onboarding setup UI when IDs are missing. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeAuthIdsTest {
    private val dispatcher = StandardTestDispatcher()
    private val dir = Files.createTempDirectory("runtime-auth").toFile()
    private val clientA = "0123456789abcdef0123456789abcdef"
    private val clientB = "fedcba9876543210fedcba9876543210"
    private val ownerA = "owner-a"
    private val ownerB = "owner-b"

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    @Test fun `repository seeds from build then accepts runtime override`() = runBlocking<Unit> {
        val repo = SpotifyAuthConfigRepository.create(File(dir, "cfg.preferences_pb"))
        assertNull(repo.ensureSeeded("", ""))
        val seeded = repo.ensureSeeded(clientA, ownerA)!!
        assertEquals(clientA, seeded.clientId)
        val overridden = repo.save(clientB, ownerB)
        assertEquals(clientB, overridden.clientId)
        assertEquals(ownerB, overridden.expectedOwnerId)
        assertEquals(clientB, repo.ensureSeeded(clientA, ownerA)!!.clientId)
    }

    @Test fun `missing config shows setup UI until ids are saved`() = runTest(dispatcher) {
        var configured = false
        var saved: Pair<String, String>? = null
        val gate = object : RuntimeAuthIds {
            override fun isConfigured() = configured
            override fun maskedIds() = null
            override suspend fun save(clientId: String, ownerUserId: String, confirmed: Boolean) {
                saved = clientId to ownerUserId
                configured = true
            }
        }
        val model = OnboardingViewModel(auth = null, setup = null, authIds = gate)
        runCurrent()
        assertFalse(model.state.value.configured)
        assertEquals(OnboardingStage.WELCOME, model.state.value.stage)
        model.connect(); runCurrent()
        assertFalse(model.state.value.busy)
        model.saveAuthIds(clientA, ownerA, confirmed = false); runCurrent()
        assertEquals(clientA to ownerA, saved)
        assertTrue(model.state.value.configured)
    }
}
