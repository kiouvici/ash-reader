// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.Book
import kr.vici.ashreader.doc.Dialogue
import kr.vici.ashreader.doc.MarginCrop
import kr.vici.ashreader.doc.TxtToc
import kr.vici.ashreader.doc.TxtXhtml
import kr.vici.ashreader.ui.LibSort
import kr.vici.ashreader.ui.sortBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6A: TXT 목차(F-33), 대화문 표시(F-40), 여백 자르기 상자(F-24), 서재 정렬(F-03). */
class Batch6aTest {
    @Test
    fun txtTocFindsKoreanChapters() {
        val lines = listOf("프롤로그", "본문이다.", "", "제1장 시작", "글", "제 2 장", "글", "3화", "제일 좋아하는 것은 사과다.", "에필로그")
        val h = TxtToc.find(lines, TxtToc.ALL)
        assertEquals(listOf(0, 3, 5, 7, 9), h.map { it.line })
        assertTrue(h.all { it.depth == 0 })
    }

    @Test
    fun txtTocDepthWithParts() {
        val lines = listOf("제1부 봄", "제1장 바람", "글", "제2장 비", "제2부 여름", "제3장 볕")
        val h = TxtToc.find(lines, TxtToc.CHAPTER)
        assertEquals(listOf(0, 1, 1, 0, 1), h.map { it.depth })
    }

    @Test
    fun txtTocLatinAndNumbersAndMask() {
        val lines = listOf("Chapter 1", "text", "#1", "a", "#2", "b", "#3", "Chapter II: The Road")
        assertEquals(listOf(0, 2, 4, 6, 7), TxtToc.find(lines, TxtToc.ALL).map { it.line })
        assertEquals(listOf(0, 7), TxtToc.find(lines, TxtToc.LATIN).map { it.line })
        // 번호만 있는 줄이 둘뿐이면 제목으로 보지 않는다
        assertEquals(emptyList<Int>(), TxtToc.find(listOf("1.", "글", "2.", "글"), TxtToc.NUMBER).map { it.line })
        // 사용자 식
        assertEquals(listOf(1), TxtToc.find(listOf("글", "【외전】 겨울", "글"), 0, "^【.+】").map { it.line })
        assertFalse(TxtToc.validCustom("[abc"))
        assertTrue(TxtToc.find(listOf("글"), 0, "[abc").isEmpty())
    }

    @Test
    fun dialogueMarking() {
        assertTrue(Dialogue.starts("  “안녕.”"))
        assertTrue(Dialogue.starts("「네」"))
        assertFalse(Dialogue.starts("그가 말했다."))
        val x = "<p>본문</p><p class=\"x\"><span>“말”</span></p><p>&#8220;말&#8221;</p><pre>“</pre><p>&#160;\"말\"</p>"
        assertEquals(
            "<p>본문</p><p class=\"x ash-dlg\"><span>“말”</span></p><p class=\"ash-dlg\">&#8220;말&#8221;</p><pre>“</pre><p class=\"ash-dlg\">&#160;\"말\"</p>",
            Dialogue.mark(x),
        )
    }

    @Test
    fun txtXhtmlStructure() {
        val x = TxtXhtml.convert("제1장 시작\n“말”\n\n  본문 <b>&", "t", TxtToc.ALL, "", hyphenLang = false)
        assertTrue(x.contains("<h2 id=\"ash-t0\">제1장 시작</h2>"))
        assertTrue(x.contains("<p class=\"ash-dlg\">“말”</p>"))
        assertTrue(x.contains("<p class=\"ash-e\">&#160;</p>"))
        assertTrue(x.contains("<p>&#160;&#160;본문 &lt;b&gt;&amp;</p>"))
        assertFalse(x.contains("lang="))
        assertTrue(TxtXhtml.convert("a", "t", 0, "", hyphenLang = true).contains("lang=\"en\""))
        assertEquals("ab", TxtXhtml.esc("a\u0001b"))
    }

    @Test
    fun marginCropDetect() {
        val w = 100; val h = 140
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 30 until 110) for (x in 20 until 80) if ((x + y) % 3 == 0) px[y * w + x] = 0xFF000000.toInt()
        px[5 * w + 5] = 0xFF000000.toInt() // 먼지 점 하나는 무시
        val r = MarginCrop.detect(px, w, h)
        assertEquals(0.18f, r.l, 0.011f)
        assertEquals(0.80f + 0.02f, r.r, 0.011f)
        assertEquals(30f / 140 - 0.02f, r.t, 0.011f)
        assertEquals(110f / 140 + 0.02f, r.b, 0.011f)
        // 빈 쪽·꽉 찬 쪽은 자르지 않는다
        assertTrue(MarginCrop.isFull(MarginCrop.detect(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h)))
        assertTrue(MarginCrop.isFull(MarginCrop.detect(IntArray(w * h) { 0xFF000000.toInt() }, w, h)))
        assertEquals(1.414f * 0.5f / 0.25f, MarginCrop.aspect(1.414f, kr.vici.ashreader.doc.NRect(0.5f, 0.25f, 0.75f, 0.75f)), 1e-4f)
    }

    @Test
    fun librarySort() {
        fun b(id: String, title: String, size: Long, added: Long) = Book(id = id, title = title, format = "EPUB", path = "/$id", sizeBytes = size, addedAt = added)
        val l = listOf(b("1", "나무", 30, 2), b("2", "가방", 10, 3), b("3", "다리", 20, 1))
        assertEquals(listOf("가방", "나무", "다리"), sortBooks(l, LibSort.TITLE, false).map { it.title })
        assertEquals(listOf("다리", "나무", "가방"), sortBooks(l, LibSort.TITLE, true).map { it.title })
        assertEquals(listOf("나무", "다리", "가방"), sortBooks(l, LibSort.SIZE, true).map { it.title })
        assertEquals(listOf("다리", "나무", "가방"), sortBooks(l, LibSort.ADDED, false).map { it.title })
    }
}
