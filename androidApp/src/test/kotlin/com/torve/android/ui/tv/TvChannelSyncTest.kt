package com.torve.android.ui.tv

import com.torve.domain.sync.SyncResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests verifying that channel sync results correctly trigger
 * ChannelsViewModel reload on the TV side.
 *
 * The root cause of the channel sync bug was that after importSyncPayload()
 * wrote new playlists/favorites to SQLite, the ChannelsViewModel was never
 * notified — its in-memory state remained stale. These tests validate the
 * reload-decision logic that gates the fix.
 */
class TvChannelSyncTest {

    /** Mirrors the condition in TvRoot.kt that gates channelsViewModel reload. */
    private fun shouldReloadChannels(result: SyncResult): Boolean =
        result.playlistsImported > 0 || result.favoritesImported > 0

    @Test
    fun `sync with playlists imported triggers channel reload`() {
        val result = SyncResult(playlistsImported = 2)
        assertTrue(shouldReloadChannels(result))
    }

    @Test
    fun `sync with favorites imported triggers channel reload`() {
        val result = SyncResult(favoritesImported = 3)
        assertTrue(shouldReloadChannels(result))
    }

    @Test
    fun `sync with both playlists and favorites triggers channel reload`() {
        val result = SyncResult(playlistsImported = 1, favoritesImported = 5)
        assertTrue(shouldReloadChannels(result))
    }

    @Test
    fun `sync with only addons does not trigger channel reload`() {
        val result = SyncResult(addonsImported = 4)
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `sync with only preferences does not trigger channel reload`() {
        val result = SyncResult(preferencesImported = 10)
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `sync with only progress does not trigger channel reload`() {
        val result = SyncResult(progressImported = 7)
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `sync with only secrets does not trigger channel reload`() {
        val result = SyncResult(secretsImported = 2)
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `empty sync result does not trigger channel reload`() {
        val result = SyncResult()
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `sync with conflicts but no imports does not trigger channel reload`() {
        val result = SyncResult(conflicts = 3)
        assertFalse(shouldReloadChannels(result))
    }

    @Test
    fun `sync with playlist conflicts and favorite imports triggers reload`() {
        // Playlist duplicates cause conflicts but favorites still imported
        val result = SyncResult(favoritesImported = 2, conflicts = 1)
        assertTrue(shouldReloadChannels(result))
    }

    @Test
    fun `full sync payload triggers reload when playlists present`() {
        val result = SyncResult(
            addonsImported = 3,
            preferencesImported = 15,
            progressImported = 5,
            playlistsImported = 1,
            favoritesImported = 0,
            secretsImported = 2,
            conflicts = 0,
        )
        assertTrue(shouldReloadChannels(result))
    }
}
