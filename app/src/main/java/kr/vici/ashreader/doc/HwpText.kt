// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * 한글 원본 보기 글자 위치(7B): rhwp 글자 배치 배열(engine-hwp rust/src/textlayout.rs) → [TextPage].
 * 순수 계산(안드로이드 의존 없음 → 단위 시험).
 *
 * 배열: [쪽 폭, 쪽 높이, 줄기 수, 줄기마다 x, y, w, h, n, 글자 왼쪽 위치 n+1개(줄기 x 기준), 글자 코드 n개] — 좌표 단위는 rhwp 쪽 px.
 */
object HwpText {
    class Run(val x: Float, val y: Float, val w: Float, val h: Float, val charX: FloatArray, val codes: IntArray)
    class Layout(val pageW: Float, val pageH: Float, val runs: List<Run>)

    /** 배열 풀기. 형식이 틀리면(길이 부족 등) 읽은 데까지만, 머리부터 틀리면 null. */
    fun decode(a: FloatArray): Layout? {
        if (a.size < 3) return null
        val pw = a[0]; val ph = a[1]
        if (!(pw > 0f && ph > 0f)) return null
        val n = a[2].toInt().coerceAtLeast(0)
        val runs = ArrayList<Run>(n)
        var p = 3
        repeat(n) {
            if (p + 5 > a.size) return Layout(pw, ph, runs)
            val x = a[p]; val y = a[p + 1]; val w = a[p + 2]; val h = a[p + 3]; val k = a[p + 4].toInt()
            p += 5
            if (k <= 0 || p + (k + 1) + k > a.size) return Layout(pw, ph, runs)
            val cx = a.copyOfRange(p, p + k + 1); p += k + 1
            val codes = IntArray(k) { a[p + it].toInt() }; p += k
            runs += Run(x, y, w, h, cx, codes)
        }
        return Layout(pw, ph, runs)
    }

    private fun isSpace(cp: Int) = cp == ' '.code || cp == '\t'.code || cp == 0x3000 || cp == 0xA0

    /**
     * 줄기들을 줄로 이어 쪽 글자를 만든다. 줄이 바뀌면(세로로 반 넘게 안 겹치거나 왼쪽으로 크게 되돌아가면) '\n',
     * 같은 줄인데 줄기 사이가 글자 높이의 1/4 넘게 벌어지면 빈칸(상자 0)을 넣어 낱말을 가른다.
     * [aspect] = 화면에 그리는 쪽의 높이/폭(렌더와 같은 값을 넘긴다). 글자가 없으면 null.
     */
    fun page(l: Layout, aspect: Float = l.pageH / l.pageW): TextPage? {
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        fun put(a: Float, b: Float, c: Float, d: Float) { boxes += a; boxes += b; boxes += c; boxes += d }
        fun gap() = put(0f, 0f, 0f, 0f)
        var prev: Run? = null
        for (r in l.runs) {
            val h = if (r.h > 0f) r.h else (r.w / r.codes.size.coerceAtLeast(1)).coerceAtLeast(1f)
            val pr = prev
            if (pr != null) {
                val ph = if (pr.h > 0f) pr.h else h
                val overlap = minOf(pr.y + ph, r.y + h) - maxOf(pr.y, r.y)
                val back = r.x < pr.x + pr.w - 0.5f * maxOf(h, ph)
                if (overlap < 0.5f * minOf(h, ph) || back) { sb.append('\n'); gap() }
                else {
                    val g = r.x - (pr.x + pr.w)
                    val lastSpace = sb.isNotEmpty() && sb.last().isWhitespace()
                    val firstSpace = r.codes.isNotEmpty() && isSpace(r.codes[0])
                    if (g > 0.25f * h && !lastSpace && !firstSpace) { sb.append(' '); gap() }
                }
            }
            val t = r.y / l.pageH; val b = (r.y + h) / l.pageH
            for (k in r.codes.indices) {
                val x0 = r.x + r.charX[k]; val x1 = r.x + r.charX[k + 1]
                val left = minOf(x0, x1) / l.pageW; val right = maxOf(x0, x1) / l.pageW
                val before = sb.length
                val cp = r.codes[k]
                if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) sb.appendCodePoint(cp) else sb.append('�')
                repeat(sb.length - before) { put(left, t, right, b) }
            }
            prev = r.copyH(h)
        }
        if (sb.isEmpty() || sb.isBlank()) return null
        sb.append('\n'); gap()
        return TextPage(sb.toString(), boxes.toFloatArray(), if (aspect > 0f) aspect else l.pageH / l.pageW)
    }

    private fun Run.copyH(h: Float) = if (h == this.h) this else Run(x, y, w, h, charX, codes)

    fun page(a: FloatArray, aspect: Float? = null): TextPage? {
        val l = decode(a) ?: return null
        return page(l, aspect ?: (l.pageH / l.pageW))
    }
}
