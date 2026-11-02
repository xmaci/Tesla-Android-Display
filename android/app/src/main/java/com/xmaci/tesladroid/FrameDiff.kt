package com.xmaci.tesladroid

/**
 * Finds what changed between two frames, so only that part is encoded, sent, decoded and
 * painted. On MCU1 the cost follows the number of pixels the car has to decode and paint,
 * so a blinking cursor or a moving map pin should cost a small rectangle, not a full frame.
 *
 * Pixels are ARGB ints, row-major, [w]×[h]. The frame is split into [tile]×[tile] tiles;
 * the result is the bounding box of all changed tiles (one rectangle keeps the car side simple).
 */
object FrameDiff {

    /** @return [x, y, w, h] of the changed area, or null if the frames are identical. */
    fun changedRect(cur: IntArray, prev: IntArray, w: Int, h: Int, tile: Int = 32): IntArray? {
        require(cur.size >= w * h && prev.size >= w * h)
        val tilesX = (w + tile - 1) / tile
        var minTx = Int.MAX_VALUE; var maxTx = -1
        var minTy = Int.MAX_VALUE; var maxTy = -1
        for (y in 0 until h) {
            val ty = y / tile
            val row = y * w
            var tx = 0
            while (tx < tilesX) {
                // A tile already inside the box on this tile-row needs no check.
                if (ty in minTy..maxTy && tx in minTx..maxTx) { tx = maxTx + 1; continue }
                val x0 = tx * tile
                val x1 = minOf(x0 + tile, w)
                var i = row + x0
                val end = row + x1
                while (i < end) {
                    if (cur[i] != prev[i]) {
                        if (tx < minTx) minTx = tx
                        if (tx > maxTx) maxTx = tx
                        if (ty < minTy) minTy = ty
                        if (ty > maxTy) maxTy = ty
                        break
                    }
                    i++
                }
                tx++
            }
        }
        if (maxTx < 0) return null
        val x = minTx * tile
        val y = minTy * tile
        val rw = minOf((maxTx + 1) * tile, w) - x
        val rh = minOf((maxTy + 1) * tile, h) - y
        return intArrayOf(x, y, rw, rh)
    }
}
