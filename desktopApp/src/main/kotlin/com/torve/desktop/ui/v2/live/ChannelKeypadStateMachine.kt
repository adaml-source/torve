package com.torve.desktop.ui.v2.live

/**
 * Pure state machine driving the Live-TV channel-number keypad. Extracted
 * from V2LivePage's Composable scope so behaviour can be unit-tested with
 * a virtual clock - the Composable wrapper still owns the focus + idle
 * timer, but every keystroke decision routes through here.
 *
 * Behaviour pinned by ChannelKeypadStateMachineTest:
 *  - Digits append, max 5 chars (i.e. matches channel numbers up to 99 999).
 *  - Enter on a non-empty buffer commits and clears.
 *  - Esc on a non-empty buffer clears without committing.
 *  - 2.5 s of idle clears the buffer.
 *  - Any non-digit / non-control key is ignored when the buffer is empty.
 */
class ChannelKeypadStateMachine(
    private val idleTimeoutMs: Long = 2_500L,
    private val maxLength: Int = 5,
) {
    var buffer: String = ""
        private set

    /** Wall-clock ms timestamp of the most recent keystroke that mutated buffer. */
    private var lastKeystrokeAt: Long = 0L

    sealed interface Event {
        data object Digit0 : Event; data object Digit1 : Event; data object Digit2 : Event
        data object Digit3 : Event; data object Digit4 : Event; data object Digit5 : Event
        data object Digit6 : Event; data object Digit7 : Event; data object Digit8 : Event
        data object Digit9 : Event
        data object Enter : Event
        data object Esc : Event
    }

    sealed interface Outcome {
        /** Buffer changed (typed digit or cleared). UI should redraw. */
        data object Consumed : Outcome
        /** Enter on a numeric buffer - caller should jump to [channelNumber]. */
        data class Commit(val channelNumber: Int) : Outcome
        /** Event was ignored (e.g. Esc on empty buffer). */
        data object Ignored : Outcome
    }

    /**
     * Apply [event] at wall-clock [nowMs]. The clock is injected so tests
     * can fast-forward without sleeping. In production wiring [nowMs] is
     * `System.currentTimeMillis()`.
     */
    fun handle(event: Event, nowMs: Long): Outcome {
        // Idle clear - if it's been more than the timeout since the last
        // keystroke, drop the buffer before processing this event.
        if (buffer.isNotEmpty() && nowMs - lastKeystrokeAt >= idleTimeoutMs) {
            buffer = ""
        }
        return when (event) {
            Event.Enter -> {
                if (buffer.isEmpty()) return Outcome.Ignored
                val num = buffer.toIntOrNull()
                buffer = ""
                if (num != null) Outcome.Commit(num) else Outcome.Consumed
            }
            Event.Esc -> {
                if (buffer.isEmpty()) return Outcome.Ignored
                buffer = ""
                Outcome.Consumed
            }
            else -> {
                val digit = digitOf(event)
                if (buffer.length >= maxLength) return Outcome.Consumed
                buffer += digit
                lastKeystrokeAt = nowMs
                Outcome.Consumed
            }
        }
    }

    /**
     * Manually trigger an idle-driven clear. Returns true if anything changed.
     * Used by the Compose host's LaunchedEffect to mirror state precisely.
     */
    fun applyIdleTick(nowMs: Long): Boolean {
        if (buffer.isEmpty()) return false
        if (nowMs - lastKeystrokeAt < idleTimeoutMs) return false
        buffer = ""
        return true
    }

    private fun digitOf(event: Event): Char = when (event) {
        Event.Digit0 -> '0'; Event.Digit1 -> '1'; Event.Digit2 -> '2'
        Event.Digit3 -> '3'; Event.Digit4 -> '4'; Event.Digit5 -> '5'
        Event.Digit6 -> '6'; Event.Digit7 -> '7'; Event.Digit8 -> '8'
        Event.Digit9 -> '9'
        else -> error("non-digit routed through digit branch: $event")
    }
}
