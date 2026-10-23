// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * 한 쪽의 글자와 글자 상자. [text]의 i번째 글자 상자 = [boxes] 4i..4i+3
 * (left, top, right, bottom — 쪽 크기 기준 0..1). 줄 끝 '\n'은 상자 0. [aspect] = 쪽 높이/폭.
 */
class TextPage(val text: String, val boxes: FloatArray, val aspect: Float) {
    fun l(i: Int) = boxes[4 * i]
    fun t(i: Int) = boxes[4 * i + 1]
    fun r(i: Int) = boxes[4 * i + 2]
    fun b(i: Int) = boxes[4 * i + 3]
    fun hasBox(i: Int) = i in text.indices && 4 * i + 3 < boxes.size && r(i) > l(i) && b(i) > t(i)
}

/** 정규화 좌표 상자. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

/** 글자 선택·형광펜·검색에 쓰는 순수 계산(안드로이드 의존 없음 → 단위 시험). */
object TextGeo {

    /** 쪽(높이/폭 = [aspect])을 [w]×[h] 칸 안에 비율 유지로 가운데 맞춘 영역(fitInto 와 같은 규칙). */
    fun contentRect(aspect: Float, w: Float, h: Float): NRect {
        val a = if (aspect > 0f) aspect else 1f
        val dw = minOf(w, h / a)
        val dh = dw * a
        val x = (w - dw) / 2f; val y = (h - dh) / 2f
        return NRect(x, y, x + dw, y + dh)
    }

    /** 점(정규화)에 가장 가까운 글자. 같은 줄(세로로 겹침)을 먼저 본다. 글자가 없으면 -1. */
    fun charAt(p: TextPage, x: Float, y: Float, maxDist: Float = 0.08f): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in p.text.indices) {
            if (!p.hasBox(i)) continue
            val dx = when { x < p.l(i) -> p.l(i) - x; x > p.r(i) -> x - p.r(i); else -> 0f }
            val dy = when { y < p.t(i) -> p.t(i) - y; y > p.b(i) -> y - p.b(i); else -> 0f }
            val d = dx + dy * 3f // 세로 거리에 무게 → 같은 줄 우선
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD <= maxDist) best else -1
    }

    private fun isWordChar(c: Char) = !c.isWhitespace() && c !in ".,!?;:\"'“”‘’()[]{}<>「」『』《》〈〉·…—–-/、。，" && c !in "：；！？（）［］｛｝｢｣〔〕【】＜＞～※☏○●◎□■△▲▷▶◇◆*"

    /** [i] 글자가 든 낱말 범위(끝 제외). 낱말 글자가 아니면 그 글자 하나. */
    fun wordAt(text: String, i: Int): IntRange {
        if (i !in text.indices) return IntRange.EMPTY
        if (!isWordChar(text[i])) return i until i + 1
        var s = i; var e = i + 1
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < text.length && isWordChar(text[e])) e++
        return s until e
    }

    /** [start]..[end](끝 제외) 글자들을 줄마다 하나로 합친 상자들. */
    fun rects(p: TextPage, start: Int, end: Int): List<NRect> {
        val out = ArrayList<NRect>()
        var cur: NRect? = null
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(p.text.length)) {
            if (!p.hasBox(i)) { cur?.let { out += it }; cur = null; continue }
            val r = NRect(p.l(i), p.t(i), p.r(i), p.b(i))
            val c = cur
            cur = if (c != null && r.t < c.b && r.b > c.t && r.l >= c.l - 0.002f) {
                NRect(c.l, minOf(c.t, r.t), maxOf(c.r, r.r), maxOf(c.b, r.b))
            } else { c?.let { out += it }; r }
        }
        cur?.let { out += it }
        return out
    }

    /** 검색용 글: 줄바꿈을 빈칸으로(글자 번호는 그대로). */
    fun flat(text: String) = text.replace('\n', ' ')

    /** [query]가 나오는 모든 시작 위치(대소문자 무시, 줄 넘김은 빈칸으로 봄). */
    fun find(text: String, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hay = flat(text)
        val out = ArrayList<Int>()
        var i = hay.indexOf(q, ignoreCase = true)
        while (i >= 0) { out += i; i = hay.indexOf(q, i + q.length, ignoreCase = true) }
        return out
    }

    /** 검색 결과 앞뒤 [ctx]자 미리보기. */
    fun snippet(text: String, start: Int, len: Int, ctx: Int = 18): String {
        val s = (start - ctx).coerceAtLeast(0); val e = (start + len + ctx).coerceAtMost(text.length)
        return (if (s > 0) "…" else "") + flat(text.substring(s, e)).trim() + (if (e < text.length) "…" else "")
    }

    /** 저장된 형광펜 위치를 지금 쪽 글자에서 다시 찾는다. 못 찾으면 null. */
    fun resolve(text: String, start: Int, end: Int, saved: String): IntRange? {
        if (saved.isEmpty()) return null
        if (start in 0..end && end <= text.length && flat(text.substring(start, end)) == flat(saved)) return start until end
        val i = flat(text).indexOf(flat(saved))
        return if (i >= 0) i until i + saved.length else null
    }
}
