// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * 작업 11: 회전·글꼴 변경으로 쪽을 다시 나눌 때 읽던 자리를 '읽은 비율'이 아니라 본문 글자로 찾는다.
 * 읽던 쪽의 첫 글자 몇 개(띄어쓰기·줄바꿈 뺌)를 자리 표시로 두고, 새 배치에서 비율로 짐작한 쪽 근처부터
 * 그 글자가 '시작하는' 쪽을 찾는다. 앞쪽에서 비율 반올림으로 몇 쪽씩 깎이던 것(태블릿 4쪽 → 1쪽)을 막는다.
 */
object PageAnchor {
    /** 자리 표시 길이(띄어쓰기 뺀 글자 수). 짧으면 비슷한 문장이 되풀이되는 책에서 다른 곳과 겹친다(쪽 끝에 걸쳐도 찾으므로 길어도 됨). */
    const val LEN = 32

    /** 비교용: 띄어쓰기·줄바꿈·소프트 하이픈을 뺀다(배치에 따라 줄이 바뀌는 곳만 달라지게). */
    fun norm(s: String): String = buildString(s.length) { s.forEach { if (!it.isWhitespace() && it != '­') append(it) } }

    /** 쪽 글에서 자리 표시. 글자가 너무 적은 쪽(그림·빈 쪽)은 null. */
    fun of(text: String?): String? = text?.let(::norm)?.takeIf { it.length >= 6 }?.take(LEN)

    /**
     * [estimate] 쪽 근처 [radius] 쪽 안에서 [anchor] 가 든 쪽. 쪽 끝에 걸쳐 다음 쪽으로 이어져도 찾는다.
     * 쪽 경계에 걸친 줄은 앞 쪽 끝과 뒤 쪽 첫머리 두 쪽 글에 모두 들어갈 수 있다(한글 표 문서, CI 4쪽 → 3쪽) —
     * 그래서 자리 표시가 쪽 첫머리([HEAD] 글자 안)에 있는 쪽을 먼저, 그런 쪽이 없으면 [estimate] 에 가장 가까운 쪽.
     * [textOf] = i쪽 글(못 읽으면 null). 못 찾으면 null(부르는 쪽이 짐작한 쪽을 그대로 쓴다).
     */
    fun find(anchor: String, estimate: Int, count: Int, radius: Int, textOf: (Int) -> String?): Int? {
        if (count <= 0 || anchor.isEmpty()) return null
        val cache = HashMap<Int, String>()
        fun t(i: Int): String = cache.getOrPut(i) { norm(textOf(i).orEmpty()) }
        val est = estimate.coerceIn(0, count - 1)
        var nearest: Int? = null
        for (d in 0..radius) {
            for (k in if (d == 0) intArrayOf(est) else intArrayOf(est - d, est + d)) {
                if (k !in 0 until count) continue
                val a = t(k)
                if (a.isEmpty()) continue
                val joined = if (k + 1 < count) a + t(k + 1).take(anchor.length) else a
                val at = joined.indexOf(anchor)
                if (at !in a.indices) continue
                if (at < HEAD) return k
                if (nearest == null) nearest = k
            }
        }
        return nearest
    }

    /** '쪽 첫머리'로 볼 글자 수(띄어쓰기 뺌). 쪽 위 머리글·쪽 번호 몇 글자가 앞에 붙어도 첫머리로 본다. */
    const val HEAD = 40
}
