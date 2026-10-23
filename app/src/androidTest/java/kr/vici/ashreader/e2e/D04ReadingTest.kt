// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 읽기 화면(EPUB 합성 책): 탭 안내, 탭 영역·밀기·종이책(curl) 넘김, 넘김 효과 바꾸기, 회전, 보기 설정 시트, 듣기(TTS),
 * 본문 검색, 목차, 책갈피·메모·형광펜.
 */
@RunWith(AndroidJUnit4::class)
class D04ReadingTest : E2eTest() {
    private val book = "e2e-epub 소설"

    private fun openBook() {
        launchApp()
        openFromLibrary(book, "EPUB")
        waitPage()
        dismissTapGuide()
        SystemClock.sleep(600)
    }

    @Test
    fun tapGuide_tapZones_swipe_curl() {
        resetPrefs(tapGuideShown = false)
        launchApp()
        openFromLibrary(book, "EPUB")
        waitPage()
        assertTrue("탭 안내가 뜨지 않음", has(By.text("화면을 누르는 곳에 따라 쪽을 넘겨요"), 8_000))
        shot("tap-guide")
        clickText("알겠어요")
        val p0 = page()
        // 오른쪽 = 다음, 왼쪽 = 이전(좌우 배치 기본)
        tap(0.9f, 0.5f)
        val p1 = waitPageChange(p0, forward = true)!!
        shot("tap-next")
        assertTrue("오른쪽 탭으로 넘어가지 않음 ($p0 → $p1)", p1 > p0)
        tap(0.1f, 0.5f)
        val p2 = waitPageChange(p1, forward = false)!!
        shot("tap-prev")
        assertTrue("왼쪽 탭으로 돌아오지 않음 ($p1 → $p2)", p2 < p1)
        // 밀어서 넘기기
        swipe(0.85f, 0.5f, 0.15f, 0.5f, 12)
        val p3 = waitPageChange(p2, forward = true)!!
        shot("swipe-next")
        assertTrue("밀어서 넘어가지 않음 ($p2 → $p3)", p3 > p2)
        swipe(0.15f, 0.5f, 0.85f, 0.5f, 12)
        val p4 = waitPageChange(p3, forward = false)!!
        assertTrue("밀어서 돌아오지 않음 ($p3 → $p4)", p4 < p3)
        // 종이책(curl): 오른쪽 아래 모서리를 천천히 끌어 왼쪽으로
        swipe(0.88f, 0.9f, 0.05f, 0.55f, 60) // 오른쪽 끝 띠는 시스템 뒤로 가기 제스처라 피한다
        val p5 = waitPageChange(p4, forward = true)!!
        shot("curl-drag")
        assertTrue("모서리를 끌어 넘어가지 않음 ($p4 → $p5)", p5 > p4)
        // 넘김 효과: 슬라이드·없음에서도 넘어가는지
        for (effect in listOf("슬라이드", "없음", "종이책")) {
            menuItem("넘김")
            clickText(effect)
            shot("turn-sheet-$effect")
            device.pressBack(); SystemClock.sleep(400)
            closeMenu()
            val a = page()
            tap(0.9f, 0.5f)
            val b = waitPageChange(a, forward = true)!!
            assertTrue("넘김 효과 '$effect' 에서 넘어가지 않음 ($a → $b)", b > a)
        }
        // 볼륨 키(다음 쪽)
        val v0 = page()
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        val v1 = waitPageChange(v0, forward = true)
        shot("volume-key")
        assertTrue("볼륨 아래 키로 넘어가지 않음 ($v0 → $v1)", v1 != null && v1 > v0)
        assertAlive()
    }

    @Test
    fun rotation_keepsReaderAndPosition() {
        openBook()
        repeat(3) { tap(0.9f, 0.5f) }
        val before = page()
        shot("before-rotate")
        val rot0 = device.displayRotation
        try {
            if (rot0 == 0 || rot0 == 2) device.setOrientationLeft() else device.setOrientationNatural()
            SystemClock.sleep(2_500)
            val d = waitPage()
            shot("rotated")
            assertTrue("회전 뒤 읽던 책이 아님: $d", d.startsWith(book) || Regex("\\d+쪽").matches(d))
            val after = page()
            // 다시 배치되며 쪽 수가 바뀌어도 읽던 비율 근처여야 한다(처음으로 돌아가면 실패)
            assertTrue("회전 뒤 처음 쪽으로 돌아감 ($before → $after)", before <= 2 || after > 1)
            // 회전한 채로 넘기기·메뉴
            tap(0.9f, 0.5f)
            openMenu(); shot("rotated-menu"); closeMenu()
        } finally {
            // 처음 방향으로(CI 는 회전을 고정해 둔다: 태블릿 세로 = user_rotation 1)
            if (rot0 == 0) device.setOrientationNatural() else shell("settings put system user_rotation $rot0")
        }
        SystemClock.sleep(2_000)
        waitPage()
        shot("rotated-back")
        assertAlive()
    }

