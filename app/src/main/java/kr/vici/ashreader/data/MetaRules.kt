// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import java.text.Normalizer

/**
 * 책 정보 찾기 규칙(작업 06, D-024). 안드로이드 API 없이 JVM 에서 시험한다.
 * - 1순위 파일 정보: 믿지 않는 값('Administrator'·'user'·'Microsoft Word - …'·PC 이름·빈 값·파일 이름과 같은 제목)은 버린다.
 * - 2순위 인터넷: 검색어 = 파일 이름 정리([출판사]·(완)·v1.2·스캔·해상도 등 지우고 권수는 따로).
 *   자동 적용은 일치도 90% 이상 + 권수 일치일 때만, 아니면 바꾸지 않고 '확인 필요'.
 */
object MetaRules {
    /** 자동으로 바꾸는 최소 일치도. */
    const val AUTO_MIN = 90

    /** 검색어: 정리한 제목 + 권수(없으면 0). 보내는 것은 이것뿐(책 내용·경로·파일 전체 이름 안 보냄). */
    data class Query(val title: String, val volume: Int)

    /** 인터넷 후보 하나. [source] = "국립중앙도서관"·"카카오"·"구글 북스". */
    data class Candidate(
        val title: String, val author: String, val publisher: String, val year: String, val isbn: String,
        val coverUrl: String, val source: String,
    )

    data class Match(val score: Int, val volumeOk: Boolean) {
        val auto: Boolean get() = score >= AUTO_MIN && volumeOk
    }

    private val badPeople = setOf(
        "administrator", "admin", "user", "users", "owner", "unknown", "anonymous", "author", "guest", "pc", "default",
        "사용자", "관리자", "작성자", "알 수 없음", "알수없음", "익명", "소유자", "hp", "samsung", "lg", "microsoft", "windows 사용자",
    )

    /** 파일 속 지은이를 믿어도 되나. */
    fun trustAuthor(a: String?): Boolean {
        val s = a?.trim().orEmpty()
        if (s.length < 2 || s.length > 80) return false
        val low = s.lowercase()
        if (low in badPeople) return false
        // PC 이름(DESKTOP-ABC123·LAPTOP-…·WIN-…) · 메일 주소 · 숫자만
        if (Regex("^(desktop|laptop|win|pc|user)[-_][a-z0-9]+$", RegexOption.IGNORE_CASE).matches(s)) return false
        if ('@' in s || s.all { it.isDigit() || it.isWhitespace() || it == '-' }) return false
        return true
    }

    /** 파일 속 제목을 믿어도 되나: 빈 값·'Microsoft Word - …'·'제목 없음'·파일 이름과 같은 값(정보가 아님)은 안 믿는다. */
    fun trustTitle(t: String?, fileTitle: String): Boolean {
        val s = t?.trim().orEmpty()
        if (s.length < 2 || s.length > 200) return false
        val low = s.lowercase()
        if (low.startsWith("microsoft word -") || low.startsWith("microsoft powerpoint -") || low.endsWith(".doc") || low.endsWith(".docx") ||
            low.endsWith(".pdf") || low.endsWith(".hwp") || low.endsWith(".indd") || low.endsWith(".qxd")
        ) return false
        if (low in setOf("untitled", "제목 없음", "제목없음", "unknown", "document", "문서", "title", "새 문서", "no title")) return false
        if (norm(s) == norm(fileTitle)) return false
        if (s.all { it.isDigit() || it.isWhitespace() || it in "-_." }) return false
        return true
    }

