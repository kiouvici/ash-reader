// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import kotlin.math.pow

/**
 * 만화 쪽 그림 처리(안드로이드 API 없이 JVM 시험 가능). 화소는 ARGB_8888 정수 배열.
 * - [BorderCrop] 테두리 자동 자르기(F-52)
 * - [Adjust] 밝기·대비·감마·흑백·색 반전(F-57)
 * - [Sharpen] 선명하게 하기(F-56)
 * - [SpreadLayout] 가로로 긴 쪽(펼침 그림) 배치(F-51)
 * - [Webtoon] 웹툰 보기 제안 판정(F-53)
 */

/** 원본 그림 안의 사각형(오른쪽·아래 미포함). */
data class CropBox(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
}

/** 테두리 자동 자르기(F-52). 흰색·검은색 테두리만 자른다. */
object BorderCrop {
    /** 민감도별 밝기 허용 차(0=약, 1=보통, 2=강). */
    private val TOL = intArrayOf(10, 24, 44)
    /** 한 줄에서 테두리 색과 다른 화소 비율 허용치. */
    private val NOISE = doubleArrayOf(0.002, 0.008, 0.025)
    /** 한쪽에서 최대로 자르는 비율. */
    const val MAX_SIDE = 0.3
    /** 잘라 낸 결과가 원래의 이 비율보다 작아지면 자르지 않는다. */
    const val MIN_KEEP = 0.4

    private fun lum(c: Int): Int = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000

    /**
     * 테두리를 찾아 남길 상자를 돌려준다. 테두리가 없거나 판단이 어려우면 전체.
     * 그림 가장자리 줄의 평균 밝기가 밝으면(흰 테두리) 또는 어두우면(검은 테두리) 그 색과 같은 줄을 안쪽으로 걷어 낸다.
     */
    fun detect(px: IntArray, w: Int, h: Int, sensitivity: Int = 1): CropBox {
        val full = CropBox(0, 0, w, h)
        if (w < 8 || h < 8 || px.size < w * h) return full
        val s = sensitivity.coerceIn(0, 2)
        val tol = TOL[s]; val noise = NOISE[s]
        val g = IntArray(w * h) { lum(px[it]) }

        fun rowBad(y: Int, x0: Int, x1: Int, ref: Int): Int { var n = 0; for (x in x0 until x1) if (kotlin.math.abs(g[y * w + x] - ref) > tol) n++; return n }
        fun colBad(x: Int, y0: Int, y1: Int, ref: Int): Int { var n = 0; for (y in y0 until y1) if (kotlin.math.abs(g[y * w + x] - ref) > tol) n++; return n }
        fun rowMean(y: Int): Int { var sum = 0L; for (x in 0 until w) sum += g[y * w + x]; return (sum / w).toInt() }
        fun colMean(x: Int, y0: Int, y1: Int): Int { var sum = 0L; for (y in y0 until y1) sum += g[y * w + x]; return (sum / (y1 - y0).coerceAtLeast(1)).toInt() }
        /** 테두리 색(흰 255 쪽 / 검은 0 쪽). 중간 밝기면 -1(자르지 않음). */
        fun ref(mean: Int): Int = if (mean >= 200 || mean <= 55) mean else -1

        val maxY = (h * MAX_SIDE).toInt(); val maxX = (w * MAX_SIDE).toInt()
        var t = 0; var b = h
        ref(rowMean(0)).takeIf { it >= 0 }?.let { r -> val lim = (w * noise).toInt(); while (t < maxY && rowBad(t, 0, w, r) <= lim) t++ }
        ref(rowMean(h - 1)).takeIf { it >= 0 }?.let { r -> val lim = (w * noise).toInt(); while (h - b < maxY && rowBad(b - 1, 0, w, r) <= lim) b-- }
        if (b - t < h * MIN_KEEP) { t = 0; b = h }
        var l = 0; var r = w
        ref(colMean(0, t, b)).takeIf { it >= 0 }?.let { c -> val lim = ((b - t) * noise).toInt(); while (l < maxX && colBad(l, t, b, c) <= lim) l++ }
        ref(colMean(w - 1, t, b)).takeIf { it >= 0 }?.let { c -> val lim = ((b - t) * noise).toInt(); while (w - r < maxX && colBad(r - 1, t, b, c) <= lim) r-- }
        if (r - l < w * MIN_KEEP) { l = 0; r = w }
        // 그림에 바로 붙여 자르지 않도록 조금 남긴다
        val px2 = maxOf(1, w / 200); val py2 = maxOf(1, h / 200)
        if (l > 0) l = (l - px2).coerceAtLeast(0)
        if (r < w) r = (r + px2).coerceAtMost(w)
        if (t > 0) t = (t - py2).coerceAtLeast(0)
        if (b < h) b = (b + py2).coerceAtMost(h)
        return CropBox(l, t, r, b)
    }

