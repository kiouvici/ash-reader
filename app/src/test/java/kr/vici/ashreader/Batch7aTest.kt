// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.IntentInbox
import kr.vici.ashreader.data.OnceRefs
import kr.vici.ashreader.doc.PageFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배치 7A: eBook 보기 가장자리(I-711), 받은 문서 처리(I-714). 칸 단위 밀기(I-717)는 칸 단위 보기를 없애며 뺐다(작업 03). */
class Batch7aTest {
    // ---------------- I-711 eBook 보기 쪽 가장자리 ----------------

    @Test fun frameReservesStatusCutoutFooterAndNav() {
        // S26U 세로: 상태 표시줄 40dp, 카메라 구멍 44dp, 제스처 막대 24dp, 정보줄 아래
        val f = PageFrame.of(40f, 44f, 0f, 0f, 0f, 24f, 0f, 0f, infoShown = true, infoTop = false, fontScale = 1f)
        assertEquals(44f, f.top, 0.01f)
        val info = PageFrame.infoLineDp(1f) + PageFrame.INFO_GAP_DP
        assertEquals(24f + info, f.bottom, 0.01f)
        assertEquals(0f, f.left, 0.01f)
        // 정보줄을 위에 두면 위쪽이 커지고 아래는 막대만
        val t = PageFrame.of(40f, 44f, 0f, 0f, 0f, 24f, 0f, 0f, infoShown = true, infoTop = true, fontScale = 1f)
        assertEquals(44f + info, t.top, 0.01f)
        assertEquals(24f, t.bottom, 0.01f)
    }

    @Test fun landscapeSideCutoutAndNavBar() {
        // 가로: 카메라 구멍 왼쪽 44dp, 3버튼 내비게이션 줄 오른쪽 48dp
        val f = PageFrame.of(24f, 0f, 0f, 44f, 0f, 0f, 0f, 48f, infoShown = true, infoTop = false, fontScale = 1.3f)
        assertEquals(44f, f.left, 0.01f)
        assertEquals(48f, f.right, 0.01f)
        assertEquals(24f, f.top, 0.01f)
        // 글자 크기 배율이 크면 정보줄 몫도 커진다
        assertTrue(f.bottom > PageFrame.infoLineDp(1f))
    }

    @Test fun paginationHeightExcludesFrameAndMargins() {
        val f = PageFrame(44f, 60f, 0f, 0f)
        // 화면 높이 900dp, 위아래 여백 2.4em(글자 18) → 글자 높이 = 900 - 44 - 60 - 2×43.2
        assertEquals(900f - 44f - 60f - 2 * 43.2f, PageFrame.contentHeight(900f, f, 24, 18f), 0.01f)
    }

    @Test fun pageCssPutsVerticalMarginsOnEveryPage() {
        val css = PageFrame.css(PageFrame(44f, 60f, 10f, 12f), 24, 20, 18f)
        // @page 여백 = 가장자리 몫 + 사용자 위아래 여백(2.4em × 18 = 43.2)
        assertTrue(css, css.contains("@page { margin: 87.2px 12.0px 103.2px 10.0px !important; }"))
        // 본문은 좌우 사용자 여백만(위아래 0 — 장 첫 쪽에만 붙던 여백 제거)
        assertTrue(css, css.contains("body { margin: 0 2.00em 0 2.00em !important; }"))
    }

    // ---------------- I-714 받은 문서 처리 ----------------

    @Test fun inboxReopensSameFileAgain() {
        val saved = ArrayList<Pair<IntentInbox.Item<String>?, Long>>()
        val box = IntentInbox<String>(null, 0L) { i, d -> saved += i to d }
        val a = box.offer(listOf("content://kakao/a.hwp"))!!
        assertEquals(a, box.state.value)
        box.done(a.seq)
        assertNull(box.state.value)
        // 같은 파일을 다시 '열기' → 새 번호로 다시 처리
        val b = box.offer(listOf("content://kakao/a.hwp"))!!
        assertTrue(b.seq > a.seq)
        assertEquals(b, box.state.value)
        assertNull(box.offer(emptyList()))
    }

    @Test fun inboxKeepsPendingUntilDoneAndSurvivesRestore() {
        var store: Pair<IntentInbox.Item<String>?, Long> = null to 0L
        val box = IntentInbox<String>(null, 0L) { i, d -> store = i to d }
        val a = box.offer(listOf("x"))!!
        // 처리 중 Activity 재생성(같은 SavedState 로 복원) → 아직 처리할 것이 남아 있다
        val restored = IntentInbox(store.first, store.second) { i, d -> store = i to d }
        assertEquals(a, restored.state.value)
        restored.done(a.seq)
        // 끝낸 것을 다시 복원해도 또 열지 않는다(예전 문서가 다시 열리던 문제)
        val again = IntentInbox(a, store.second) { _, _ -> }
        assertNull(again.state.value)
        // 새 번호는 끝낸 번호보다 크다
        assertTrue(again.offer(listOf("y"))!!.seq > a.seq)
    }

    @Test fun inboxNewerItemIsNotClearedByOlderDone() {
        val box = IntentInbox<String>(null, 0L) { _, _ -> }
        val a = box.offer(listOf("a"))!!
        val b = box.offer(listOf("b"))!!
        box.done(a.seq) // 앞 것 처리가 늦게 끝나도
        assertEquals(b, box.state.value) // 새로 받은 b 는 남는다
        box.done(b.seq)
        assertNull(box.state.value)
    }

    @Test fun onceRefsProtectReopenedCopy() {
        val r = OnceRefs()
        r.hold("abc.hwp")          // 처음 연 사본
        r.hold("abc.hwp")          // 닫기 전에 같은 문서를 다시 받음
        assertFalse(r.release("abc.hwp")) // 앞 화면 닫기 → 아직 쓰는 중이라 지우지 않음
        assertTrue(r.isLive("abc.hwp"))
        assertEquals(setOf("abc"), r.ids())
        assertTrue(r.release("abc.hwp"))  // 마지막 화면 닫기 → 지워도 됨
        assertFalse(r.isLive("abc.hwp"))
        assertTrue(r.release("never-held.pdf")) // 잡은 적 없는 것(압축 안 문서 등)은 예전처럼 지운다
        r.hold("tmp-1")
        assertTrue(r.ids().isEmpty())
    }
}
