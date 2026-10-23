// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.tts

/**
 * 읽을 문장 하나: 쪽 글자 [start]..[end](끝 제외)와 소리 낼 글.
 * 쪽 경계에 걸친 문장이면 다음 쪽 글자 [start2]..[end2] 도 이어 읽고, 소리 낼 글의 [split] 번째 글자부터가 다음 쪽 몫이다.
 */
data class Sentence(
    val page: Int, val start: Int, val end: Int, val text: String,
    val start2: Int = -1, val end2: Int = -1, val split: Int = -1,
) {
    /** 다음 쪽까지 이어지는 문장인가. */
    val spans: Boolean get() = split >= 0
}

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

    /** 쪽 글이 문장 중간에서 끝나는가(마지막 글자가 마침표류·닫는 따옴표 뒤 마침표류가 아님). */
    fun dangling(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty() || !t.any { it.isLetterOrDigit() }) return false
        var i = t.length - 1
        while (i > 0 && t[i] in CLOSERS) i--
        return t[i] !in ENDS
    }

    /**
     * 쪽 경계를 넘는 문장을 끊지 않고 읽게 나눈다(듣기).
     * - 이 쪽이 문장 중간에서 끝나고 다음 쪽에 문장이 둘 이상이면, 마지막 문장에 다음 쪽 첫 문장을 붙인다.
     * - 앞 쪽이 문장 중간에서 끝나 이 쪽 첫 문장을 가져갔으면(같은 규칙), 이 쪽 첫 문장은 뺀다.
     * 양쪽이 같은 규칙을 쓰므로 어느 쪽에서 시작해도 빠지거나 두 번 읽는 글이 없다.
     */
    fun splitAcross(page: Int, text: String, prevText: String?, nextText: String?): List<Sentence> {
        var base = split(page, text)
        if (prevText != null && dangling(prevText) && base.size >= 2) base = base.drop(1)
        if (nextText != null && dangling(text) && base.isNotEmpty()) {
            val nx = split(page + 1, nextText)
            val head = nx.firstOrNull()
            val last = base.last()
            if (nx.size >= 2 && head != null && last.split < 0 && last.text.length + head.text.length <= MAX * 2) {
                val joined = last.text + " " + head.text
                base = base.dropLast(1) + last.copy(text = joined, start2 = head.start, end2 = head.end, split = last.text.length + 1)
            }
        }
        return base
    }

    private fun add(out: MutableList<Sentence>, page: Int, text: String, a: Int, b: Int) {
        var x = a; var y = b
        while (x < y && text[x].isWhitespace()) x++
        while (y > x && text[y - 1].isWhitespace()) y--
        if (y > x) out += Sentence(page, x, y, text.substring(x, y).replace('\n', ' ').replace(Regex("\\s+"), " ").trim())
    }
}
