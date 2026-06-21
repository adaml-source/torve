package com.torve.desktop.reminders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide reminder firing engine.
 *
 * Subscribes to [EpgReminderStore.state] and (re-)schedules a single
 * `delay(...)` coroutine per reminder. Re-arms whenever the set
 * changes - adds, removes, snoozes (which mutate `startMs`).
 *
 * Was previously a per-V2LivePage scope which meant snoozing from
 * Settings (where the page isn't mounted) couldn't reschedule. The
 * scheduler now lives for the lifetime of the app, so reminders fire
 * regardless of which page the user is on (or even if the window is
 * minimised - the tray notification still appears).
 *
 * Each fire dispatches via [com.torve.desktop.desktopNotify] which
 * routes to the AWT tray icon's `displayMessage`.
 */
class ReminderScheduler(
    private val store: EpgReminderStore,
    private val notifier: (title: String, body: String) -> Unit,
    private val leadMs: Long = 60_000L,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    /**
     * Start observing the store. Call once at app startup. Idempotent
     * for the same instance - subsequent calls are no-ops.
     */
    fun start() {
        scope.launch {
            store.state.collect { reminders ->
                synchronized(jobs) {
                    val activeKeys = reminders.map { it.key }.toSet()
                    // Cancel jobs for reminders that vanished or were
                    // changed - re-arm fresh below.
                    val toCancel = jobs.keys - activeKeys
                    toCancel.forEach { jobs.remove(it)?.cancel() }
                    reminders.forEach { reminder ->
                        // Re-arm if not already scheduled, or if we want
                        // to pick up a startMs change. Cheapest correct
                        // path: cancel and replace each tick.
                        jobs.remove(reminder.key)?.cancel()
                        jobs[reminder.key] = scope.launch {
                            val waitMs = (reminder.startMs - leadMs - System.currentTimeMillis())
                                .coerceAtLeast(0L)
                            delay(waitMs)
                            if (store.contains(reminder.key)) {
                                runCatching {
                                    notifier(
                                        "Starting soon: ${reminder.title}",
                                        "${reminder.channelName} • ${formatHour(reminder.startMs)}",
                                    )
                                }
                                // Auto-clear after firing so the same
                                // reminder doesn't loop forever.
                                store.remove(reminder.key)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatHour(epochMs: Long): String {
        val fmt = java.time.format.DateTimeFormatter
            .ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        return fmt.format(java.time.Instant.ofEpochMilli(epochMs))
    }
}
