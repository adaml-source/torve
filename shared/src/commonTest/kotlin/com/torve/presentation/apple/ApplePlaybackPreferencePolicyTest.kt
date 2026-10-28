package com.torve.presentation.apple

import com.torve.domain.model.MediaType
import com.torve.domain.player.PlaybackTrackSelection
import com.torve.domain.player.TrackDescription
import kotlin.test.*

class ApplePlaybackPreferencePolicyTest {
    @Test fun existingAndroidPreferencesRestoreWithoutChangingTrackPriority() {
        val prefs = ApplePlaybackPreferencePolicy.read("150|1.5|RATIO_16_9", "de|en|1")
        assertEquals(150, prefs.audioDelayMs)
        assertEquals(1.5, prefs.speed)
        assertEquals("RATIO_16_9", prefs.picture)
        val tracks = listOf(TrackDescription(7, "Deutsch", "deu"), TrackDescription(9, "English", "eng"))
        assertEquals(7, PlaybackTrackSelection.audioId(tracks, prefs.audioTag, "en"))
        assertEquals(9, PlaybackTrackSelection.subtitleId(tracks, prefs.subtitleTag, "de", "de"))
        assertFalse(ApplePlaybackPreferencePolicy.read(null, "||0").subtitlesEnabled)
    }
    @Test fun seriesEpisodesUseTheSameAndroidShowKeyWhileOtherTitlesAreIndependent() {
        val first = ApplePlaybackIdentity("42", "Series", "tt42", MediaType.SERIES, 1, 1)
        assertEquals(ApplePlaybackPreferencePolicy.key(first), ApplePlaybackPreferencePolicy.key(first.copy(episode = 2)))
        assertNotEquals(ApplePlaybackPreferencePolicy.key(first), ApplePlaybackPreferencePolicy.key(first.copy(mediaId = "43")))
        assertEquals("player_playback_prefs_${"tv:media:42".hashCode().toUInt().toString(16)}", ApplePlaybackPreferencePolicy.key(first))
    }
    @Test fun corruptValuesCannotCreateAnInvalidSpeedOrPicture() {
        val prefs = ApplePlaybackPreferencePolicy.read("9000|NaN|STRETCH", null)
        assertEquals(2000, prefs.audioDelayMs)
        assertNull(prefs.speed)
        assertEquals("SOURCE", prefs.picture)
        assertFalse(ApplePlaybackPreferencePolicy.validKey("player_playback_prefs_https://signed.example"))
    }
    @Test fun missingRememberedSubtitleFallsBackToPreferredLanguageBeforeAudioFallback() {
        val tracks = listOf(TrackDescription(1, "English", "eng"), TrackDescription(2, "German", "deu"))
        assertEquals(1, PlaybackTrackSelection.subtitleId(tracks, "missing", "en-US", "de"))
        assertEquals(2, PlaybackTrackSelection.subtitleId(tracks, null, "fr", "de"))
        assertNull(PlaybackTrackSelection.audioId(tracks, "missing", "en"))
    }
}