    /**
     * 파일 이름 → 검색어. [출판사]·(완)·v1.2·스캔·해상도·고화질 등 지우고 권수는 따로.
     * "[대원] 슬램덩크 03권 (완) 1200px" → ("슬램덩크", 3)
     */
    fun query(fileTitle: String): Query {
        var s = Normalizer.normalize(fileTitle, Normalizer.Form.NFC)
        s = s.replace('_', ' ').replace('+', ' ')
        // 괄호 속(출판사·스캔·완결·해상도·번역 등): 권수가 아닌 것은 지운다
        s = s.replace(Regex("[\\[【〔{][^\\]】〕}]*[\\]】〕}]"), " ")
        s = s.replace(Regex("\\((완|완결|完|end|fin|스캔|scan|고화질|저화질|hq|hd|c2c|digital|번역|한글판|한국어판|korean|eng|kor)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b(v|ver)\\.?\\s?\\d+(\\.\\d+)*\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b\\d{3,4}\\s?(px|dpi|p)\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("\\b(scan|scanned|c2c|hq|hd|epub|pdf|cbz|cbr|zip|rar)\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("(스캔본|스캔|고화질|저화질|완결|합본|전자책|이북)"), " ")
        // 권수: "03권"·"제3권"·"vol.3"·"Vol 3"·"#3"·"3화"·끝의 숫자
        var vol = 0
        val volPatterns = listOf(
            Regex("제?\\s?(\\d{1,4})\\s?(권|화|부|편)"),
            Regex("\\b(?:vol|volume|v|no|#)\\.?\\s?(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
            Regex("[\\s-]0*(\\d{1,4})\\s*$"),
            Regex("\\(\\s*0*(\\d{1,4})\\s*\\)\\s*$"),
        )
        for (p in volPatterns) {
            val m = p.find(s) ?: continue
            vol = m.groupValues[1].toIntOrNull() ?: 0
            s = s.removeRange(m.range)
            break
        }
        s = s.replace(Regex("\\(\\s*\\)"), " ").replace(Regex("[\\-–—·:,]+\\s*$"), " ")
        s = s.replace(Regex("\\s+"), " ").trim().trim('-', '.', ',', ' ')
        return Query(s.ifBlank { fileTitle.trim() }, vol)
    }

    /** 비교용: 소문자·NFC·띄어쓰기·문장부호 없앰. */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().filter { it.isLetterOrDigit() }

    /** 문자 두 개씩 묶음(bigram) 겹침(Dice) 0~100. 짧은 쪽이 긴 쪽 안에 통째로 있으면 조금 깎아 인정. */
    fun similarity(a: String, b: String): Int {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0
        if (x == y) return 100
        fun grams(s: String) = if (s.length == 1) listOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }
        val gx = grams(x); val gy = grams(y).toMutableList()
        var hit = 0
        for (g in gx) { val i = gy.indexOf(g); if (i >= 0) { hit++; gy.removeAt(i) } }
        val dice = (200 * hit / (gx.size + grams(y).size)).coerceIn(0, 100)
        val (short, long) = if (x.length <= y.length) x to y else y to x
        // "데미안" ⊂ "데미안: 에밀 싱클레어의 청소년 시절" — 부제가 붙은 같은 책. 길이 차이만큼만 깎는다
        val contained = if (short.length >= 2 && long.contains(short)) (100 - 30 * (long.length - short.length) / long.length).coerceIn(70, 95) else 0
        return maxOf(dice, contained)
    }

    /** 후보 제목에서 권수(없으면 0). */
    fun volumeOf(title: String): Int = query(title).volume

    /** 검색어와 후보의 일치도. 권수: 둘 다 없거나 같으면 맞음. */
    fun match(q: Query, c: Candidate): Match {
        val cq = query(c.title)
        val score = similarity(q.title, cq.title)
        val volOk = q.volume == cq.volume || (q.volume == 0 && cq.volume == 1)
        return Match(score, volOk)
    }

    /** 일치도 순 정렬(같으면 표지 있는 것 먼저). */
    fun rank(q: Query, list: List<Candidate>): List<Pair<Candidate, Match>> =
        list.map { it to match(q, it) }.sortedWith(compareByDescending<Pair<Candidate, Match>> { it.second.score }.thenByDescending { it.first.coverUrl.isNotEmpty() })

    /** ISBN-10/13 만 남김(숫자·X). 아니면 "". */
    fun isbn(s: String?): String {
        val d = s.orEmpty().filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
        return if (d.length == 10 || d.length == 13) d else ""
    }
}
