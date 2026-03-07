package com.streamvault.android.tv

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class TvNotification(
    val message: String,
    val type: NotificationType = NotificationType.INFO,
)

enum class NotificationType { INFO, SUCCESS, ERROR }

object TvNotificationQueue {
    private val _events = MutableSharedFlow<TvNotification>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun post(message: String, type: NotificationType = NotificationType.INFO) {
        _events.tryEmit(TvNotification(message, type))
    }
}