    /** 펼침 그림: 왼쪽·오른쪽을 같은 폭(적은 쪽)으로 자른다(F-52). */
    fun symmetric(box: CropBox, w: Int): CropBox {
        val m = minOf(box.l, w - box.r).coerceAtLeast(0)
        return CropBox(m, box.t, w - m, box.b)
    }
}

/** 밝기·대비·감마·흑백·색 반전(F-57). 모두 기본값이면 [isIdentity]. */
data class Adjust(
    /** -1 ~ 1 (0 = 그대로) */
    val brightness: Float = 0f,
    /** -1 ~ 1 (0 = 그대로) */
    val contrast: Float = 0f,
    /** 0.5 ~ 2 (1 = 그대로). 크면 밝아진다. */
    val gamma: Float = 1f,
    val gray: Boolean = false,
    val invert: Boolean = false,
) {
    val isIdentity: Boolean get() = brightness == 0f && contrast == 0f && gamma == 1f && !gray && !invert

    /** 채널 값 0~255 → 0~255 표. */
    fun lut(): IntArray {
        val cf = if (contrast >= 0f) 1f + contrast * 2f else 1f + contrast
        val g = gamma.coerceIn(0.2f, 5f)
        return IntArray(256) { i ->
            var v = i / 255f
            v = (v - 0.5f) * cf + 0.5f + brightness * 0.5f
            v = v.coerceIn(0f, 1f)
            if (g != 1f) v = v.toDouble().pow(1.0 / g).toFloat()
            if (invert) v = 1f - v
            (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /** [px] 를 제자리에서 바꾼다. */
    fun apply(px: IntArray) {
        if (isIdentity) return
        val t = lut()
        for (i in px.indices) {
            val c = px[i]
            val a = c and -0x1000000
            var r = (c shr 16) and 0xff; var gg = (c shr 8) and 0xff; var b = c and 0xff
            if (gray) { val y = (r * 299 + gg * 587 + b * 114) / 1000; r = y; gg = y; b = y }
            px[i] = a or (t[r] shl 16) or (t[gg] shl 8) or t[b]
        }
    }
}

/** 선명하게 하기(F-56): 3×3 언샤프 마스크. [level] 0=끔, 1=약, 2=중, 3=강. */
object Sharpen {
    private val AMOUNT = floatArrayOf(0f, 0.45f, 0.9f, 1.5f)

    fun amount(level: Int): Float = AMOUNT[level.coerceIn(0, 3)]

    fun apply(px: IntArray, w: Int, h: Int, level: Int) {
        val k = amount(level)
        if (k <= 0f || w < 3 || h < 3) return
        val src = px.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) {
                    val o = i + dy * w
                    for (dx in -1..1) { val c = src[o + dx]; sr += (c shr 16) and 0xff; sg += (c shr 8) and 0xff; sb += c and 0xff }
                }
                val c = src[i]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                val nr = (r + k * (r - sr / 9f)).toInt().coerceIn(0, 255)
                val ng = (g + k * (g - sg / 9f)).toInt().coerceIn(0, 255)
                val nb = (b + k * (b - sb / 9f)).toInt().coerceIn(0, 255)
                px[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}

/** 가로로 긴 쪽 처리(한 쪽 모드, F-51). */
enum class WideMode { SPLIT, ROTATE, KEEP }

/** 화면 한 칸(쪽 자리)에 무엇을 그릴지. */
enum class SlotPart { WHOLE, LEFT, RIGHT, ROTATE, BLANK }

data class PageSlot(val page: Int, val part: SlotPart)

/**
 * 펼침 그림 배치(F-51). 쪽 자리 목록을 만든다.
 * - 두 쪽 모드: 펼침 그림은 펼침 하나를 통째로 쓴다(왼쪽 반·오른쪽 반을 양쪽 자리에 나눠 그려 한 장으로 크게 보이게).
 *   펼침의 첫 자리가 아니면 빈 자리를 하나 넣어 맞춘다. 넘김 화면(pagecurl)의 짝 규칙(F-50 표지 단독)과 같아야 한다.
 * - 한 쪽 모드: 세로 화면이면 설정대로(반으로 나눔·회전·그대로), 가로 화면이면 그대로.
 * - 나눌 때는 읽는 방향을 따른다(오→왼이면 오른쪽 반이 먼저).
 */
object SpreadLayout {
    fun isWide(w: Int, h: Int): Boolean = w > 0 && h > 0 && w > h

    fun build(
        sizes: List<Pair<Int, Int>?>,
        spread: Boolean,
        landscape: Boolean,
        wide: WideMode,
        rtl: Boolean,
        coverAlone: Boolean,
    ): List<PageSlot> {
        val out = ArrayList<PageSlot>(sizes.size + 8)
        val halves = if (rtl) listOf(SlotPart.RIGHT, SlotPart.LEFT) else listOf(SlotPart.LEFT, SlotPart.RIGHT)
        val firstParity = if (coverAlone) 1 else 0
        sizes.forEachIndexed { i, sz ->
            val isWide = sz != null && isWide(sz.first, sz.second)
            when {
                !isWide -> out += PageSlot(i, SlotPart.WHOLE)
                spread -> {
                    // 표지(0쪽)가 펼침 그림이어도 단독 표지 자리에 그대로 둔다
                    if (i == 0 && coverAlone) { out += PageSlot(i, SlotPart.WHOLE); return@forEachIndexed }
                    if (Math.floorMod(out.size, 2) != firstParity) out += PageSlot(-1, SlotPart.BLANK)
                    halves.forEach { out += PageSlot(i, it) }
                }
                landscape || wide == WideMode.KEEP -> out += PageSlot(i, SlotPart.WHOLE)
                wide == WideMode.ROTATE -> out += PageSlot(i, SlotPart.ROTATE)
                else -> halves.forEach { out += PageSlot(i, it) }
            }
        }
        return out
    }

    /** 옛 배치의 [index] 자리에 있던 쪽이 새 배치에서 처음 나오는 자리. */
    fun translate(old: List<PageSlot>?, new: List<PageSlot>?, index: Int): Int {
        val page = if (old == null) index else old.getOrNull(index.coerceIn(0, (old.size - 1).coerceAtLeast(0)))?.let { s ->
            if (s.page >= 0) s.page else old.drop(index).firstOrNull { it.page >= 0 }?.page ?: old.lastOrNull { it.page >= 0 }?.page ?: 0
        } ?: 0
        if (new == null) return page
        val j = new.indexOfFirst { it.page == page }
        return if (j >= 0) j else new.indexOfFirst { it.page > page }.let { if (it >= 0) it else (new.size - 1).coerceAtLeast(0) }
    }
}

/** 웹툰 보기 제안(F-53): 처음 10장 중 6장 이상이 세로/가로 비율 3 이상. */
object Webtoon {
    const val RATIO = 3.0

    fun isTall(w: Int, h: Int): Boolean = w > 0 && h > 0 && h.toDouble() / w >= RATIO

    /** [sizes] = 앞쪽부터 최대 10장(크기 모르면 null). 쪽이 10장보다 적으면 같은 비율(60%)로 본다. */
    fun suggest(sizes: List<Pair<Int, Int>?>): Boolean {
        val first = sizes.take(10)
        if (first.isEmpty()) return false
        val tall = first.count { it != null && isTall(it.first, it.second) }
        val need = if (first.size >= 10) 6 else kotlin.math.ceil(first.size * 0.6).toInt().coerceAtLeast(1)
        return tall >= need
    }

    /**
     * 세로로 긴 쪽을 화면에 띠(strip)로 나눈다(메모리 한도 — 보이는 띠만 디코딩, F-53 타일).
     * [displayW] 화면에 그릴 폭(px), [stripH] 띠 하나의 화면 높이(px). 돌려주는 값 = 원본 좌표 위·아래 경계 목록.
     */
    fun strips(pageW: Int, pageH: Int, displayW: Int, stripH: Int): List<IntArray> {
        if (pageW <= 0 || pageH <= 0 || displayW <= 0) return listOf(intArrayOf(0, pageH.coerceAtLeast(1)))
        val srcStrip = (stripH.toDouble() * pageW / displayW).toInt().coerceAtLeast(64)
        val out = ArrayList<IntArray>()
        var y = 0
        while (y < pageH) {
            var y1 = minOf(pageH, y + srcStrip)
            // 마지막 띠가 너무 얇으면 앞 띠에 붙인다
            if (pageH - y1 in 1 until srcStrip / 4) y1 = pageH
            out += intArrayOf(y, y1)
            y = y1
        }
        return out
    }

    /** 원본 폭 [pageW] 를 [displayW] 이상으로 남기는 가장 큰 2의 거듭제곱 축소 배율. */
    fun sampleFor(pageW: Int, displayW: Int): Int {
        var s = 1
        while (s < 64 && pageW / (s * 2) >= displayW) s *= 2
        return s
    }
}
