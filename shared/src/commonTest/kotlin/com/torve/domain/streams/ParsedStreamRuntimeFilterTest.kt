package com.torve.domain.streams

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ParsedStreamRuntimeFilterTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `runtime path consumes persisted regex and stream group settings`() = runTest {
        val preferences = FakePreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(
                listOf(
                    StreamGroup(name = "4K DV", matchPattern = "(?i)2160p", priority = 0),
                    StreamGroup(name = "1080p", matchPattern = "(?i)1080p", priority = 1),
                ),
            ),
        )

        val filter = ParsedStreamRuntimeFilter(
            preferencesRepository = preferences,
            subscriptionRepository = FakeSubscriptionRepository(premium = true),
            json = json,
        )
        val fullHd = stream(title = "Movie.2026.1080p.WEB-DL", score = 80)
        val cam = stream(title = "Movie.2026.HDCAM.x264", score = 80)
        val ultraHd = stream(title = "Movie.2026.2160p.WEB-DL.DV.Atmos", score = 80)

        val result = filter.apply(listOf(fullHd, cam, ultraHd))

        assertEquals(listOf(ultraHd, fullHd), result.streams)
        assertEquals(1, result.filterResult.excludedCount)
        assertEquals(listOf(ultraHd), result.filterResult.groupMatches["4K DV"])
    }

    @Test
    fun `runtime path applies persisted custom settings without premium`() = runTest {
        val preferences = FakePreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(listOf(StreamGroup(name = "4K", matchPattern = "(?i)2160p", priority = 0))),
        )
        val filter = ParsedStreamRuntimeFilter(
            preferencesRepository = preferences,
            subscriptionRepository = FakeSubscriptionRepository(premium = false),
            json = json,
        )
        val cam = stream(title = "Movie.2026.HDCAM.x264", score = 80)
        val ultraHd = stream(title = "Movie.2026.2160p.WEB-DL.DV.Atmos", score = 80)

        val result = filter.apply(listOf(cam, ultraHd))

        assertEquals(listOf(ultraHd), result.streams)
        assertEquals(1, result.filterResult.excludedCount)
        assertEquals(listOf(ultraHd), result.filterResult.groupMatches["4K"])
    }

    @Test
    fun `matching text excludes stream urls tokens hashes and memory ids`() = runTest {
        val preferences = FakePreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        val filter = ParsedStreamRuntimeFilter(
            preferencesRepository = preferences,
            subscriptionRepository = FakeSubscriptionRepository(premium = true),
            json = json,
        )
        val stream = stream(title = "Movie.2026.1080p.WEB-DL", score = 80).copy(
            directUrl = "https://cdn.example/HDCAM/token",
            magnetUrl = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000&dn=HDCAM",
            infoHash = "HDCAM_HASH_SHOULD_NOT_MATCH",
            accelerationMemoryId = "memory-HDCAM-token",
            accelerationSourceKey = "source-HDCAM-token",
        )

        val result = filter.apply(listOf(stream))

        assertEquals(listOf(stream), result.streams)
        assertEquals(0, result.filterResult.excludedCount)
    }

    private fun stream(
        title: String,
        score: Int,
        quality: String = "1080p",
    ): ParsedStream = ParsedStream(
        addonName = "Panda",
        quality = quality,
        title = title,
        source = "Real-Debrid",
        codec = "HEVC",
        hdr = "DV",
        audioCodec = "Atmos",
        isCached = true,
        score = score,
    )

    private class FakePreferencesRepository : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeSubscriptionRepository(
        private val premium: Boolean,
    ) : SubscriptionRepository {
        override suspend fun getActiveSubscription(): Subscription? = if (premium) {
            Subscription(
                id = "sub-1",
                tier = SubscriptionTier.MONTHLY,
                isActive = true,
                purchasedAt = 1L,
            )
        } else {
            null
        }

        override suspend fun isPro(): Boolean = premium
        override suspend fun hasAccess(feature: PremiumFeature): Boolean = premium
        override suspend fun hasLocallyVerifiedPremiumAccess(): Boolean = premium
        override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) = Unit
        override suspend fun ensureFreeTier() = Unit
        override suspend fun restorePurchase(purchaseToken: String): Subscription? = getActiveSubscription()
        override suspend fun refreshFromBackend(): Boolean = premium
        override suspend fun refreshFromBackendDetailed(): BackendPremiumResult =
            if (premium) BackendPremiumResult.Active else BackendPremiumResult.NoEntitlement
        override suspend fun onBackendEntitlementGranted(isPremium: Boolean) = Unit
    }
}
