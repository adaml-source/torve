package com.torve.domain.repository

import com.torve.domain.model.WatchProgress
import com.torve.domain.player.PlaybackViewingCoverage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface WatchProgressRepository {
    /** Emits after durable local progress changes so visible catalog rails can refresh. */
    val progressChanges: Flow<Unit>
        get() = emptyFlow()

    suspend fun getInProgress(limit: Long = 20): List<WatchProgress>
    suspend fun getProgress(mediaId: String): WatchProgress?
    suspend fun saveProgress(progress: WatchProgress)
    suspend fun saveBookmark(progress: WatchProgress) { saveProgress(progress) }
    suspend fun markWatchedManually(progress: WatchProgress) { saveProgress(progress) }
    /** Decoder evidence is kept separate from bookmarks and imported/manual watched events. */
    suspend fun playbackCoverage(progress: WatchProgress): PlaybackViewingCoverage? = null
    suspend fun savePlaybackProgress(progress: WatchProgress, coverage: PlaybackViewingCoverage) {
        error("This repository does not support verified playback progress")
    }
    /** Marks remote/local history complete from a validated final-content boundary while preserving resume position. */
    suspend fun markWatchedAtMeaningfulContentEnd(progress: WatchProgress) {
        saveProgress(progress)
    }
    suspend fun getAllProgress(): List<WatchProgress>
    suspend fun deleteProgress(mediaId: String)
    suspend fun clearAllProgress()
    suspend fun syncFromTrakt()
}
