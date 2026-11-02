package com.xmaci.tesladroid

/**
 * Finds what changed between two frames, so only that part is encoded, sent, decoded and
 * painted. On MCU1 the cost follows the number of pixels the car has to decode and paint.
 *
 * Pixels are ARGB ints, row-major, [w]×[h], compared in [tile]×[tile] tiles.
 * Changes far apart (typing: the keyboard at the bottom and the text field at the top) are
 * returned as separate rectangles instead of one box covering the whole screen.
 */
object FrameDiff {

    /** Tiles that differ, as a [tilesY][tilesX] grid; null if nothing changed. */
    fun dirtyTiles(cur: IntArray, prev: IntArray, w: Int, h: Int, tile: Int): Array<BooleanArray>? {
        require(cur.size >= w * h && prev.size >= w * h)
        val tilesX = (w + tile - 1) / tile
        val tilesY = (h + tile - 1) / tile
        val grid = Array(tilesY) { BooleanArray(tilesX) }
        var any = false
        for (y in 0 until h) {
            val rowFlags = grid[y / tile]
            val row = y * w
            for (tx in 0 until tilesX) {
                if (rowFlags[tx]) continue
                var i = row + tx * tile
                val end = row + minOf(tx * tile + tile, w)
                while (i < end) {
                    if (cur[i] != prev[i]) { rowFlags[tx] = true; any = true; break }
                    i++
                }
            }
        }
        return if (any) grid else null
    }

    /**
     * Changed areas as up to [maxRects] rectangles [x, y, w, h], top to bottom.
     * Rows of tiles that changed are grouped into horizontal bands separated by unchanged rows;
     * if there are more bands than [maxRects], the closest bands are merged.
     * @return empty list if nothing changed.
     */
    fun changedRects(cur: IntArray, prev: IntArray, w: Int, h: Int, tile: Int = 32, maxRects: Int = 4): List<IntArray> {
        val grid = dirtyTiles(cur, prev, w, h, tile) ?: return emptyList()
        // bands in tile units: [ty0, ty1, tx0, tx1] inclusive
        val bands = ArrayList<IntArray>()
        for (ty in grid.indices) {
            var minX = -1; var maxX = -1
            for (tx in grid[ty].indices) if (grid[ty][tx]) { if (minX < 0) minX = tx; maxX = tx }
            if (minX < 0) continue
            val last = bands.lastOrNull()
            if (last != null && last[1] == ty - 1) {
                last[1] = ty; last[2] = minOf(last[2], minX); last[3] = maxOf(last[3], maxX)
            } else bands.add(intArrayOf(ty, ty, minX, maxX))
        }
        while (bands.size > maxRects) {
            // merge the neighbouring pair with the smallest vertical gap
            var best = 0; var bestGap = Int.MAX_VALUE
            for (i in 0 until bands.size - 1) {
                val gap = bands[i + 1][0] - bands[i][1]
                if (gap < bestGap) { bestGap = gap; best = i }
            }
            val a = bands[best]; val b = bands.removeAt(best + 1)
            a[1] = b[1]; a[2] = minOf(a[2], b[2]); a[3] = maxOf(a[3], b[3])
        }
        return bands.map { b ->
            val x = b[2] * tile; val y = b[0] * tile
            intArrayOf(x, y, minOf((b[3] + 1) * tile, w) - x, minOf((b[1] + 1) * tile, h) - y)
        }
    }

    /** Single bounding box of all changes, or null. */
    fun changedRect(cur: IntArray, prev: IntArray, w: Int, h: Int, tile: Int = 32): IntArray? {
        val r = changedRects(cur, prev, w, h, tile, 1)
        return r.firstOrNull()
    }
}
