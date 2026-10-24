// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.graphics.Bitmap

/**
 * AI 화질 향상(Real-CUGAN)이 흑백 스캔의 잡티·망점을 색으로 살려 붉게 물드는 것을 막는다(작업 03).
 * 흑백 스캔은 누런 종이처럼 한 가지 색조를 띠는 경우가 많다 — 밝은 부분(종이)의 색조를 재어, 모든 화소가
 * '그 색조 × 밝기'에 가까우면 단색 스캔으로 보고, AI 결과의 밝기만 남겨 같은 색조를 다시 입힌다.
 * 판정·변환 규칙은 안드로이드 없이 단위 시험한다.
 */
object GrayKeep {
    /** 색조로 예측한 색과 실제 색의 채널 차이 허용치(스캔 번짐·JPEG 여유). */
    const val TOLERANCE = 28
    /** 고른 화소 중 이 비율 이상이 예측에 맞으면 단색 스캔. */
    const val MONO_RATIO = 0.985f
    /** 종이 색조를 잴 밝기 하한. */
    private const val PAPER_Y = 150

    /** 단색 스캔의 색조: 밝기 1 에 대한 R·G·B 배율. 순수 흑백이면 (1, 1, 1). */
    data class Tint(val r: Float, val g: Float, val b: Float)

    fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114 + 500) / 1000
    }

    /** 고른 화소 [samples] 가 단색 스캔이면 그 색조, 아니면 null. */
    fun monoTint(samples: IntArray): Tint? {
        if (samples.isEmpty()) return null
        var sr = 0L; var sg = 0L; var sb = 0L; var sy = 0L
        for (p in samples) {
            val y = luma(p)
            if (y < PAPER_Y) continue
            sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; sy += y
        }
        val tint = if (sy == 0L) Tint(1f, 1f, 1f) else Tint(sr.toFloat() / sy, sg.toFloat() / sy, sb.toFloat() / sy)
        // 색조가 너무 진하면(컬러 그림의 한 색이 많은 경우) 단색 스캔으로 보지 않는다
        if (maxOf(tint.r, tint.g, tint.b) - minOf(tint.r, tint.g, tint.b) > 0.35f) return null
        var ok = 0
        for (p in samples) {
            val y = luma(p)
            val e = maxOf(
                kotlin.math.abs(((p shr 16) and 0xFF) - y * tint.r),
                kotlin.math.abs(((p shr 8) and 0xFF) - y * tint.g),
                kotlin.math.abs((p and 0xFF) - y * tint.b),
            )
            if (e <= TOLERANCE) ok++
        }
        return if (ok >= samples.size * MONO_RATIO) tint else null
    }

    /** 화소의 밝기만 남겨 [tint] 를 입힌다. 알파는 그대로. */
    fun apply(p: Int, tint: Tint): Int {
        val y = luma(p)
        fun c(k: Float) = (y * k + 0.5f).toInt().coerceIn(0, 255)
        return (p and 0xFF000000.toInt()) or (c(tint.r) shl 16) or (c(tint.g) shl 8) or c(tint.b)
    }

    /** [bmp] 에서 약 4천 화소를 고르게 골라 단색 스캔 색조를 잰다. */
    fun monoTint(bmp: Bitmap): Tint? {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val step = maxOf(1, kotlin.math.sqrt(w.toDouble() * h / 4096.0).toInt())
        val out = ArrayList<Int>()
        var y = 0
        while (y < h) { var x = 0; while (x < w) { out += bmp.getPixel(x, y); x += step }; y += step }
        return monoTint(out.toIntArray())
    }

    /** [bmp](바꿀 수 있는 비트맵)의 화소를 제자리에서 밝기 × [tint] 로. */
    private fun paint(bmp: Bitmap, tint: Tint) {
        val w = bmp.width
        val row = IntArray(w)
        for (yy in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, yy, w, 1)
            for (i in 0 until w) row[i] = apply(row[i], tint)
            bmp.setPixels(row, 0, w, 0, yy, w, 1)
        }
    }

    private val GRAY = Tint(1f, 1f, 1f)

    /**
     * AI 를 단색 스캔에 맞게 돌린다: 원본 [src] 가 단색 스캔이면 AI 에 넣기 전에 회색으로 바꾸고(스캔 JPEG 의 눈에 안 보이는
     * 색 얼룩 덩어리를 AI 가 붉게·다른 무늬로 키우던 것 — 대표님 확인), 결과에 원래 종이 색조를 다시 입힌다.
     * 컬러 그림이면 [engine] 결과 그대로. [src] 는 바꾸지 않는다.
     */
    fun run(src: Bitmap, engine: (Bitmap) -> Bitmap): Bitmap {
        val tint = monoTint(src) ?: return engine(src)
        val gray = src.copy(Bitmap.Config.ARGB_8888, true)
        val out = try { paint(gray, GRAY); engine(gray) } finally { gray.recycle() }
        val m = if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
        paint(m, tint)
        return m
    }
}
