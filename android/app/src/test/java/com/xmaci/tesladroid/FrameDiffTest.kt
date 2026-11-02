package com.xmaci.tesladroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameDiffTest {
    private val w = 100
    private val h = 70

    private fun frame() = IntArray(w * h) { 0xFF000000.toInt() }

    @Test fun identicalFramesGiveNull() {
        assertNull(FrameDiff.changedRect(frame(), frame(), w, h))
    }

    @Test fun singlePixelGivesItsTile() {
        val a = frame(); val b = frame()
        b[40 * w + 70] = 1          // x=70, y=40 -> tile (2,1) with 32px tiles
        assertArrayEquals(intArrayOf(64, 32, 32, 32), FrameDiff.changedRect(b, a, w, h))
    }

    @Test fun edgeTilesAreClipped() {
        val a = frame(); val b = frame()
        b[69 * w + 99] = 1          // bottom-right pixel -> tile (3,2), clipped to 100x70
        assertArrayEquals(intArrayOf(96, 64, 4, 6), FrameDiff.changedRect(b, a, w, h))
    }

    @Test fun twoChangesGiveTheirBoundingBox() {
        val a = frame(); val b = frame()
        b[5 * w + 5] = 1            // tile (0,0)
        b[50 * w + 80] = 1          // tile (2,1)
        assertArrayEquals(intArrayOf(0, 0, 96, 64), FrameDiff.changedRect(b, a, w, h))
    }
}
