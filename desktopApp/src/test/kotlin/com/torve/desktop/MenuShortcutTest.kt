package com.torve.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Sanity test for the menu accelerator helpers (Fix E-3). Real macOS
 * Cmd-key verification has to happen on a Mac. This pins:
 *  - `primaryAccel` produces a KeyShortcut with the expected key
 *  - shortcuts for different keys are not equal (catches a copy-paste
 *    accident where every shortcut would resolve to the same combo)
 *  - the fullscreen shortcut at minimum carries Key.F
 *
 * It does NOT assert which modifier (Ctrl vs Meta) was set, because that
 * depends on the runtime `os.name` system property and we don't override
 * it from the test (per the brief: no Mac-specific code paths beyond the
 * isMac flag).
 */
class MenuShortcutTest {

    @Test
    fun `primaryAccel binds the requested key`() {
        val q = primaryAccel(Key.Q)
        // KeyShortcut.toString() includes the key name — coarse but stable.
        assertEquals(true, q.toString().contains("Q"))
    }

    @Test
    fun `primaryAccel produces distinct shortcuts for distinct keys`() {
        val q = primaryAccel(Key.Q)
        val w = primaryAccel(Key.W)
        val m = primaryAccel(Key.M)
        assertNotEquals(q, w)
        assertNotEquals(w, m)
        assertNotEquals(q, m)
    }

    @Test
    fun `fullscreen accelerator targets Key F`() {
        val fs = fullscreenAccel()
        assertEquals(true, fs.toString().contains("F"))
    }

    @Test
    fun `primaryAccel applies at least one modifier on every supported platform`() {
        // Either Ctrl or Meta must be set — never a bare key, which would
        // collide with normal typing. Coarse but pins the contract.
        val accel = primaryAccel(Key.Q)
        val s = accel.toString()
        assertEquals(true, s.contains("Ctrl+") || s.contains("Meta+"), "expected modifier in: $s")
    }

    @Test
    fun `KeyShortcut equality respects all fields`() {
        // Sanity that constructing the same shortcut twice equates.
        val a = KeyShortcut(Key.Q, ctrl = true)
        val b = KeyShortcut(Key.Q, ctrl = true)
        assertEquals(a, b)
        assertNotEquals<KeyShortcut>(a, KeyShortcut(Key.Q, meta = true))
    }

    @Test
    fun `nonMacAccel returns null on macOS, ctrl combo otherwise`() {
        val shortcut = nonMacAccel(Key.W)
        if (isMac) {
            assertNull(shortcut)
        } else {
            assertNotNull(shortcut)
            // Equality is field-by-field; if ctrl=true, meta=false, key=W
            // matches a freshly built shortcut, the helper is correct.
            assertEquals(KeyShortcut(Key.W, ctrl = true), shortcut)
        }
    }
}
