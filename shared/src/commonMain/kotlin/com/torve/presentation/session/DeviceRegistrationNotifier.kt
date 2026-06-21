package com.torve.presentation.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-component event bus that fires whenever the current device's
 * registration state with the backend changes (typically right after a
 * successful POST /me/devices/register).
 *
 * Subscribers (in particular [com.torve.presentation.subscription.SubscriptionViewModel])
 * use this to invalidate any cached `/me/access-state` snapshot they took
 * BEFORE the device existed in the backend's device table — without it,
 * the very first access-state call on a fresh install races registration
 * and gets back `device_not_registered`, which then sticks until the
 * user manually re-triggers a refresh.
 *
 * `replay = 1` so a subscriber that connects shortly after registration
 * still picks up the event. `extraBufferCapacity = 4` is enough headroom
 * for the rare burst (e.g. re-registration after a network blip).
 */
class DeviceRegistrationNotifier {
    private val _events = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<Long> = _events.asSharedFlow()

    /**
     * Notify subscribers that the current device has just (re-)registered.
     * The emitted Long is a monotonic timestamp so subscribers can
     * de-duplicate or order events if needed.
     */
    fun notifyRegistered(timestampMs: Long) {
        _events.tryEmit(timestampMs)
    }
}
