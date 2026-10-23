// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word 원본(구조 보존) 보기 변환기(F-30): DOCX·RTF·DOC → 쪽 묶음. 쪽 합성(MuPDF)은 기기·호스트 측정에서 본다. */
class WordPagedTest {
    private fun wellFormed(pw: PagedWord) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        fun parse(x: String) = f.newDocumentBuilder().parse(PagedWord.wrap(x).byteInputStream())
        for (s in pw.sections) { parse(s.body); s.headers.values.forEach { parse(it) }; s.footers.values.forEach { parse(it) } }
        pw.notes.forEach { parse(it.second) }
    }

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), "").replace("&#x200B;", "").replace("&#x2060;", "")

    @Test fun publicDocxStructure() {
        val pw = DocxPaged().convert(TestFiles.public("word-structure.docx").readBytes())
        wellFormed(pw)
        assertEquals(1, pw.sections.size)
        val s = pw.sections[0]
        // Letter 세로, 여백 1in/1.25in, 첫 쪽 머리글 따로
        assertEquals(612f, s.pageW, 0.5f); assertEquals(792f, s.pageH, 0.5f)
        assertEquals(72f, s.top, 0.5f); assertEquals(90f, s.left, 0.5f)
        assertTrue(s.titlePg)
        assertEquals(setOf("first", "default"), s.headers.keys)
        assertEquals(setOf("first", "default"), s.footers.keys)
        assertEquals(3, pw.stats.tables)
        assertEquals(2, pw.stats.images)
        assertEquals(1, pw.notes.size)
        assertEquals(1, pw.stats.pageBreaks)
        assertTrue(s.body.contains("href=\"#ash-fn-"))
        assertTrue(s.body.contains(PagedWord.PB))
        // 본문 글자 순서: 이북식 변환기와 같은 문단 순서
        val t = text(s.body).replace(Regex("\\s+"), "")
        var at = 0
        for (para in DocxConverter().convert(TestFiles.public("word-structure.docx").readBytes()).stats.paragraphTexts) {
            val k = t.indexOf(para.replace(Regex("\\s+"), "").take(8), at)
            assertTrue("문단 순서: $para", k >= 0)
            at = k
        }
        // 병합 표: colspan/rowspan 유지
        assertTrue(s.body.contains("rowspan=\"3\""))
        assertTrue(s.body.contains("colspan=\"2\" rowspan=\"2\""))
    }

    @Test fun syntheticDocxFeatures() {
        val pw = DocxPaged().convert(SyntheticDocx.build())
        wellFormed(pw)
        assertEquals(2, pw.sections.size)
        val (a, b) = pw.sections
        assertEquals(595.3f, a.pageW, 0.5f); assertTrue(b.pageW > b.pageH) // 둘째 구역 가로
        assertEquals(10, b.pgStart)
        assertEquals(42.55f, a.headerDist, 0.1f)
        assertTrue(a.titlePg)
        assertTrue(a.headers.getValue("first").contains("첫 쪽 머리글"))
        assertTrue(a.headers.getValue("default").contains(PagedWord.PAGE))
        val f = a.footers.getValue("default")
        assertTrue(f.contains(PagedWord.PAGE) && f.contains(PagedWord.PAGES))
        assertTrue("필드 결과(캐시 값)는 빠진다", !text(f).contains("3 /"))
        // 둘째 구역은 머리글을 앞 구역에서 이어받는다
        assertTrue(b.headers.containsKey("default"))
        // 오른쪽 탭 + 점 채움 → 탭 표
        assertTrue(a.body.contains("class=\"tabs\""))
        assertTrue(a.body.contains("....."))
        // 번호 목록(내어쓰기 = 탭 표 두 칸)
        assertTrue(text(a.body).contains("1.첫째 항목") || text(a.body).contains("1. 첫째 항목"))
        assertTrue(text(a.body).contains("3."))
        // 표: 가로 병합 + 음영
        assertTrue(a.body.contains("colspan=\"2\""))
        assertTrue(a.body.contains("background-color:#D9D9D9"))
        // 글 뒤 그림 = 쪽 위 절대 위치(300pt, 200pt), 둘러싸기 그림 = 블록
        assertEquals(1, a.overlays.size)
        val o = a.overlays[0]
        assertEquals("page", o.relX); assertEquals("page", o.relY); assertTrue(o.behind)
        assertEquals(300f, o.x, 0.5f); assertEquals(200f, o.y, 0.5f); assertEquals(100f, o.w, 0.5f)
        assertTrue(a.body.contains("href=\"#ash-anc-${o.id}\""))
        assertEquals(3, pw.stats.images)
        // 각주·쪽 나눔
        assertEquals(1, pw.notes.size)
        assertTrue(text(pw.notes[0].second).contains("합성 각주 본문"))
        assertEquals(1, pw.stats.pageBreaks)
    }

    @Test fun manifestRoundTrip() {
        val dir = Files.createTempDirectory("ash-paged").toFile()
        try {
            DocxPaged().convert(SyntheticDocx.build()).writeTo(dir)
            val m = File(dir, "paged.txt").readLines()
            assertEquals(2, m.count { it == "section" })
            assertTrue(m.any { it.startsWith("notes notes.xhtml ") })
            assertTrue(m.any { it.startsWith("overlay o0 images/image1.png 300.00 200.00 100.00 50.00 page page 1") })
            assertTrue(m.any { it.startsWith("flags 0 0 10 ") })
            assertTrue(File(dir, "images/image1.png").isFile)
            assertTrue(File(dir, "s0-f-default.xhtml").readText().contains(PagedWord.PAGES))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rtfNamedAsDoc() {
        val pw = RtfPaged.convert(TestFiles.public("rtf-named-as.doc").readBytes())
        wellFormed(pw)
        assertTrue(pw.sections.isNotEmpty())
        assertTrue(pw.stats.paragraphs > 0)
        val plain = RtfConverter.convert(TestFiles.public("rtf-named-as.doc").readBytes()).paragraphTexts
        val t = text(pw.sections.joinToString { it.body }).replace(Regex("\\s+"), "")
        for (p in plain.take(10)) assertTrue("RTF 문단: $p", t.contains(p.replace(Regex("\\s+"), "").take(6)))
    }

    @Test fun rtfFormattingAndTables() {
        val rtf = "{\\rtf1\\ansi\\ansicpg949\\deff0{\\fonttbl{\\f0\\fnil\\fcharset129 Batang;}{\\f1\\fswiss Arial;}}{\\colortbl;\\red255\\green0\\blue0;}" +
            "\\paperw11906\\paperh16838\\margl1000\\margr1000\\margt1200\\margb1200" +
            "{\\header \\pard\\qr\\f0\\fs18 Head {\\field{\\*\\fldinst PAGE}{\\fldrslt 1}}\\par}" +
            "\\pard\\qc\\sb240\\sa120\\f0\\fs32\\b \\'c1\\'a6\\'b8\\'f1\\b0\\par" +
            "\\pard\\qj\\li720\\fi-360\\f1\\fs20\\cf1 Red\\cf0  text\\tab tab{\\footnote \\pard\\fs16 {\\chftn} note body\\par}\\par" +
            "\\trowd\\clbrdrt\\brdrs\\brdrw10\\clbrdrb\\brdrs\\brdrw10\\clcbpat1\\cellx3000\\clvmgf\\cellx6000" +
            "\\pard\\intbl A1\\cell B1\\cell\\row" +
            "\\trowd\\cellx3000\\clvmrg\\cellx6000\\pard\\intbl A2\\cell\\cell\\row" +
            "\\pard after\\page next page\\par}"
        val pw = RtfPaged.convert(rtf.toByteArray(Charsets.ISO_8859_1))
        wellFormed(pw)
        val s = pw.sections.single()
        assertEquals(595.3f, s.pageW, 0.5f); assertEquals(50f, s.left, 0.1f); assertEquals(60f, s.top, 0.1f)
        assertTrue(s.body.contains("제목")) // CP949 \'hh
        assertTrue(s.body.contains("text-align:center"))
        assertTrue(s.body.contains("color:#FF0000"))
        assertTrue(s.body.contains("class=\"tabs\""))
        assertTrue(s.body.contains("rowspan=\"2\""))
        assertTrue(s.body.contains("background-color:#FF0000"))
        assertTrue(s.body.contains(PagedWord.PB))
        assertTrue(s.headers.getValue("default").contains(PagedWord.PAGE))
        assertEquals(1, pw.notes.size)
        assertTrue(text(pw.notes[0].second).contains("note body"))
    }

    @Test fun doc97Structure() {
        val pw = DocPaged.convert(TestFiles.public("word97-structure.doc").readBytes())
        wellFormed(pw)
        assertTrue(pw.sections.isNotEmpty())
        assertTrue(pw.sections[0].pageW > 500f)
        assertTrue(pw.stats.tables >= 2)
        val t = text(pw.sections.joinToString { it.body })
        for (p in DocExtractor.convert(TestFiles.public("word97-structure.doc").readBytes()).paragraphTexts.take(15)) {
            assertTrue("DOC 문단: $p", t.replace(Regex("\\s+"), "").contains(p.replace(Regex("\\s+"), "").take(6)))
        }
        println("[doc97] ${pw.stats}")
    }

    @Test fun tabTableAndCjkScale() {
        val out = StringBuilder()
        WordHtml.para(
            WordHtml.Para(
                listOf(WordHtml.Item.Text(WordHtml.Run("제1조", null, "바탕", 10f)), WordHtml.Item.Tab, WordHtml.Item.Text(WordHtml.Run("목적", null, "바탕", 10f))),
                left = 40f, firstLine = -40f,
            ),
            400f, out,
        )
        val h = out.toString()
        assertTrue(h.contains("class=\"tabs\""))
        assertTrue(h.contains("margin-left:0pt"))
        // 한글 묶음은 1em 글꼴 폭에 맞춰 키운다
        assertTrue(h.contains("font-size:10.5pt"))
    }
}
