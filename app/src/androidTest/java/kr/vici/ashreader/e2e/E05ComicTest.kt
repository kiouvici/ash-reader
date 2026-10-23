// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 만화: CBZ(합성) 칸 단위 보기·쪽 넘김 보기·웹툰 보기·쪽 목록·만화 설정 탭, AI 화질(에뮬레이터는 CPU 라 켜지기만 보고 짧게 기다림),
 * 그림으로 된 PDF → '만화로 보기' → 다시 PDF.
 */
@RunWith(AndroidJUnit4::class)
class E05ComicTest : E2eTest() {
    private val cbz = "e2e-cbz 만화"

    private fun openComic() {
        launchApp()
        openFromLibrary(cbz, "CBZ")
        waitPage()
        dismissTapGuide()
        SystemClock.sleep(800)
    }

    /** 읽기 메뉴 → 보기(만화 설정 시트). */
    private fun openComicSheet() {
        openMenu()
        clickText("보기")
        assertTrue("만화 설정이 열리지 않음", has(By.text("만화 설정"), 8_000))
    }

    private fun closeSheet() {
        // 측면 시트(태블릿 가로)는 닫기 영역, 바텀시트는 '뒤로' — 쪽 화면이 다시 보일 때까지
        device.findObject(By.desc("만화 설정 닫기"))?.click()
        SystemClock.sleep(600)
        backToPage()
        closeMenu()
    }

    @Test
    fun panelView_pageView_grid() {
        openComic()
        shot("comic-open") // 기본: 칸 단위 보기(누르면 확대)
        val p0 = currentPage()
        tap(0.5f, 0.5f) // 칸 확대
        shot("panel-tap")
        tap(0.95f, 0.5f) // 다음 칸
        tap(0.95f, 0.5f)
        shot("panel-next")
        assertAlive()
        // 칸 단위 보기 끄기 → 쪽 넘김
        openComicSheet()
        shot("comic-sheet-turn")
        val sw = switchNear("칸 단위 보기")
        assertTrue("칸 단위 보기 스위치가 없음", sw != null)
        if (sw!!.isChecked) sw.click()
        SystemClock.sleep(600)
        closeSheet()
        waitPage()
        val a = currentPage()
        tap(0.9f, 0.5f)
        val b = waitPageChange(a, forward = true)
        shot("page-view-next")
        assertTrue("만화 쪽 넘김이 안 됨 ($a → $b)", a != null && b != null && b > a)
        // 쪽 목록(그리드)
        openMenu()
        clickText("쪽 목록")
        SystemClock.sleep(1_500)
        shot("page-grid")
        device.pressBack(); SystemClock.sleep(600)
        // 칸 단위 보기 '자동 순서'
        openComicSheet()
        switchNear("칸 단위 보기")?.let { if (!it.isChecked) it.click() }
        SystemClock.sleep(400)
        device.findObject(By.text("자동 순서"))?.click()
        closeSheet()
        waitPage()
        tap(0.95f, 0.5f)
        shot("panel-auto")
        assertAlive()
        android.util.Log.i(TAG, "만화 시작 쪽 $p0")
    }

    @Test
    fun webtoon_view_and_back() {
        openComic()
        openComicSheet()
        clickText("위→아래 · 웹툰")
        SystemClock.sleep(800)
        shot("sheet-webtoon")
        closeSheet()
        assertTrue("웹툰 보기가 아님", has(By.desc(Pattern.compile("웹툰 보기 \\d+쪽")), 15_000))
        shot("webtoon")
        repeat(3) { swipe(0.5f, 0.8f, 0.5f, 0.2f, 15) }
        shot("webtoon-scrolled")
        assertAlive()
        // 되돌리기: 왼→오
        openComicSheet()
        clickText("왼→오")
        closeSheet()
        waitPage()
        shot("back-to-ltr")
        assertAlive()
    }

    @Test
    fun viewTab_filters_noCrash() {
        openComic()
        openComicSheet()
        // 시트의 '보기' 탭(아래 메뉴의 '보기'와 이름이 같다 → '화질' 탭과 같은 줄에 있는 것)
        val y = obj(By.text("화질")).visibleBounds.centerY()
        device.findObjects(By.text("보기")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) }!!.click()
        SystemClock.sleep(500)
        for (t in listOf("폭", "화면")) { device.findObject(By.text(t))?.click(); SystemClock.sleep(400) }
        switchNear("흑백")?.click(); SystemClock.sleep(400)
        switchNear("색 반전")?.click(); SystemClock.sleep(400)
        scrollFind(By.text("고품질"))?.click()
        scrollFind(By.text("강"))?.click()
        shot("view-tab")
        // 되돌리기
        switchNear("흑백")?.click(); switchNear("색 반전")?.click()
        scrollFind(By.text("빠름"))?.click()
        scrollFind(By.text("끔"))?.click()
        closeSheet()
        waitPage()
        shot("after-view-tab")
        assertAlive()
    }

    @Test
    fun aiQuality_toggle_noCrash() {
        openComic()
        openComicSheet()
        clickText("화질")
        // 기기 확인(약 10초, 에뮬레이터는 더 걸릴 수 있음)
        val end = SystemClock.uptimeMillis() + 60_000
        var state = ""
        while (SystemClock.uptimeMillis() < end && state.isEmpty()) {
            when {
                device.hasObject(By.textContains("AI 화질 향상을 쓸 수 없어요")) -> state = "unavailable"
                device.hasObject(By.text("AI 화질 향상")) -> state = "available"
            }
            SystemClock.sleep(500)
        }
        shot("ai-$state")
        assertTrue("AI 화질 확인이 끝나지 않음", state.isNotEmpty())
        if (state == "available") {
            val sw = switchNear("AI 화질 향상")!!
            if (!sw.isChecked) {
                sw.click()
                device.wait(Until.findObject(By.text("켜기")), 3_000)?.click()
            }
            SystemClock.sleep(1_000)
            closeSheet()
            // CPU 처리 — 결과를 기다리지 않고 넘기기·메뉴가 살아 있는지만
            SystemClock.sleep(6_000)
            tap(0.95f, 0.5f)
            SystemClock.sleep(3_000)
            shot("ai-on")
            assertAlive()
            openComicSheet()
            clickText("화질")
            switchNear("AI 화질 향상")?.let { if (it.isChecked) it.click() }
            SystemClock.sleep(600)
            closeSheet()
        } else closeSheet()
        assertAlive()
    }

    @Test
    fun imagePdf_offersComic_toggleBack() {
        launchApp()
        openFromLibrary("e2e-pdf 그림", "PDF")
        waitPage()
        dismissTapGuide()
        assertTrue("그림 PDF 인데 '만화로 보기' 제안이 없음", has(By.text("그림으로 된 PDF예요"), 15_000))
        shot("image-pdf-offer")
        clickText("만화로 보기")
        waitPage()
        SystemClock.sleep(1_000)
        shot("image-pdf-comic")
        tap(0.95f, 0.5f)
        assertAlive()
        openMenu()
        val back = obj(By.desc("PDF 쪽 보기로"))
        back.click()
        waitPage()
        shot("image-pdf-back")
        assertAlive()
    }

    @Test
    fun comicPageMenu_longPress() {
        openComic()
        // 칸 보기에서도 길게 누르면 쪽 메뉴(표지로·공유·저장)
        longPress(0.5f, 0.5f)
        SystemClock.sleep(800)
        shot("comic-page-menu")
        assertAlive()
        device.pressBack()
        SystemClock.sleep(500)
        assertAlive()
    }
}
