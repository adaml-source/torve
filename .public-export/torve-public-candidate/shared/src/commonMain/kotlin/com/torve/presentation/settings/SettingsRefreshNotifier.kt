package com.torve.presentation.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SettingsRefreshNotifier {
    private val _events = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<Long> = _events.asSharedFlow()

    fun notifyRefresh(version: Long) {
        _events.tryEmit(version)
    }
}
