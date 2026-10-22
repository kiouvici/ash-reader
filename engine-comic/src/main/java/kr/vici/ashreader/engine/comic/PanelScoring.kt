// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

/**
 * 정답 칸 상자 형식과 채점 (F-72 합격 기준).
 *
 * 정답 JSON (쪽 하나):
 * ```
 * {"page": "003.jpg", "direction": "rtl", "size": [w, h], "panels": [[x, y, w, h], ...]}   // panels 는 읽기 순서
 * ```
 * 파일 하나에 여러 쪽이면 `{"book": "...", "pages": [ {...}, {...} ]}`.
 *
 * 채점: 정답 칸마다 IoU 가 가장 큰 인식 칸을 하나씩(중복 없이) 짝짓고, IoU ≥ 0.7 이고
 * 읽기 순서가 맞으면 맞은 칸. 순서 = 짝지어진 칸들 사이에서 정답 순위와 인식 순위가 같은지.
 * 인식률 = 맞은 칸 ÷ 정답 칸.
 */
object PanelScoring {
    const val IOU_PASS = 0.7

    data class GroundTruth(val page: String, val direction: ReadingDirection, val width: Int, val height: Int, val panels: List<PanelRect>)

    data class Score(val truth: Int, val correct: Int, val matchedWrongOrder: Int, val detected: Int) {
        val rate get() = if (truth == 0) 1.0 else correct.toDouble() / truth
        operator fun plus(o: Score) = Score(truth + o.truth, correct + o.correct, matchedWrongOrder + o.matchedWrongOrder, detected + o.detected)
        companion object { val ZERO = Score(0, 0, 0, 0) }
    }

    fun score(truth: List<PanelRect>, detected: List<PanelRect>): Score {
        // IoU 큰 순서로 탐욕 짝짓기
        val pairs = truth.indices.flatMap { t -> detected.indices.map { d -> Triple(t, d, truth[t].iou(detected[d])) } }
            .filter { it.third >= IOU_PASS }.sortedByDescending { it.third }
        val usedT = HashSet<Int>(); val usedD = HashSet<Int>(); val match = ArrayList<Pair<Int, Int>>()
        for ((t, d, _) in pairs) if (t !in usedT && d !in usedD) { usedT += t; usedD += d; match += t to d }
        val byT = match.sortedBy { it.first }
        val dRank = match.map { it.second }.sorted()
        var correct = 0
        byT.forEachIndexed { rank, (_, d) -> if (dRank[rank] == d) correct++ }
        return Score(truth.size, correct, match.size - correct, detected.size)
    }

    // ---- 아주 작은 JSON 읽기 (외부 의존성 없이 JVM·안드로이드 공용) ----
    fun parse(json: String): List<GroundTruth> {
        val root = Json(json).value()
        @Suppress("UNCHECKED_CAST")
        val pages = (root as Map<String, Any?>)["pages"] as? List<Map<String, Any?>> ?: listOf(root)
        return pages.map { p ->
            val size = p["size"] as List<*>
            GroundTruth(
                page = p["page"] as String,
                direction = if ((p["direction"] as? String)?.lowercase() == "rtl") ReadingDirection.RTL else ReadingDirection.LTR,
                width = (size[0] as Double).toInt(), height = (size[1] as Double).toInt(),
                panels = (p["panels"] as List<*>).map { q -> val a = (q as List<*>).map { (it as Double).toInt() }; PanelRect.xywh(a[0], a[1], a[2], a[3]) },
            )
        }
    }

    fun toJson(g: GroundTruth): String =
        """{"page": "${g.page.replace("\"", "\\\"")}", "direction": "${if (g.direction == ReadingDirection.RTL) "rtl" else "ltr"}", "size": [${g.width}, ${g.height}], "panels": [""" +
            g.panels.joinToString(", ") { "[${it.x}, ${it.y}, ${it.w}, ${it.h}]" } + "]}"

    private class Json(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i++] == '}') return m } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') return l } }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() }
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; 'u' -> { val c = s.substring(i + 1, i + 5).toInt(16).toChar(); i += 4; c }; else -> s[i] }) }
                else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}
