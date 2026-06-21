package com.torve.presentation.transfer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the credential-transfer copy spec.
 *
 * The product brief is explicit about the strings — they're the user's
 * mental model anchor for "which device starts what." A silent rename
 * here would re-introduce the device-flow confusion the rewrite was
 * supposed to fix. So the strings live in [TransferCopy] and these
 * tests assert their key shape.
 */
class TransferCopyTest {

    @Test
    fun `step 1 header makes the device-flow direction explicit`() {
        // The exact "Start on the device you want to set up" wording
        // is the load-bearing UX cue. Don't soften it without thinking.
        assertContains(TransferCopy.SEND_STEP1_HEADER, "Start on the device you want to set up")
    }

    @Test
    fun `step 1 explainer names both endpoints + the menu path`() {
        val s = TransferCopy.SEND_STEP1_EXPLAINER
        assertContains(s, "TV/phone")
        assertContains(s, "Settings")
        assertContains(s, "Receive credentials")
        assertContains(s, "QR code")
        assertContains(s, "receiver code")
    }

    @Test
    fun `receiver field is renamed away from session string`() {
        // The old "Receiver session string" was opaque to anyone who
        // didn't already know the protocol. The new label leads with
        // "code" + names the source device.
        assertEquals("Receiver code from the other device", TransferCopy.SEND_RECEIVER_FIELD_LABEL)
        assertFalse(
            TransferCopy.SEND_RECEIVER_FIELD_LABEL.contains("session", ignoreCase = true),
            "label must not say 'session'",
        )
    }

    @Test
    fun `placeholder mentions both QR scan and paste`() {
        val p = TransferCopy.SEND_RECEIVER_FIELD_PLACEHOLDER
        assertContains(p, "Scan the QR")
        assertContains(p, "paste the receiver code")
    }

    @Test
    fun `empty-receiver error tells the user where the code lives`() {
        // The old error said "Paste the receiver session string first" —
        // that doesn't tell the user where the receiver string COMES from.
        val e = TransferCopy.SEND_RECEIVER_REQUIRED_ERROR
        assertContains(e, "receiver code")
        assertContains(e, "other device")
    }

    @Test
    fun `empty-state hint surfaces the receive-credentials menu path`() {
        assertContains(TransferCopy.SEND_RECEIVER_EMPTY_HINT, "Receive credentials")
    }

    @Test
    fun `relay-unavailable copy preserves manual fallback as escape hatch`() {
        // Per spec: manual paste must remain reachable.
        assertContains(TransferCopy.SEND_RELAY_UNAVAILABLE, "manual sealed-code paste")
    }

    @Test
    fun `camera-denied copy points the user at the manual code`() {
        // Avoid scary "permission required" jargon; just say what to do.
        assertContains(TransferCopy.SEND_CAMERA_DENIED, "Type the receiver code")
        assertFalse(
            TransferCopy.SEND_CAMERA_DENIED.contains("permission", ignoreCase = true),
            "shouldn't lecture about permissions",
        )
    }

    @Test
    fun `TV receive copy frames the desktop or phone as the sender`() {
        assertContains(TransferCopy.RECEIVE_PRIMARY_EXPLAINER_TV, "desktop or phone")
        assertContains(TransferCopy.RECEIVE_PRIMARY_EXPLAINER_TV, "Send credentials")
        assertContains(TransferCopy.RECEIVE_PRIMARY_EXPLAINER_TV, "scan this code")
    }

    @Test
    fun `desktop receive copy mentions both QR scan and the receiver code`() {
        val d = TransferCopy.RECEIVE_PRIMARY_EXPLAINER_DESKTOP
        assertContains(d, "scan this QR")
        assertContains(d, "receiver code")
        // The receiver code is a ~250-char URL, not a 6-digit pairing code.
        // Don't promise "short" until the relay assigns a real pairing code.
        assertFalse(d.contains("short", ignoreCase = true))
    }

    @Test
    fun `privacy disclosure body explains what the relay sees`() {
        val b = TransferCopy.SEND_PRIVACY_DISCLOSURE_BODY
        assertTrue(
            b.contains("cannot read", ignoreCase = true) ||
                b.contains("never see", ignoreCase = true),
            "privacy line must explicitly state servers can't read the contents",
        )
        // Manual fallback must be acknowledged in the privacy line so
        // the user knows the relay is optional, not load-bearing.
        assertTrue(
            b.contains("manual", ignoreCase = true) ||
                b.contains("by hand", ignoreCase = true) ||
                b.contains("paste", ignoreCase = true),
            "privacy line should reference the manual paste fallback",
        )
    }

    @Test
    fun `advanced disclosure header keeps manual paste reachable`() {
        // "Advanced" framing is the spec — don't promote it back to
        // primary, but never hide it.
        assertContains(TransferCopy.SEND_ADVANCED_HEADER, "manual")
    }

    @Test
    fun `not-torve error tells the user where to get the right code`() {
        val s = TransferCopy.SEND_RECEIVER_NOT_TORVE_ERROR
        assertContains(s, "Receive credentials")
        assertContains(s, "other device")
        // No crypto leakage in primary flow.
        assertFalse(s.contains("base64", ignoreCase = true))
        assertFalse(s.contains("JSON", ignoreCase = true))
    }

    @Test
    fun `corrupted-code error gives a single user-friendly action`() {
        val s = TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR
        assertContains(s, "corrupted")
        assertContains(s, "fresh")
        // Crypto / encoding terms must NOT appear in the primary error.
        listOf("base64", "JSON", "public key", "encoding").forEach { term ->
            assertFalse(
                s.contains(term, ignoreCase = true),
                "primary corrupted-error must not leak '$term': $s",
            )
        }
    }

    @Test
    fun `expired-code error names the device that needs to act`() {
        val s = TransferCopy.SEND_RECEIVER_EXPIRED_ERROR
        assertContains(s, "expired")
        assertContains(s, "receiving device")
    }
}
