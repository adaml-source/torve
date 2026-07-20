package com.torve.android.tv

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class TvNotification(
    val message: String,
    val type: NotificationType = NotificationType.INFO,
    val tag: String? = null,
    val durationMs: Long? = DEFAULT_DURATION_MS,
    val clear: Boolean = false,
)

enum class NotificationType { INFO, SUCCESS, ERROR }

object TvNotificationQueue {
    private val _events = MutableSharedFlow<TvNotification>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun post(
        message: String,
        type: NotificationType = NotificationType.INFO,
        tag: String? = null,
        durationMs: Long? = DEFAULT_DURATION_MS,
    ) {
        _events.tryEmit(TvNotification(message, type, tag, durationMs))
    }

    fun clear(tag: String? = null) {
        _events.tryEmit(TvNotification(message = "", tag = tag, clear = true))
    }
}

private const val DEFAULT_DURATION_MS = 2_400L
