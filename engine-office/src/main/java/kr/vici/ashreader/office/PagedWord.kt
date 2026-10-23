// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.File
import java.util.Locale

/**
 * Word 원본(구조 보존) 보기(F-30)의 중간 결과: 구역(쪽 크기·여백·머리글/바닥글 종류)별 XHTML 조각과 그림·각주.
 * [writeTo] 가 쪽 묶음 폴더(paged.txt + *.xhtml + images/)를 쓰고, `:engine-mupdf` PagedComposer 가 이를 쪽으로 합성한다.
 *
 * 조각 XHTML 규칙
 *  - 글꼴 이름은 ashmj(명조)·ashgt(고딕)·serif·sans-serif 만 쓴다(@font-face 는 합성기 사용자 CSS 가 준다).
 *  - 쪽 나눔은 `<div class="pb"></div>`(MuPDF 는 page-break-after 만 지킨다).
 *  - 각주 참조는 `<a href="#ash-fn-ID">`, 떠 있는 그림 기준점은 `<a href="#ash-anc-ID">&#x2060;</a>`.
 *  - 머리글·바닥글의 쪽 번호는 {{ASH_PAGE}}, 전체 쪽 수는 {{ASH_PAGES}}.
 *  - 문단 앞 간격은 `<a href="#ash-sb-pt">`, 머리글·바닥글·각주 끝은 `<a href="#ash-end">` 표지로 합성기에 알린다.
 */
