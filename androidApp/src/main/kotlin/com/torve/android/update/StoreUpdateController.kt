package com.torve.android.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class StoreUpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, DOWNLOADED, INSTALLING, CHECK_FAILED }

data class StoreUpdateState(
    val enabled: Boolean = false,
    val phase: StoreUpdatePhase = StoreUpdatePhase.IDLE,
    val versionCode: Int = 0,
    val showPrompt: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

interface StoreUpdateController {
    val state: StateFlow<StoreUpdateState>
    fun onResume()
    fun checkNow()
    fun beginUpdate()
    fun completeUpdate()
    fun dismissPrompt()
    fun close()
}

internal object NoStoreUpdateController : StoreUpdateController {
    override val state = MutableStateFlow(StoreUpdateState())
    override fun onResume() = Unit
    override fun checkNow() = Unit
    override fun beginUpdate() = Unit
    override fun completeUpdate() = Unit
    override fun dismissPrompt() = Unit
    override fun close() = Unit
}

internal object StoreUpdatePolicy {
    const val REMINDER_INTERVAL_MS = 24L * 60 * 60 * 1_000

    fun shouldRemind(version: Int, dismissedVersion: Int, dismissedAt: Long, now: Long): Boolean =
        version > 0 && (version != dismissedVersion || now - dismissedAt >= REMINDER_INTERVAL_MS || now < dismissedAt)

    fun mayInstall(fullScreenPlayer: Boolean, playing: Boolean, retainedPlayback: Boolean, pictureInPicture: Boolean): Boolean =
        !fullScreenPlayer && !playing && !retainedPlayback && !pictureInPicture
}
