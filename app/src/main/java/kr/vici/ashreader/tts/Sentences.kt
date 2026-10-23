// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.tts

/** 읽을 문장 하나: 쪽 글자 [start]..[end](끝 제외)와 소리 낼 글. */
data class Sentence(val page: Int, val start: Int, val end: Int, val text: String)

/** 쪽 글을 문장으로 나눈다(F-13 듣기·읽는 위치 강조). 안드로이드 의존 없음 → 단위 시험. */
object Sentences {
    private const val ENDS = ".?!…。？！"
    private const val CLOSERS = "\"'”’」』)]）》〉"
    const val MAX = 220

    fun split(page: Int, text: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        var s = 0
        var i = 0
        val n = text.length
        fun emit(e: Int) {
            var a = s; var b = e
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b > a) {
                val spoken = text.substring(a, b).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
                if (spoken.any { it.isLetterOrDigit() }) {
                    // 너무 긴 문장은 쉼표·빈칸에서 끊는다(엔진 길이 제한·강조 범위)
                    if (b - a > MAX) {
                        var x = a
                        while (b - x > MAX) {
                            var cut = text.lastIndexOf(", ", x + MAX).takeIf { it > x + MAX / 3 }?.plus(1)
                                ?: text.lastIndexOf(' ', x + MAX).takeIf { it > x + MAX / 3 }
                                ?: (x + MAX)
                            if (cut <= x) cut = x + MAX
                            add(out, page, text, x, cut)
                            x = cut
                            while (x < b && text[x].isWhitespace()) x++
                        }
                        if (x < b) add(out, page, text, x, b)
                    } else out += Sentence(page, a, b, spoken)
                }
            }
            s = e
        }
        while (i < n) {
            val c = text[i]
            if (c in ENDS) {
                var j = i + 1
                while (j < n && (text[j] in ENDS || text[j] in CLOSERS)) j++
                if (j >= n || text[j].isWhitespace()) { emit(j); i = j; continue }
                i = j; continue
            }
            i++
        }
        emit(n)
        return out
    }

    private fun add(out: MutableList<Sentence>, page: Int, text: String, a: Int, b: Int) {
        var x = a; var y = b
        while (x < y && text[x].isWhitespace()) x++
        while (y > x && text[y - 1].isWhitespace()) y--
        if (y > x) out += Sentence(page, x, y, text.substring(x, y).replace('\n', ' ').replace(Regex("\\s+"), " ").trim())
    }
}
