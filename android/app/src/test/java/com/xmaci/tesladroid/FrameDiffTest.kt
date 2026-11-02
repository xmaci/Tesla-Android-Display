package com.xmaci.tesladroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

class FrameDiffRectsTest {
    private val w = 320
    private val h = 640
    private fun frame() = IntArray(w * h)

    @Test fun typingGivesTwoSmallRects() {
        val a = frame(); val b = frame()
        b[40 * w + 100] = 1           // text field near the top   -> tile row 1
        b[600 * w + 10] = 1           // keyboard near the bottom  -> tile row 18
        b[610 * w + 300] = 1          // same keyboard band, other side
        val r = FrameDiff.changedRects(b, a, w, h)
        assertEquals(2, r.size)
        assertArrayEquals(intArrayOf(96, 32, 32, 32), r[0])
        assertArrayEquals(intArrayOf(0, 576, 320, 64), r[1])
    }

    @Test fun adjacentRowsFormOneBand() {
        val a = frame(); val b = frame()
        b[33 * w + 5] = 1; b[70 * w + 200] = 1     // tile rows 1 and 2 -> one band
        val r = FrameDiff.changedRects(b, a, w, h)
        assertEquals(1, r.size)
        assertArrayEquals(intArrayOf(0, 32, 224, 64), r[0])
    }

    @Test fun tooManyBandsAreMergedByClosestGap() {
        val a = frame(); val b = frame()
        for (ty in intArrayOf(0, 2, 4, 10, 18)) b[(ty * 32 + 1) * w + 1] = 1
        val r = FrameDiff.changedRects(b, a, w, h, maxRects = 4)
        assertEquals(4, r.size)
        assertArrayEquals(intArrayOf(0, 0, 32, 96), r[0])   // rows 0 and 2 merged (gap 2, first smallest)
    }

    @Test fun nothingChangedGivesEmpty() {
        assertEquals(0, FrameDiff.changedRects(frame(), frame(), w, h).size)
    }
}
