// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * F-41 TXT 정리(원본은 그대로, 화면에만 적용). 안드로이드 의존 없음 → 단위 시험.
 * ① 빈 줄: [blank] 0 = 그대로, 1 = 연속 빈 줄을 하나로, 2 = 모두 없앰
 * ② [join]: 줄이 . ? ! … " ” 」 』 로 끝나지 않고 다음 줄이 빈 줄이 아니면 두 줄을 합침
 * ③ [trim]: 줄 앞 공백·전각 공백·탭 제거
 */
object TxtClean {
    private val ENDS = setOf('.', '?', '!', '…', '"', '”', '」', '』', '。', '？', '！', '\'', '’', ')', '）', ':', ';')

    fun isOff(blank: Int, join: Boolean, trim: Boolean) = blank == 0 && !join && !trim

    /** 캐시 파일 이름에 붙일 표시(설정이 바뀌면 새로 만든다). */
    fun tag(blank: Int, join: Boolean, trim: Boolean) = "b$blank" + (if (join) "j" else "") + (if (trim) "t" else "")

    fun clean(text: String, blank: Int, join: Boolean, trim: Boolean): String {
        if (isOff(blank, join, trim)) return text
        val src = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val lines = ArrayList<String>(src.size)
        for (raw in src) {
            val l = if (trim) raw.trimStart(' ', '\t', '　', ' ') else raw
            lines += if (l.isBlank()) "" else l.trimEnd()
        }
        // ② 끊긴 줄 합치기
        val joined = ArrayList<String>(lines.size)
        if (join) {
            val sb = StringBuilder()
            for ((i, l) in lines.withIndex()) {
                if (l.isEmpty()) { if (sb.isNotEmpty()) { joined += sb.toString(); sb.clear() }; joined += ""; continue }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(if (sb.isNotEmpty()) l.trimStart(' ', '\t', '　') else l)
                val next = lines.getOrNull(i + 1)
                val ends = l.trimEnd().lastOrNull()?.let { it in ENDS } ?: true
                if (ends || next == null || next.isEmpty()) { joined += sb.toString(); sb.clear() }
            }
            if (sb.isNotEmpty()) joined += sb.toString()
        } else joined += lines
        // ① 빈 줄
        val out = StringBuilder(text.length)
        var prevBlank = false
        var first = true
        for (l in joined) {
            val isBlank = l.isEmpty()
            if (isBlank && (blank == 2 || (blank == 1 && prevBlank))) continue
            if (!first) out.append('\n')
            out.append(l)
            first = false
            prevBlank = isBlank
        }
        return out.toString()
    }
}
