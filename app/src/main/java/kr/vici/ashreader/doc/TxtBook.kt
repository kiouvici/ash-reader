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

    /**
     * 사용자 식이 문서 하나에서 쓸 수 있는 시간(ReDoS 막기). 기본 200ms + 줄 수에 비례한 몫(줄당 20µs, 최대 2초).
     * 넘으면 사용자 식은 버리고 기본 모양으로만 찾는다.
     * (안드로이드 정규식은 ICU 네이티브라 CharSequence 로 끊을 수 없다 → 따로 도는 스레드에서 맞춰 보고 기한까지만 기다린다.
     *  위험한 모양(중첩 반복·역참조)은 [validCustom] 이 미리 막아, 버린 스레드도 오래 돌지 않는다.)
     */
    const val CUSTOM_BUDGET_MS = 200L
    private const val PROBE_BUDGET_MS = 100L
    private const val MAX_UNBOUNDED = 4

    /**
     * 느려질 수 있는 식의 모양을 막는다: 반복(*, +, {n,}, {n,m} 등 2번 이상)이 걸린 괄호 안에 또 반복(?, * …)이나 '|' 가 있는 식,
     * 역참조(\1, \k<…>), 끝없는 반복이 [MAX_UNBOUNDED]개 넘는 식.
     */
    internal fun riskyShape(re: String): Boolean {
        data class G(var inner: Boolean = false)
        val stack = ArrayList<G>()
        var lastGroup: G? = null // 바로 앞이 닫는 괄호면 그 괄호
        var unbounded = 0
        var i = 0
        fun markInner() { stack.forEach { it.inner = true } }
        while (i < re.length) {
            val c = re[i]
            var closed: G? = null
            when (c) {
                '\\' -> {
                    val n = re.getOrNull(i + 1)
                    if (n != null && (n in '1'..'9' || n == 'k')) return true
                    i += 2; lastGroup = null; continue
                }
                '[' -> {
                    // 문자 묶음: 닫는 ] 까지 건너뜀(맨 앞 ^· ] 와 \ 처리)
                    var k = i + 1
                    if (re.getOrNull(k) == '^') k++
                    if (re.getOrNull(k) == ']') k++
                    while (k < re.length && re[k] != ']') { if (re[k] == '\\') k++; k++ }
                    i = k + 1; lastGroup = null; continue
                }
                '(' -> { stack += G(); i++; if (re.getOrNull(i) == '?') { i++; if (re.getOrNull(i) == '<' && re.getOrNull(i + 1) != '=' && re.getOrNull(i + 1) != '!') { while (i < re.length && re[i] != '>') i++; i++ } }; lastGroup = null; continue }
                ')' -> { closed = stack.removeLastOrNull(); i++ }
                '|' -> { markInner(); i++ }
                '*', '+', '?', '{' -> {
                    var max = 1 // 반복 최대 횟수(끝없음 = Int.MAX_VALUE)
                    if (c == '{') {
                        val e = re.indexOf('}', i)
                        val body = if (e > i) re.substring(i + 1, e) else ""
                        val m = Regex("^(\\d+)(,(\\d*))?$").find(body)
                        if (m == null) { i++; lastGroup = null; continue } // 글자 그대로의 {
                        max = when {
                            m.groupValues[2].isEmpty() -> m.groupValues[1].toInt()
                            m.groupValues[3].isEmpty() -> Int.MAX_VALUE
                            else -> m.groupValues[3].toIntOrNull() ?: Int.MAX_VALUE
                        }
                        i = e + 1
                    } else { max = if (c == '?') 1 else Int.MAX_VALUE; i++ }
                    if (max == Int.MAX_VALUE || max > 60) unbounded++
                    if (max > 1 && lastGroup?.inner == true) return true
                    markInner()
                    // 게으른·소유 반복 표시(*? *+)는 건너뜀
                    if (re.getOrNull(i) == '?' || re.getOrNull(i) == '+') i++
                    lastGroup = null; continue
                }
                else -> i++
            }
            lastGroup = closed
            if (closed != null && closed.inner) markInner()
        }
        return unbounded > MAX_UNBOUNDED
    }

    /** 일부러 느리게 만드는 줄들(빠져나간 위험한 식을 시간으로 한 번 더 거른다). */
    private val probes = listOf("a", "1", "가", " ", "제", "【", "-", "#", ".").map { it.repeat(MAX_LEN - 1) + "\u0001" } +
        listOf("제1장 " + "가".repeat(MAX_LEN - 5), "Chapter " + "a1".repeat((MAX_LEN - 8) / 2))

    /** 식이 맞고, 위험한 모양이 아니고, 시험 줄들에서 [PROBE_BUDGET_MS] 안에 끝나는지(설정 화면·백업 가져오기). */
    fun validCustom(custom: String): Boolean {
        if (custom.isBlank()) return true
        if (custom.length > 200 || riskyShape(custom)) return false
        val r = runCatching { Regex(custom) }.getOrNull() ?: return false
        return matchAll(r, probes, PROBE_BUDGET_MS) != null
    }

    /** 틀렸거나 위험한 식은 빈 값으로(백업에서 들어온 값 등). */
    fun safeCustom(custom: String): String = custom.trim().take(200).takeIf { validCustom(it) }.orEmpty()

    /**
     * [lines] 각각에 [r] 이 맞는지 따로 도는 스레드에서 본다. [budgetMs] 안에 못 끝나면 null(스레드는 다음 줄에서 멈춘다).
     */
    private fun matchAll(r: Regex, lines: List<String>, budgetMs: Long): BooleanArray? {
        val out = BooleanArray(lines.size)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        val t = Thread({
            try {
                for ((k, l) in lines.withIndex()) {
                    if (stop.get()) return@Thread
                    out[k] = runCatching { r.containsMatchIn(l) }.getOrDefault(false)
                }
            } finally { done.countDown() }
        }, "ash-txt-toc")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
        val ok = done.await(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) { stop.set(true); return null }
        return out
    }

    /** [headings] = 찾은 제목, [customTimedOut] = 사용자 식이 시간을 넘겨(또는 위험한 모양이라) 기본 모양으로만 찾았음. */
    data class Found(val headings: List<Heading>, val customTimedOut: Boolean)

    fun find(lines: List<String>, mask: Int, custom: String = ""): List<Heading> = findEx(lines, mask, custom).headings

    fun findEx(lines: List<String>, mask: Int, custom: String = "", budgetMs: Long = CUSTOM_BUDGET_MS): Found {
        var user = custom.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
        var timedOut = false
        if (user != null && riskyShape(custom)) { user = null; timedOut = true }
        if ((mask and ALL) == 0 && user == null) return Found(emptyList(), timedOut)
        // 사용자 식은 제목 후보 줄(60자 이하)에만, 시간 기한을 두고 한꺼번에 맞춰 본다
        val trimmed = lines.map { it.trim().trim('\u3000') }
        var userHit: Map<Int, Boolean> = emptyMap()
        if (user != null) {
            val idx = trimmed.indices.filter { trimmed[it].isNotEmpty() && trimmed[it].length <= MAX_LEN }
            val budget = minOf(2_000L, budgetMs + idx.size / 50)
            val r = matchAll(user, idx.map { trimmed[it] }, budget)
            if (r == null) { user = null; timedOut = true } else userHit = idx.withIndex().associate { (k, li) -> li to r[k] }
        }
        if ((mask and ALL) == 0 && user == null) return Found(emptyList(), timedOut)
        data class Raw(val line: Int, val title: String, val top: Boolean, val numberOnly: Boolean)
        val raw = ArrayList<Raw>()
        for (i in lines.indices) {
            val l = trimmed[i]
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
            if (!hit && user != null && userHit[i] == true) hit = true
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
        return Found(kept.map { Heading(it.line, it.title, if (anyTop && !it.top) 1 else 0) }, timedOut)
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
    fun convert(text: String, title: String, mask: Int, custom: String, hyphenLang: Boolean, onCustomTimeout: () -> Unit = {}): String {
        val lines = text.split('\n')
        val found = TxtToc.findEx(lines, mask, custom)
        if (found.customTimedOut) onCustomTimeout()
        val heads = found.headings.associateBy { it.line }
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
