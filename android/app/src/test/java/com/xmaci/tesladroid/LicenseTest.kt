package com.xmaci.tesladroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Codes made by tools/license/tdlicense.py (throw-away test key) must verify in the app, and only with that key. */
class LicenseTest {
    private val pub = "170ce5856ee74d494cdc6c6b5f6a9552c1aec745a38d002c335503bdd505e3a4"
    private val code = "AEAEIMQUY5BACV46K4I3OLUBERFPICHUSLXS3FWBX52HC7ZVKQT6B272KKDGP45VKRRKRA3WW2FRC5RGLDZL2YE5CAFGGCKGMJ5DD5BQ3HPLXIKGAXZQM"

    @Test fun generatorCodeVerifies() {
        val p = License.parse(code, pub)
        assertNotNull(p)
        assertEquals("TD-ABCDE-FGHII", "TD-" + License.base32(p!!.device).let { it.substring(0, 5) + "-" + it.substring(5) })
    }

    @Test fun tamperedCodeFails() {
        val i = code.length / 2
        val bad = code.substring(0, i) + (if (code[i] == 'A') 'B' else 'A') + code.substring(i + 1)
        assertNull(License.parse(bad, pub))
    }

    @Test fun otherKeyFails() {
        assertNull(License.parse(code, "00".repeat(31) + "01"))
    }

    @Test fun base32RoundTrip() {
        val b = byteArrayOf(1, 2, 3, 4, 5, (0xFF).toByte())
        assertEquals(b.toList(), License.unbase32(License.base32(b)).toList())
    }
}
