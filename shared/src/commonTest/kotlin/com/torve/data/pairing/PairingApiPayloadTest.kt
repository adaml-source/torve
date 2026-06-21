package com.torve.data.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

class PairingApiPayloadTest {
    @Test
    fun parsePairingListPayload_accepts_raw_array_payload() {
        val parsed = parsePairingListPayload("[]")

        assertEquals(0, parsed.pairings.size)
    }

    @Test
    fun parsePairingListPayload_accepts_enveloped_payload() {
        val parsed = parsePairingListPayload("""{"pairings": []}""")

        assertEquals(0, parsed.pairings.size)
    }

    @Test
    fun resolvedDeviceTypeLabel_supports_desktop() {
        val pairing = PairedDeviceDto(
            targetDeviceType = "desktop",
            targetPlatform = "windows",
            targetDeviceName = "Office PC",
        )

        assertEquals("desktop", pairing.resolvedDeviceType())
        assertEquals("Desktop", pairing.resolvedDeviceTypeLabel())
    }
}