    @Test
    fun viewSettingsSheet_allTabs() {
        openBook()
        menuItem("보기")
        assertTrue(has(By.text("글꼴"), 8_000))
        shot("aa-text")
        // 글자 크기 키우기(오른쪽 '가') 두 번, 줄이기 한 번
        repeat(2) { stale { device.findObjects(By.text("가")).maxByOrNull { it.visibleBounds.centerX() }?.click() }; SystemClock.sleep(400) }
        stale { device.findObjects(By.text("가")).minByOrNull { it.visibleBounds.centerX() }?.click() }
        // 글꼴 바꾸기
        stale { device.findObject(By.textStartsWith("ASH 바탕"))?.click() }; SystemClock.sleep(400)
        shot("aa-font")
        clickText("테마")
        for (t in listOf("세피아", "야간", "내 테마", "주간")) { clickText(t); SystemClock.sleep(300) }
        shot("aa-theme")
        clickText("문단")
        for (step in listOf("줄 간격", "문단 간격", "좌우 여백", "위아래 여백")) {
            val label = scrollFind(By.text(step)) ?: continue
            val y = label.visibleBounds.centerY()
            stale { device.findObjects(By.text("+")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) }?.click() }
            SystemClock.sleep(300)
        }
        scrollFind(By.text("왼쪽 맞춤"))?.click()
        scrollFind(By.text("2글자"))?.click()
        scrollFind(By.text("글자 단위"))?.click()
        scrollFind(By.text("영문 하이픈 넣기"))?.click()
        shot("aa-para")
        // 시트 닫기: 쪽이 다시 보일 때까지 '뒤로'(시트가 다 펼쳐지면 탭 이름이 화면 밖이라 글자로는 못 본다)
        backToPage()
        // 다시 배치된 문서가 열리는지
        waitPage()
        closeMenu()
        shot("after-aa")
        tap(0.9f, 0.5f)
        assertAlive()
        // 밝기 시트
        menuItem("밝기")
        SystemClock.sleep(800)
        shot("light-sheet")
        device.pressBack(); SystemClock.sleep(500)
        assertAlive()
    }

    @Test
    fun listen_tts_opens() {
        if (API >= 33) shell("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        openBook()
        menuItem("듣기")
        // 엔진 확인(최대 15초): 엔진 없음/한국어 없음 안내, 알림 허용 묻기, 또는 바로 듣기 막대
        val end = SystemClock.uptimeMillis() + 20_000
        var seen = ""
        while (SystemClock.uptimeMillis() < end && seen.isEmpty()) {
            when {
                device.hasObject(By.text("읽어 주기 엔진이 없어요")) || device.hasObject(By.text("한국어 음성이 없어요")) -> seen = "no-engine"
                device.hasObject(By.text("화면을 꺼도 계속 들으려면 알림을 허용해 주세요.")) -> { clickText("나중에"); }
                device.hasObject(By.desc("속도와 높낮이")) -> seen = "bar"
            }
            SystemClock.sleep(300)
        }
        shot("listen-$seen")
        assertTrue("듣기를 눌렀는데 아무 반응이 없음", seen.isNotEmpty())
        if (seen == "no-engine") {
            clickText("닫기")
        } else {
            obj(By.desc("속도와 높낮이")).click()
            assertTrue(has(By.desc("읽기 속도"), 5_000))
            shot("listen-tune")
            device.pressBack(); SystemClock.sleep(500)
            obj(By.desc("잠자기 타이머")).click(); SystemClock.sleep(500)
            shot("listen-sleep-menu")
            device.pressBack()
        }
        assertAlive()
        device.pressBack(); SystemClock.sleep(500)
        if (!onTabs()) device.pressBack()
        assertTrue("읽기 화면을 닫지 못함", has(By.text("독서 기록"), 8_000))
        assertAlive()
    }

    @Test
    fun search_toc_jump() {
        openBook()
        openMenu()
        obj(By.desc("검색")).click()
        val field = obj(By.clazz("android.widget.EditText"))
        field.text = "반딧불이"
        assertTrue("검색 결과가 나오지 않음", has(By.textEndsWith("곳을 찾았어요."), 60_000))
        shot("search-results")
        // 결과 줄("N쪽" + 글 조각)을 누르면 그 쪽으로
        val hit = device.findObjects(By.text(Pattern.compile("\\d+쪽"))).firstOrNull()
        assertNotNull(hit)
        val target = hit!!.text.removeSuffix("쪽").toInt()
        hit.click()
        SystemClock.sleep(1_200)
        assertTrue("검색 표시 띠가 없음", has(By.text("검색 결과로"), 5_000))
        shot("search-hit")
        val now = page()
        assertTrue("검색 결과 쪽으로 가지 않음 (기대 $target, 지금 $now)", kotlin.math.abs(now - target) <= 1)
        obj(By.desc("검색 표시 지우기")).click()
        // 목차 → 3장
        openMenu()
        // 목차 → 1장 → 3장 (검색 결과 쪽이 3장과 같은 쪽일 수 있어 1장에서 출발)
        menuItem("목차")
        obj(By.textContains("제1장"), 10_000).click()
        SystemClock.sleep(1_200)
        closeMenu()
        val before = page()
        menuItem("목차")
        val ch = obj(By.textContains("제3장"), 10_000)
        shot("toc")
        ch.click()
        SystemClock.sleep(1_500)
        shot("toc-jump")
        assertTrue("목차로 이동하지 않음", currentPage() != before)
        assertAlive()
    }

    @Test
    fun bookmark_memo_highlight() {
        openBook()
        tap(0.9f, 0.5f)
        val page = page()
        // 책갈피
        openMenu()
        obj(By.desc("책갈피")).click()
        SystemClock.sleep(800)
        val bid = runBlocking { app.db.books().allBooks().first { it.title == book }.id }
        assertTrue("책갈피가 저장되지 않음", runBlocking { app.db.books().bookmarks(bid).first().isNotEmpty() })
        shot("bookmarked")
        // 쪽 메모
        clickText("메모")
        obj(By.text("${page}쪽 메모"), 5_000)
        obj(By.clazz("android.widget.EditText")).text = "e2e 쪽 메모"
        clickText("저장")
        // 형광펜: 본문 글자를 길게 눌러 고르기 → 형광펜 → 노랑
        closeMenu()
        // 낱말 위를 길게 누른다(줄 사이·빈칸을 누르면 빈칸만 골라질 수 있어 몇 군데 시도)
        var done = false
        for ((x, y) in listOf(0.25f to 0.35f, 0.5f to 0.42f, 0.3f to 0.55f, 0.6f to 0.62f)) {
            longPress(x, y)
            if (!has(By.text("형광펜"), 3_000)) continue
            shot("selection")
            clickText("형광펜")
            obj(By.desc("노랑 형광펜")).click()
            if (has(By.text("형광펜을 칠했어요."), 4_000)) { done = true; break }
            device.pressBack(); SystemClock.sleep(500)
        }
        assertTrue("형광펜 안내가 없음", done)
        shot("highlighted")
        // 목록 확인
        menuItem("목차")
        clickText("책갈피·메모·형광펜")
        assertTrue("메모가 목록에 없음", has(By.text("e2e 쪽 메모"), 5_000))
        assertTrue("책갈피가 목록에 없음", has(By.text(Pattern.compile("\\s*${page}쪽")), 3_000))
        assertTrue("형광펜이 목록에 없음", has(By.textEndsWith("· 형광펜"), 3_000))
        shot("notes-list")
        // 내보내기 시트
        clickText("내보내기")
        assertTrue(has(By.text("형광펜·메모 내보내기"), 5_000))
        shot("export-sheet")
        device.pressBack(); SystemClock.sleep(500)
        // DB 에도 남았는지
        val (hlCount, noteCount) = runBlocking {
            val id = app.db.books().allBooks().first { it.title == book }.id
            app.db.books().allHighlights().count { it.bookId == id } to app.db.books().allNotes().count { it.bookId == id }
        }
        assertTrue("형광펜이 저장되지 않음", hlCount >= 1)
        assertTrue("메모가 저장되지 않음", noteCount >= 1)
        assertAlive()
    }

    @Test
    fun autoTurn_starts_and_stops() {
        openBook()
        menuItem("넘김")
        obj(By.text("자동 넘김")).click()
        SystemClock.sleep(500)
        assertAlive()
        // 켜면 시트·메뉴가 닫힌다. 화면을 누르면 멈춤 띠
        tap(0.5f, 0.5f)
        val paused = has(By.text("자동 넘김 멈춤"), 4_000)
        shot("auto-paused")
        if (paused) clickText("끄기")
        assertEquals(true, paused)
        assertAlive()
    }
}
