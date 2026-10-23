// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * F-33 TXT 장·절 제목 찾기(안드로이드 의존 없음 → 단위 시험).
 * [mask] 비트: [CHAPTER] 제N장·제N화·N화… / [LATIN] Chapter N·Part N… / [NAMED] 프롤로그·에필로그·외전… / [NUMBER] 번호만 있는 줄(#1, < 1 >, 1.)
 * [custom] = 사용자 정규식(한 줄 전체와 맞춰 봄). 틀린 식은 무시한다.
 */
object TxtToc {
    const val CHAPTER = 1
    const val LATIN = 2
    const val NAMED = 4
    const val NUMBER = 8
    const val ALL = CHAPTER or LATIN or NAMED or NUMBER

    /** 찾은 제목: [line] = 줄 번호(0부터), [depth] 0 = 부·편·권, 1 = 그 아래(부가 없으면 모두 0). */
    data class Heading(val line: Int, val title: String, val depth: Int)

    private const val NUM = "[0-9０-９]{1,4}|[一二三四五六七八九十百千零〇]{1,6}|[일이삼사오육칠팔구십백영공]{1,6}"
    private val chapter = Regex("^제\\s*($NUM)\\s*([장화편부권절막회])(?:$|[\\s.:：\\-—–)\\]】』」>》〉]).{0,50}$")
    private val chapterBare = Regex("^([0-9０-９]{1,4})\\s*([장화])(?:$|[\\s.:：\\-—–]).{0,50}$")
    private val latin = Regex(
        "^(chapter|part|book|episode|volume|section)\\s+([0-9]{1,4}|[ivxlcdm]{1,8}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b.{0,60}$",
        RegexOption.IGNORE_CASE,
    )
    private val named = Regex(
        "^[<\\[【〈《「『(]?\\s*(프롤로그|에필로그|외전|번외|서장|종장|서문|후기|작가의\\s*말|작가\\s*후기|머리말|맺음말|들어가며|나오며|序章|終章|prologue|epilogue|interlude|afterword|foreword)(?:$|[\\s.:：\\-—–>\\]】〉》」』)0-9]).{0,50}$",
        RegexOption.IGNORE_CASE,
    )
    private val number = listOf(
        Regex("^[#＃]\\s*[0-9]{1,4}\\s*\\.?$"),
        Regex("^[<\\[【〈《(]\\s*[0-9]{1,4}\\s*[>\\]】〉》)]$"),
        Regex("^[-─=*~]{1,3}\\s*[0-9]{1,4}\\s*[-─=*~]{1,3}$"),
        Regex("^[0-9]{1,4}\\s*[.)]?$"),
    )
    private const val MAX_LEN = 60
    private const val MAX_HEADINGS = 3000

    /** 사용자 식이 맞는 정규식인지(설정 화면 안내용). */
    fun validCustom(custom: String): Boolean = custom.isBlank() || runCatching { Regex(custom) }.isSuccess

    fun find(lines: List<String>, mask: Int, custom: String = ""): List<Heading> {
        val user = custom.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
        if ((mask and ALL) == 0 && user == null) return emptyList()
        data class Raw(val line: Int, val title: String, val top: Boolean, val numberOnly: Boolean)
        val raw = ArrayList<Raw>()
        for ((i, l0) in lines.withIndex()) {
            val l = l0.trim().trim('\u3000')
            if (l.isEmpty() || l.length > MAX_LEN) continue
            var hit = false; var top = false; var numberOnly = false
            if ((mask and CHAPTER) != 0) {
                chapter.matchEntire(l)?.let { m -> hit = true; top = m.groupValues[2] in setOf("편", "부", "권") }
                    ?: chapterBare.matchEntire(l)?.let { hit = true }
            }
            if (!hit && (mask and LATIN) != 0 && latin.matches(l)) {
                hit = true; top = l.startsWith("part", true) || l.startsWith("book", true) || l.startsWith("volume", true)
            }
            if (!hit && (mask and NAMED) != 0 && named.matches(l)) hit = true
            if (!hit && (mask and NUMBER) != 0 && number.any { it.matches(l) }) { hit = true; numberOnly = true }
            if (!hit && user != null && runCatching { user.containsMatchIn(l) }.getOrDefault(false)) hit = true
            if (hit) raw += Raw(i, l, top, numberOnly)
            if (raw.size >= MAX_HEADINGS) break
        }
        // 번호만 있는 줄은 셋 이상이고 대체로 커질 때만(본문의 숫자 줄과 구별)
        val nums = raw.filter { it.numberOnly }
        val keepNums = nums.size >= 3 && run {
            val vs = nums.mapNotNull { r -> Regex("[0-9]{1,4}").find(r.title)?.value?.toIntOrNull() }
            vs.zipWithNext().count { (a, b) -> b > a } >= (vs.size - 1) * 0.7
        }
        val kept = raw.filter { !it.numberOnly || keepNums }
        val anyTop = kept.any { it.top } && kept.any { !it.top }
        return kept.map { Heading(it.line, it.title, if (anyTop && !it.top) 1 else 0) }
    }
}

/** 따옴표로 시작하는 대화문 문단인지(F-40). 앞 공백은 건너뛴다. */
object Dialogue {
    private const val QUOTES = "\"“”「『'‘’«《〈―—"
    fun starts(text: CharSequence): Boolean {
        for (c in text) {
            if (c == ' ' || c == '\t' || c == '\u3000' || c == '\u00A0') continue
            return c in QUOTES
        }
        return false
    }

    /**
     * XHTML 안 `<p>` 중 글이 따옴표로 시작하는 것에 class "ash-dlg" 를 붙인다(한글·Word 이북식 변환 결과).
     * 문단 안 첫 글자 앞의 태그(<span> 등)는 건너뛴다.
     */
    fun mark(xhtml: String): String {
        val sb = StringBuilder(xhtml.length + 256)
        var i = 0
        while (true) {
            val p = xhtml.indexOf("<p", i)
            if (p < 0) { sb.append(xhtml, i, xhtml.length); break }
            val after = xhtml.getOrNull(p + 2)
            val end = xhtml.indexOf('>', p)
            if (end < 0 || (after != '>' && after != ' ' && after != '\t' && after != '\n')) { sb.append(xhtml, i, p + 2); i = p + 2; continue }
            sb.append(xhtml, i, p)
            val tag = xhtml.substring(p, end + 1)
            // 문단의 첫 글자 찾기(태그·공백 건너뜀)
            var k = end + 1
            var first: Char? = null
            while (k < xhtml.length) {
                val c = xhtml[k]
                if (c == '<') { val e = xhtml.indexOf('>', k); if (e < 0) break; if (xhtml.startsWith("</p", k)) break; k = e + 1; continue }
                if (c == '&') {
                    // &#160; &nbsp; 등은 공백으로, &quot; &#8220; 는 따옴표로
                    val e = xhtml.indexOf(';', k)
                    if (e < 0 || e - k > 10) { first = c; break }
                    val ent = xhtml.substring(k + 1, e)
                    val ch = when {
                        ent == "nbsp" || ent == "#160" || ent == "#xA0" || ent == "#xa0" -> null
                        ent == "quot" -> '"'
                        ent == "ldquo" -> '“'
                        ent == "lsquo" -> '‘'
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.toChar()
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.toChar()
                        else -> '&'
                    }
                    if (ch == null || ch == '\u00A0' || ch == ' ' || ch == '\u3000') { k = e + 1; continue }
                    first = ch; break
                }
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000' || c == '\u00A0') { k++; continue }
                first = c; break
            }
            if (first != null && starts(first.toString())) sb.append(withClass(tag, "ash-dlg")) else sb.append(tag)
            i = end + 1
        }
        return sb.toString()
    }

    private fun withClass(tag: String, cls: String): String {
        val m = Regex("\\sclass\\s*=\\s*([\"'])(.*?)\\1").find(tag)
        return if (m != null) tag.replaceRange(m.range, " class=${m.groupValues[1]}${m.groupValues[2]} $cls${m.groupValues[1]}")
        else tag.substring(0, 2) + " class=\"$cls\"" + tag.substring(2)
    }
}

/**
 * TXT(정리 끝난 글) → XHTML(F-33 목차·F-40 대화문 들여쓰기·F-39 하이픈). 줄 하나 = 문단 하나, 빈 줄 = 빈 문단.
 * 찾은 제목은 <h2>/<h3> 로 바꿔 MuPDF 가 목차(outline)를 만든다.
 */
object TxtXhtml {
    fun convert(text: String, title: String, mask: Int, custom: String, hyphenLang: Boolean): String {
        val lines = text.split('\n')
        val heads = TxtToc.find(lines, mask, custom).associateBy { it.line }
        val sb = StringBuilder(text.length + text.length / 4 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"")
        if (hyphenLang) sb.append(" lang=\"en\"")
        sb.append("><head><title>").append(esc(title)).append("</title></head><body>\n")
        var n = 0
        for ((i, line) in lines.withIndex()) {
            val h = heads[i]
            if (h != null) {
                val tag = if (h.depth == 0) "h2" else "h3"
                sb.append('<').append(tag).append(" id=\"ash-t").append(n++).append("\">").append(esc(h.title)).append("</").append(tag).append(">\n")
                continue
            }
            if (line.isBlank()) { sb.append("<p class=\"ash-e\">&#160;</p>\n"); continue }
            sb.append(if (Dialogue.starts(line)) "<p class=\"ash-dlg\">" else "<p>")
            // 줄 앞 빈칸은 HTML 에서 사라지므로 붙는 빈칸으로(TXT 정리 '줄 앞 공백 정리'를 끈 경우)
            var k = 0
            while (k < line.length && (line[k] == ' ' || line[k] == '\t')) { sb.append(if (line[k] == '\t') "&#160;&#160;&#160;&#160;" else "&#160;"); k++ }
            sb.append(esc(line.substring(k).trimEnd('\r')))
            sb.append("</p>\n")
        }
        sb.append("</body></html>\n")
        return sb.toString()
    }

    /** XML 특수문자와 XML 에 쓸 수 없는 제어 문자를 처리한다. */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> if ((c < ' ' && c != '\t') || c == '\uFFFE' || c == '\uFFFF') { /* 버림 */ } else sb.append(c)
            }
        }
        return sb.toString()
    }
}