class PagedWord(
    val sections: List<Section>,
    /** 각주(참조 순서) id → 조각. */
    val notes: List<Pair<String, String>>,
    val images: Map<String, ByteArray>,
    val stats: Stats,
) {
    class Section(
        val pageW: Float, val pageH: Float,
        val top: Float, val right: Float, val bottom: Float, val left: Float,
        val headerDist: Float, val footerDist: Float,
        val titlePg: Boolean, val evenOdd: Boolean,
        /** 쪽 번호 시작(-1 = 앞 구역에 이어서). */
        val pgStart: Int,
        val pgFmt: String,
        val body: String,
        /** "first" / "default" / "even" → 조각 */
        val headers: Map<String, String>,
        val footers: Map<String, String>,
        val overlays: List<Overlay>,
    )

    /** 글 뒤/앞 떠 있는 그림. relX: page|margin, relY: page|margin|paragraph. 좌표·크기 pt. */
    class Overlay(val id: String, val src: String, val x: Float, val y: Float, val w: Float, val h: Float, val relX: String, val relY: String, val behind: Boolean)

    data class Stats(
        val sections: Int, val paragraphs: Int, val tables: Int, val images: Int, val overlays: Int,
        val headers: Int, val footers: Int, val footnotes: Int, val tabParagraphs: Int, val pageBreaks: Int,
    )

    fun writeTo(dir: File) {
        dir.mkdirs()
        for ((name, data) in images) File(dir, name).apply { parentFile?.mkdirs() }.writeBytes(data)
        val man = StringBuilder("# ash-paged 1\n")
        if (notes.isNotEmpty()) {
            val sb = StringBuilder()
            for ((i, n) in notes.withIndex()) {
                sb.append("<div class=\"note\">").append(n.second).append("</div>").append(END).append('\n')
                if (i < notes.size - 1) sb.append(PB).append('\n')
            }
            File(dir, "notes.xhtml").writeText(wrap(sb.toString()))
            man.append("notes notes.xhtml ").append(notes.joinToString(",") { it.first }).append('\n')
        } else man.append("notes -\n")
        for ((i, s) in sections.withIndex()) {
            man.append("section\n")
            man.append("geom ").append(listOf(s.pageW, s.pageH, s.top, s.right, s.bottom, s.left, s.headerDist, s.footerDist).joinToString(" ") { f(it) }).append('\n')
            man.append("flags ").append(if (s.titlePg) 1 else 0).append(' ').append(if (s.evenOdd) 1 else 0).append(' ').append(s.pgStart).append(' ').append(s.pgFmt.ifEmpty { "decimal" }).append('\n')
            File(dir, "s$i-body.xhtml").writeText(wrap(s.body))
            man.append("body s$i-body.xhtml\n")
            for ((k, v) in s.headers) { File(dir, "s$i-h-$k.xhtml").writeText(wrap(v + END)); man.append("header $k s$i-h-$k.xhtml\n") }
            for ((k, v) in s.footers) { File(dir, "s$i-f-$k.xhtml").writeText(wrap(v + END)); man.append("footer $k s$i-f-$k.xhtml\n") }
            for (o in s.overlays) {
                man.append("overlay ").append(o.id).append(' ').append(o.src).append(' ')
                    .append(listOf(o.x, o.y, o.w, o.h).joinToString(" ") { f(it) }).append(' ').append(o.relX).append(' ').append(o.relY).append(' ').append(if (o.behind) 1 else 0).append('\n')
            }
            man.append("end\n")
        }
        File(dir, "paged.txt").writeText(man.toString())
    }

    companion object {
        const val PB = "<div class=\"pb\"></div>"
        /** 조각 끝 표지(머리글·바닥글·각주 높이 = 줄 상자 끝까지). */
        const val END = "<div style=\"font-size:0.1pt;line-height:0.1pt\"><a href=\"#ash-end\">&#x2060;</a></div>"
        /** 문단 앞 간격 표지: 쪽 첫 줄이 이 문단이면 Word 처럼 앞 간격을 쪽 위에 둔다. */
        fun sbMark(pt: Float) = "<a href=\"#ash-sb-" + f(pt) + "\">&#x2060;</a>"
        const val PAGE = "{{ASH_PAGE}}"
        const val PAGES = "{{ASH_PAGES}}"

        /** 조각 공통 CSS. 문단 간격은 margin(겹침 = Word 의 앞·뒤 간격 중 큰 쪽)으로 준다. */
        const val CSS = "@page{margin:0}html,body{margin:0;padding:0}body{font-family:ashmj;font-size:10pt;color:#000;word-break:keep-all}" +
            "p{margin:0;padding:0}div{margin:0;padding:0}div.pb{page-break-after:always;height:0}" +
            "table{border-collapse:collapse;table-layout:fixed;margin:0}td{padding:0;vertical-align:top}" +
            "a{color:#000;text-decoration:none}a.nr{vertical-align:super;font-size:0.65em}" +
            "table.tabs td{border:none}span.img-unsupported{background:#ddd;color:#555}"

        fun wrap(body: String): String =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><head><meta charset=\"UTF-8\"/><title>w</title>" +
                "<style>" + CSS + "</style></head><body>\n" + body + "\n</body></html>\n"

        fun f(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

        /** 사용자 CSS(글꼴). [myeongjo]·[myeongjoBold]·[gothic] = 글꼴 파일 절대 경로. */
        fun fontCss(myeongjo: String, myeongjoBold: String?, gothic: String, gothicBold: String?, symbols: String? = null): String = buildString {
            append("@font-face{font-family:ashmj;src:url(\"").append(myeongjo).append("\")}")
            if (myeongjoBold != null) append("@font-face{font-family:ashmj;font-weight:bold;src:url(\"").append(myeongjoBold).append("\")}")
            append("@font-face{font-family:ashgt;src:url(\"").append(gothic).append("\")}")
            if (gothicBold != null) append("@font-face{font-family:ashgt;font-weight:bold;src:url(\"").append(gothicBold).append("\")}")
            // 나눔 글꼴에 없는 기호(「」※○①→ 등)는 리디바탕으로
            if (symbols != null) append("@font-face{font-family:ashsym;src:url(\"").append(symbols).append("\")}")
        }

        /**
         * Word 한글 글꼴(바탕·굴림·맑은 고딕·HY 계열)은 한글 폭이 1em, 번들 나눔 글꼴은 0.94~0.95em 이다(실측).
         * 줄바꿈·쪽 나눔을 Word 와 맞추려고 한글·한자 묶음 글자 크기를 이만큼 키운다(줄 높이는 문단 값으로 고정).
         */
        const val CJK_SCALE = 1.05f

        /** 번들 나눔 글꼴에 없는 기호(실측: 괄호류·※·도형·원문자·화살표·별·로마 숫자·㈜ 등). */
        fun isNanumMissing(cp: Int): Boolean = cp in 0x3008..0x301F || cp == 0x203B || cp in 0x25A0..0x25FF || cp in 0x2460..0x24FF ||
            cp in 0x2190..0x21FF || cp in 0x2600..0x26FF || cp in 0x2160..0x217F || cp == 0x2015 || cp == 0xFF5E || cp == 0x223C || cp in 0x3200..0x32FF

        // ---------------- 글꼴 대응(F-29 대응표와 같은 규칙) ----------------
        private val GOTHIC = Regex("(?i)gothic|고딕|굴림|돋움|gulim|dotum|malgun|맑은|arial|calibri|helvetica|verdana|tahoma|segoe|sans|견고딕|나눔고딕|noto sans|pretendard|헤드라인|jung|중고딕|consolas|courier|휴먼둥근|함초롬돋움|spoqa")
        private val KOREAN_NAME = Regex("[가-힣]|(?i)batang|gulim|dotum|gungsuh|malgun|hy[a-z]|myeongjo|gothic|nanum|haansoft|hcr|함초롬")

        /** 낱말 사이 공백이 0.5em 인 글꼴(한양 HY 계열, 굴림체·바탕체 같은 고정폭 '체'). */
        fun wideSpaceFont(font: String?): Boolean = font != null && (font.startsWith("HY", ignoreCase = true) || font.endsWith("체") || font.endsWith("Che", ignoreCase = true))

        fun isGothic(font: String?): Boolean = font != null && GOTHIC.containsMatchIn(font)
        fun isKoreanFont(font: String?): Boolean = font != null && KOREAN_NAME.containsMatchIn(font)

        /** 한글·한자가 든 글자 묶음의 CSS 글꼴. */
        fun cjkFamily(font: String?): String = if (isGothic(font)) "ashgt" else "ashmj"

        /** 라틴 글자 묶음의 CSS 글꼴: 한국어 글꼴이면 같은 번들 글꼴, 아니면 MuPDF 내장(Times·Helvetica 계열 — Word 글자 폭과 가깝다). */
        fun latinFamily(font: String?): String = when {
            font == null -> "serif"
            isKoreanFont(font) -> cjkFamily(font)
            isGothic(font) -> "sans-serif"
            else -> "serif"
        }

        /** Word '한 줄' 줄 높이 배수(글꼴 상하 메트릭). 모르는 글꼴은 1.17. */
        fun lineFactor(font: String?): Float {
            val f = font?.lowercase() ?: return 1.17f
            return when {
                f.contains("malgun") || f.contains("맑은") -> 1.72f // Word 실측(real-04 기준 PDF: 10pt 한 줄 = 17.2pt)
                f.contains("calibri") -> 1.22f
                f.contains("cambria") -> 1.17f
                f.contains("times") || f.contains("arial") -> 1.15f
                f.contains("batang") || f.contains("바탕") || f.contains("gulim") || f.contains("굴림") || f.contains("dotum") || f.contains("돋움") || f.contains("gungsuh") || f.contains("궁서") -> 1.15f
                f.startsWith("hy") -> 1.5f // Word 실측(real-03 기준 PDF: HY신명조 9pt 한 줄 ≈ 13.5pt)
                f.contains("함초롬") || f.contains("hcr") -> 1.2f
                f.contains("nanum") || f.contains("나눔") -> 1.15f
                else -> 1.17f
            }
        }

        fun isCjk(cp: Int): Boolean = cp in 0x1100..0x11FF || cp in 0x3000..0x30FF || cp in 0x3130..0x318F || cp in 0x3200..0x33FF ||
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF ||
            cp in 0x2460..0x24FF || cp in 0x25A0..0x25FF || cp in 0x2600..0x26FF || cp in 0x20000..0x2FFFF

        /** 글자 폭 추정(em). 탭 위치 계산에만 쓴다. */
        fun estWidthEm(s: String): Float {
            var w = 0f
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i); i += Character.charCount(cp)
                w += when {
                    isCjk(cp) -> CJK_SCALE * 0.95f
                    cp == ' '.code -> 0.27f
                    cp in 'A'.code..'Z'.code -> 0.66f
                    cp in 'a'.code..'z'.code -> 0.48f
                    cp in '0'.code..'9'.code -> 0.52f
                    cp == '.'.code || cp == ','.code || cp == ':'.code || cp == ';'.code || cp == '\''.code || cp == '|'.code || cp == '!'.code -> 0.28f
                    cp == '('.code || cp == ')'.code || cp == '['.code || cp == ']'.code -> 0.33f
                    else -> 0.55f
                }
            }
            return w
        }

        /** 글자 묶음을 0 = 라틴, 1 = 한·중·일, 2 = 나눔에 없는 기호로 나눈다(공백·숫자는 앞 묶음에 붙임). */
        fun splitScripts(s: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            val cur = StringBuilder(); var curK: Int? = null
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i); i += Character.charCount(cp)
                val own = when { isNanumMissing(cp) -> 2; isCjk(cp) -> 1; else -> 0 }
                val neutral = cp == ' '.code || cp == 0xA0 || cp == 0x2060 || cp == 0x200B || (cp in '0'.code..'9'.code && curK == 1)
                val k = if (neutral && curK != null && curK != 2) curK else own
                if (curK != null && k != curK) { out += curK to cur.toString(); cur.setLength(0) }
                curK = k
                cur.appendCodePoint(cp)
            }
            if (cur.isNotEmpty()) out += (curK ?: 0) to cur.toString()
            return out
        }
    }
}
