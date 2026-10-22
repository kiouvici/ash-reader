// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

/** 8비트 회색조 이미지 (칸 인식 입력). 안드로이드 API 없이 JVM 시험 가능. */
class GrayImage(val width: Int, val height: Int, val px: ByteArray = ByteArray(width * height)) {
    init { require(px.size == width * height) }

    operator fun get(x: Int, y: Int): Int = px[y * width + x].toInt() and 0xff
    operator fun set(x: Int, y: Int, v: Int) { px[y * width + x] = v.coerceIn(0, 255).toByte() }

    /** 면적 평균으로 줄이기 (긴 변을 [maxSide] 이하로). 배율도 돌려준다. */
    fun downscale(maxSide: Int): Pair<GrayImage, Double> {
        val f = maxOf(width, height).toDouble() / maxSide
        if (f <= 1.0) return this to 1.0
        val nw = (width / f).toInt().coerceAtLeast(1); val nh = (height / f).toInt().coerceAtLeast(1)
        val out = GrayImage(nw, nh)
        for (y in 0 until nh) {
            val y0 = (y * f).toInt(); val y1 = minOf(height, ((y + 1) * f).toInt().coerceAtLeast(y0 + 1))
            for (x in 0 until nw) {
                val x0 = (x * f).toInt(); val x1 = minOf(width, ((x + 1) * f).toInt().coerceAtLeast(x0 + 1))
                var s = 0; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { s += this[xx, yy]; n++ }
                out[x, y] = s / n
            }
        }
        return out to f
    }

    companion object {
        fun fromArgb(w: Int, h: Int, argb: IntArray): GrayImage {
            val g = GrayImage(w, h)
            for (i in argb.indices) {
                val c = argb[i]
                g.px[i] = ((((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000).toByte()
            }
            return g
        }
    }
}
