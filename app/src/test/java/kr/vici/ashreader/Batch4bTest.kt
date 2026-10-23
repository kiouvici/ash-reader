// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatDetector
import kr.vici.ashreader.data.TapLayout
import kr.vici.ashreader.doc.DjvuWord
import kr.vici.ashreader.doc.TxtClean
import kr.vici.ashreader.doc.djvuTextPage
import kr.vici.ashreader.tts.Sentences
import kr.vici.ashreader.ui.LightMath
import kr.vici.ashreader.ui.TapAction
import kr.vici.ashreader.ui.TapZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4B 읽기·듣기: TXT 정리(F-41), 탭 영역(F-64), 문장 나누기(F-13), 밝기·색온도 시각(F-42), DjVu 글자층, 문서 ZIP(S-46). */
class Batch4bTest {
    @Test fun txtCleanBlankJoinTrim() {
        val src = "　첫째 줄은 끊겨\n이어진다.\n\n\n\n  둘째 문단.\n“대화문”\n다음"
        assertEquals(src, TxtClean.clean(src, 0, false, false))
        assertEquals("첫째 줄은 끊겨 이어진다.\n\n둘째 문단.\n“대화문”\n다음", TxtClean.clean(src, 1, true, true))
        assertEquals("첫째 줄은 끊겨 이어진다.\n둘째 문단.\n“대화문”\n다음", TxtClean.clean(src, 2, true, true))
        // 합치기 끔: 줄은 그대로, 빈 줄만 하나로
        assertEquals("첫째 줄은 끊겨\n이어진다.\n\n둘째 문단.\n“대화문”\n다음", TxtClean.clean(src, 1, false, true))
        assertEquals("b1jt", TxtClean.tag(1, true, true))
    }

    @Test fun tapZonesLayouts() {
        // 좌우(기본): 왼쪽 30% 이전 / 가운데 메뉴 / 오른쪽 다음
        assertEquals(TapAction.PREV, TapZones.action(0.1f, 0.5f, TapLayout.SIDES, "", leftHanded = false, rtl = false))
        assertEquals(TapAction.MENU, TapZones.action(0.5f, 0.5f, TapLayout.SIDES, "", false, false))
        assertEquals(TapAction.NEXT, TapZones.action(0.9f, 0.5f, TapLayout.SIDES, "", false, false))
        // 오→왼: 왼쪽 = 다음, 왼손잡이와 겹치면 원래대로
        assertEquals(TapAction.NEXT, TapZones.action(0.1f, 0.5f, TapLayout.SIDES, "", false, rtl = true))
        assertEquals(TapAction.PREV, TapZones.action(0.1f, 0.5f, TapLayout.SIDES, "", leftHanded = true, rtl = true))
        // L자: 위 띠 = 이전, 가운데 상자 = 메뉴, 오른쪽 아래 = 다음
        assertEquals(TapAction.PREV, TapZones.action(0.9f, 0.1f, TapLayout.L_SHAPE, "", false, false))
        assertEquals(TapAction.MENU, TapZones.action(0.5f, 0.5f, TapLayout.L_SHAPE, "", false, false))
        assertEquals(TapAction.NEXT, TapZones.action(0.9f, 0.9f, TapLayout.L_SHAPE, "", false, false))
        // 어디든 다음
        assertEquals(TapAction.NEXT, TapZones.action(0.1f, 0.1f, TapLayout.ANYWHERE, "", false, false))
        // 내 방식 3×3
        val custom = "nnnxmxppp"
        assertEquals(TapAction.NEXT, TapZones.action(0.1f, 0.1f, TapLayout.CUSTOM, custom, false, false))
        assertEquals(TapAction.NONE, TapZones.action(0.1f, 0.5f, TapLayout.CUSTOM, custom, false, false))
        assertEquals(TapAction.PREV, TapZones.action(0.9f, 0.9f, TapLayout.CUSTOM, custom, false, false))
        assertEquals("pmnpmnpmn".replaceRange(0, 1, "n"), TapZones.cycle("pmnpmnpmn", 0))
        assertFalse(TapZones.hasMenu("pnxpnxpnx"))
        assertEquals(9, TapZones.grid(TapLayout.SIDES, "", false, false).size)
    }

    @Test fun sentencesSplit() {
        val t = "그날 밤은 비가 왔다. 창문을\n두드렸다! “정말?” 그래…\n끝"
        val s = Sentences.split(3, t)
        assertEquals(listOf("그날 밤은 비가 왔다.", "창문을 두드렸다!", "“정말?”", "그래…", "끝"), s.map { it.text })
        assertTrue(s.all { it.page == 3 && t.substring(it.start, it.end).replace('\n', ' ') == it.text })
        // 긴 문장은 끊는다
        val long = (1..80).joinToString(" ") { "낱말$it" } + "."
        assertTrue(Sentences.split(0, long).all { it.end - it.start <= Sentences.MAX })
        assertTrue(Sentences.split(0, "   \n ").isEmpty())
    }

    @Test fun lightMath() {
        assertEquals(0.5f to 0, LightMath.split(0.5f))
        assertEquals(LightMath.MIN to 30, LightMath.split(-0.3f))
        assertEquals(-0.3f, LightMath.level(LightMath.MIN, 30), 0.0001f)
        // 밤을 넘기는 시간대(21:00~07:00)
        assertTrue(LightMath.inWindow(23 * 60, 21 * 60, 7 * 60))
        assertTrue(LightMath.inWindow(6 * 60, 21 * 60, 7 * 60))
        assertFalse(LightMath.inWindow(12 * 60, 21 * 60, 7 * 60))
        assertTrue(LightMath.inWindow(12 * 60, 9 * 60, 18 * 60))
    }

    @Test fun djvuWordsToTextPage() {
        val tp = djvuTextPage(listOf(DjvuWord(0.1f, 0.1f, 0.3f, 0.15f, "안녕", false), DjvuWord(0.35f, 0.1f, 0.5f, 0.15f, "세상", true), DjvuWord(0.1f, 0.2f, 0.2f, 0.25f, "끝", true)), 1.4f)
        assertEquals("안녕 세상\n끝\n", tp.text)
        assertEquals(tp.text.length * 4, tp.boxes.size)
        assertTrue(tp.hasBox(0) && !tp.hasBox(2) && tp.hasBox(3))
        assertEquals(0.2f, tp.r(0), 0.0001f)
    }

    @Test fun zipWithDocuments() {
        assertEquals(DocFormat.ZIP, FormatDetector.zipKindOf(listOf("소설/1권.txt", "소설/2권.epub", "readme.md"), null))
        // 그림이 반 이상이면 만화
        assertEquals(DocFormat.CBZ, FormatDetector.zipKindOf(listOf("01.jpg", "02.jpg", "a.txt"), null))
        // 맥 압축 찌꺼기·숨은 파일·안의 ZIP 은 문서로 치지 않음
        assertEquals(null, FormatDetector.zipKindOf(listOf("__MACOSX/._a.txt", ".hidden.pdf", "inner.zip"), null))
        assertTrue(FormatDetector.isInnerDoc("폴더/문서.hwp"))
        assertFalse(FormatDetector.isInnerDoc("폴더/"))
    }
}
