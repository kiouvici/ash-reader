// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/** F-24 여백 자르기를 쓸 수 있는 고정 쪽 문서(PDF·XPS·DjVu·Word 원본). 렌더 전에 켠다. */
interface MarginCroppable {
    var cropMargins: Boolean
}

/**
 * F-24 쪽 여백 자르기. 쪽을 작게 그려 바탕(흰 종이)이 아닌 곳의 테두리 상자를 찾고, 그 상자만 화면에 맞춘다.
 * 상자는 쪽마다 한 번 재서 기억한다. 글자 상자(선택·검색·형광펜)와 쪽 비율도 같은 상자로 옮긴다.
 */
class MarginCrop {
    private val boxes = ConcurrentHashMap<Int, NRect>()

    /** 쪽 [index] 의 내용 상자(쪽 기준 0..1). [small] = 흰 바탕으로 작게 그린 쪽(이 함수가 recycle). */
    fun box(index: Int, small: () -> Bitmap?): NRect = boxes[index] ?: run {
        val bmp = runCatching { small() }.getOrNull()
        val r = if (bmp == null) FULL else try {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            detect(px, w, h)
        } finally { bmp.recycle() }
        boxes[index] = r
        r
    }

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
        /** 상자 감지에 쓰는 작은 그림 폭(px). */
        const val PROBE_W = 240

        fun isFull(r: NRect) = r.l <= 0f && r.t <= 0f && r.r >= 1f && r.b >= 1f

        /**
         * 흰 바탕 쪽 그림(ARGB)에서 내용 상자. 줄(가로·세로)마다 어두운 점이 2개 이상이면 내용으로 본다(먼지 점 무시).
         * 둘레를 쪽의 2% 남긴다. 잘라도 별 차이가 없거나(각 변 95% 이상) 내용이 너무 작으면(10% 미만) 자르지 않는다.
         */
        fun detect(px: IntArray, w: Int, h: Int): NRect {
            if (w < 8 || h < 8) return FULL
            fun dark(c: Int): Boolean {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                return (r * 299 + g * 587 + b * 114) / 1000 < 225
            }
            val rowHits = IntArray(h); val colHits = IntArray(w)
            for (y in 0 until h) {
                val o = y * w
                for (x in 0 until w) if (dark(px[o + x])) { rowHits[y]++; colHits[x]++ }
            }
            val minRow = maxOf(2, w / 200); val minCol = maxOf(2, h / 200)
            var top = 0; while (top < h && rowHits[top] < minRow) top++
            var bottom = h - 1; while (bottom > top && rowHits[bottom] < minRow) bottom--
            var left = 0; while (left < w && colHits[left] < minCol) left++
            var right = w - 1; while (right > left && colHits[right] < minCol) right--
            if (top >= h || left >= w) return FULL
            val pad = 0.02f
            val l = (left.toFloat() / w - pad).coerceAtLeast(0f)
            val t = (top.toFloat() / h - pad).coerceAtLeast(0f)
            val r = ((right + 1).toFloat() / w + pad).coerceAtMost(1f)
            val b = ((bottom + 1).toFloat() / h + pad).coerceAtMost(1f)
            if (r - l < 0.10f || b - t < 0.10f) return FULL
            if (r - l >= 0.95f && b - t >= 0.95f) return FULL
            return NRect(l, t, r, b)
        }

        /** 잘린 쪽의 세로/가로 비율. */
        fun aspect(raw: Float, r: NRect): Float = raw * (r.b - r.t) / (r.r - r.l).coerceAtLeast(0.01f)

        /** 쪽 기준 글자 상자 → 잘린 쪽 기준. 상자 밖 글자도 그대로 둔다(화면 밖일 뿐). */
        fun mapText(tp: TextPage, r: NRect): TextPage {
            if (isFull(r)) return tp
            val cw = (r.r - r.l).coerceAtLeast(0.01f); val ch = (r.b - r.t).coerceAtLeast(0.01f)
            val b = tp.boxes.copyOf()
            var i = 0
            while (i + 3 < b.size) {
                if (b[i + 2] > b[i] && b[i + 3] > b[i + 1]) {
                    b[i] = (b[i] - r.l) / cw; b[i + 2] = (b[i + 2] - r.l) / cw
                    b[i + 1] = (b[i + 1] - r.t) / ch; b[i + 3] = (b[i + 3] - r.t) / ch
                }
                i += 4
            }
            return TextPage(tp.text, b, aspect(tp.aspect, r))
        }

        /** 그림 영역(4개씩, 쪽 기준) → 잘린 쪽 기준(F-21 그림은 반전 안 함). */
        fun mapRects(rects: FloatArray?, r: NRect): FloatArray? {
            if (rects == null || isFull(r)) return rects
            val cw = (r.r - r.l).coerceAtLeast(0.01f); val ch = (r.b - r.t).coerceAtLeast(0.01f)
            val out = FloatArray(rects.size)
            var i = 0
            while (i + 3 < rects.size) {
                out[i] = ((rects[i] - r.l) / cw).coerceIn(0f, 1f); out[i + 2] = ((rects[i + 2] - r.l) / cw).coerceIn(0f, 1f)
                out[i + 1] = ((rects[i + 1] - r.t) / ch).coerceIn(0f, 1f); out[i + 3] = ((rects[i + 3] - r.t) / ch).coerceIn(0f, 1f)
                i += 4
            }
            return out
        }

        /** 쪽 전체 그림 [raw] 에서 [r] 부분만 잘라 낸다([raw] 는 recycle). */
        fun cut(raw: Bitmap, r: NRect): Bitmap {
            if (isFull(r)) return raw
            val x = (r.l * raw.width).toInt().coerceIn(0, raw.width - 1)
            val y = (r.t * raw.height).toInt().coerceIn(0, raw.height - 1)
            val w = ((r.r - r.l) * raw.width).toInt().coerceIn(1, raw.width - x)
            val h = ((r.b - r.t) * raw.height).toInt().coerceIn(1, raw.height - y)
            val out = Bitmap.createBitmap(raw, x, y, w, h)
            if (out !== raw) raw.recycle()
            return out
        }
    }
}
