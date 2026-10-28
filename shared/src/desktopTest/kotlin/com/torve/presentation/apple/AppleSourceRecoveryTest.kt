package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.addon.*
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.detail.DetailUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class AppleSourceRecoveryTest {
    private val original = ParsedStream("Fixture", "1080p", "Episode.1080p.H264.A", directUrl = "https://fixture.example/original.mkv")
    private val second = original.copy(title = "Episode.1080p.H264.B", directUrl = "https://fixture.example/second.mkv")
    private val third = original.copy(title = "Episode.1080p.H264.C", directUrl = "https://fixture.example/third.mkv")
    private suspend fun fixture(prefs: StreamPreferences = StreamPreferences(), fail: Boolean = false, switchProfile: Boolean = false,
                                check: suspend (AppleSourceRecovery, MutableList<ParsedStream>) -> Unit) {
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val progress = Proxy.newProxyInstance(WatchProgressRepository::class.java.classLoader,
                arrayOf(WatchProgressRepository::class.java)) { _, _, _ -> Unit } as WatchProgressRepository
            val sessions = ApplePlaybackSessions(device.ids, progress)
            val token = assertNotNull(sessions.begin(DetailUiState(mediaItem = MediaItem("42", type = MediaType.MOVIE, title = "Movie"),
                streams = listOf(original, second, second, third), resolvedStream = ResolvedStream(original.directUrl!!))))
            val attempted = mutableListOf<ParsedStream>()
            val streams = Proxy.newProxyInstance(StreamRepository::class.java.classLoader, arrayOf(StreamRepository::class.java)) { _, method, args ->
                when (method.name) {
                    "resolveStreamWithFallback" -> {
                        val candidate = args[0] as ParsedStream; attempted += candidate
                        if (switchProfile) device.select("other")
                        if (fail) throw IllegalStateException("fixture unavailable")
                        ResolvedStream(candidate.directUrl!!)
                    }
                    "probeStreamReadiness" -> StreamReadiness.Ready(args[0] as String)
                    else -> Unit
                }
            } as StreamRepository
            val continuation = SourceContinuationSession().apply {
                stageResolvedSource(original, original.directUrl!!, origin = ContinuationSelectionOrigin.MANUAL)
            }
            check(AppleSourceRecovery(device.ids, sessions, token, streams, { prefs }, { emptyMap() }, continuation), attempted)
        }
    }
    @Test fun askPolicyRequiresUserChoiceAndEachFallbackIsAttemptedOnlyOnce() = runTest {
        fixture { recovery, attempted ->
            assertNull(recovery.recover(original.directUrl!!, 600_000, automatic = true))
            assertTrue(attempted.isEmpty())
            val first = assertNotNull(recovery.recover(original.directUrl!!, 600_000, automatic = false))
            assertNotNull(recovery.recover(first.url, 600_000, automatic = false))
            assertNull(recovery.recover(first.url, 600_000, automatic = false))
            assertEquals(2, attempted.size)
            assertEquals(setOf(second, third), attempted.toSet())
        }
    }
    @Test fun configuredRetryLimitBoundsUnavailableSources() = runTest {
        fixture(StreamPreferences(sourceFallbackPolicy = SourceFallbackPolicy.BEST_AVAILABLE, maxFallbackAttempts = 1), fail = true) { recovery, attempted ->
            assertNull(recovery.recover(original.directUrl!!, 600_000, automatic = true))
            assertFalse(recovery.available())
            assertEquals(1, attempted.size)
        }
    }
    @Test fun profileSwitchDuringResolutionCannotHandThePreviousProfilesSourceToThePlayer() = runTest {
        fixture(switchProfile = true) { recovery, attempted ->
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                recovery.recover(original.directUrl!!, 600_000, automatic = false)
            }
            assertFalse(recovery.available())
            assertEquals(1, attempted.size)
        }
    }
}
