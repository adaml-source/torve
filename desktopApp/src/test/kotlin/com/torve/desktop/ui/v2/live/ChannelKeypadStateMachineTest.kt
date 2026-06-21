package com.torve.desktop.ui.v2.live

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the channel-number keypad state machine. Uses a virtual clock —
 * no Thread.sleep, no flakes.
 */
class ChannelKeypadStateMachineTest {

    private fun fresh() = ChannelKeypadStateMachine()

    @Test
    fun `single digit accumulates`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit3, nowMs = 0)
        assertEquals("3", m.buffer)
    }

    @Test
    fun `multiple digits accumulate in order`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit1, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit2, 100)
        m.handle(ChannelKeypadStateMachine.Event.Digit3, 200)
        assertEquals("123", m.buffer)
    }

    @Test
    fun `enter on populated buffer commits parsed number`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit1, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit2, 100)
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Enter, 200)
        assertEquals(ChannelKeypadStateMachine.Outcome.Commit(12), outcome)
        assertEquals("", m.buffer, "Enter must clear the buffer")
    }

    @Test
    fun `enter on empty buffer is ignored`() {
        val m = fresh()
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Enter, 0)
        assertEquals(ChannelKeypadStateMachine.Outcome.Ignored, outcome)
    }

    @Test
    fun `esc on populated buffer clears without commit`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit5, 0)
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Esc, 50)
        assertEquals(ChannelKeypadStateMachine.Outcome.Consumed, outcome)
        assertEquals("", m.buffer)
    }

    @Test
    fun `esc on empty buffer is ignored`() {
        val m = fresh()
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Esc, 0)
        assertEquals(ChannelKeypadStateMachine.Outcome.Ignored, outcome)
    }

    @Test
    fun `idle longer than timeout clears buffer before next event`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit5, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit5, 1_000)
        // 2_500 ms later, type a new digit — old buffer should drop first.
        m.handle(ChannelKeypadStateMachine.Event.Digit7, 1_000 + 2_500)
        assertEquals("7", m.buffer)
    }

    @Test
    fun `idle just under timeout preserves buffer`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit5, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit5, 1_000)
        m.handle(ChannelKeypadStateMachine.Event.Digit7, 1_000 + 2_499)
        assertEquals("557", m.buffer)
    }

    @Test
    fun `applyIdleTick clears stale buffer and reports the change`() {
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        assertTrue(m.applyIdleTick(0 + 2_500))
        assertEquals("", m.buffer)
    }

    @Test
    fun `applyIdleTick on empty buffer is a no-op`() {
        val m = fresh()
        assertEquals(false, m.applyIdleTick(10_000))
    }

    @Test
    fun `buffer cap is enforced at maxLength`() {
        val m = ChannelKeypadStateMachine(maxLength = 3)
        m.handle(ChannelKeypadStateMachine.Event.Digit1, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit2, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit3, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit4, 0)
        assertEquals("123", m.buffer, "extra digits past cap dropped silently")
    }

    @Test
    fun `commit returns the integer value not the string`() {
        val m = fresh()
        listOf(
            ChannelKeypadStateMachine.Event.Digit3,
            ChannelKeypadStateMachine.Event.Digit4,
            ChannelKeypadStateMachine.Event.Digit5,
        ).forEach { m.handle(it, 0) }
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Enter, 0)
        assertEquals(ChannelKeypadStateMachine.Outcome.Commit(345), outcome)
    }

    @Test
    fun `large numbers (up to 5 digits) commit correctly`() {
        val m = fresh()
        listOf(
            ChannelKeypadStateMachine.Event.Digit1,
            ChannelKeypadStateMachine.Event.Digit2,
            ChannelKeypadStateMachine.Event.Digit3,
            ChannelKeypadStateMachine.Event.Digit4,
            ChannelKeypadStateMachine.Event.Digit5,
        ).forEach { m.handle(it, 0) }
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Enter, 0)
        assertEquals(ChannelKeypadStateMachine.Outcome.Commit(12345), outcome)
    }

    @Test
    fun `caller decides whether the channel exists — state machine just emits the number`() {
        // The unit under test does NOT know the channel catalog, so even a
        // bogus number commits — caller treats absence as a no-op.
        val m = fresh()
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        m.handle(ChannelKeypadStateMachine.Event.Digit9, 0)
        val outcome = m.handle(ChannelKeypadStateMachine.Event.Enter, 0)
        assertEquals(ChannelKeypadStateMachine.Outcome.Commit(99999), outcome)
    }
}
