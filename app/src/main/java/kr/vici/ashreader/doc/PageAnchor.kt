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
     * [estimate] 쪽부터 가까운 순서로 [radius] 쪽 안에서 [anchor] 가 시작하는 쪽. 쪽 끝에 걸쳐 다음 쪽으로 이어져도 찾는다.
     * [textOf] = i쪽 글(못 읽으면 null). 못 찾으면 null(부르는 쪽이 짐작한 쪽을 그대로 쓴다).
     */
    fun find(anchor: String, estimate: Int, count: Int, radius: Int, textOf: (Int) -> String?): Int? {
        if (count <= 0 || anchor.isEmpty()) return null
        val cache = HashMap<Int, String>()
        fun t(i: Int): String = cache.getOrPut(i) { norm(textOf(i).orEmpty()) }
        val est = estimate.coerceIn(0, count - 1)
        for (d in 0..radius) {
            for (k in if (d == 0) intArrayOf(est) else intArrayOf(est - d, est + d)) {
                if (k !in 0 until count) continue
                val a = t(k)
                if (a.isEmpty()) continue
                val joined = if (k + 1 < count) a + t(k + 1).take(anchor.length) else a
                val at = joined.indexOf(anchor)
                if (at in a.indices) return k
            }
        }
        return null
    }
}
