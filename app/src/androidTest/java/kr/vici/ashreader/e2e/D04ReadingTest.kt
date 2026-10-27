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

    /** 글자판(IME) 창이 떠 있나. */
    private fun imeShown(): Boolean = runCatching {
        inst.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** 실패 원인을 보려고 지금 화면 구조를 기록에 남긴다. */
    private fun dumpTree(name: String) {
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            out.toString("UTF-8").lines().filter { "text=\"\"" !in it || "content-desc=\"\"" !in it }.take(80)
                .forEach { android.util.Log.i("AshE2E", "[$name] $it") }
        }
    }

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
        val p1 = tapTurn(0.9f, p0, forward = true)!!
        shot("tap-next")
        assertTrue("오른쪽 탭으로 넘어가지 않음 ($p0 → $p1)", p1 > p0)
        val p2 = tapTurn(0.1f, p1, forward = false)!!
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

    /**
     * 작업 11: 회전(세로↔가로, 태블릿은 두 쪽 펼침) 3번 왕복 뒤 처음 방향으로 돌아오면 같은 쪽(같은 배치 = 같은 문장).
     * 다시 나눌 때 비율이 아니라 읽던 쪽 첫 글자로 찾으므로 앞쪽에서도 깎이지 않아야 한다. EPUB·TXT·HWP(eBook 보기).
     */
    /** 정보줄 "N/M쪽"의 M(전체 쪽 수). 못 읽으면 null. */
    private fun pageTotal(): Int? {
        val t = device.findObject(By.text(INFO))?.text ?: return null
        return Regex("\\d+ ?/ ?(\\d+)쪽").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 한글 원본 보기 → eBook 보기(책마다 저장되므로 이미 eBook 이면 그대로). [mustSwitch] = 단추가 꼭 있어야 함. */
    private fun toEbookView(title: String, mustSwitch: Boolean = true) {
        stackItem("테마 및 설정")
        val toggle = device.wait(Until.findObject(By.desc(Pattern.compile("보기 전환.*지금 원본.*"))), 4_000)
        if (mustSwitch) assertNotNull("'$title' 원본 → eBook 보기 전환 단추가 없음", toggle)
        if (toggle != null) { toggle.click(); SystemClock.sleep(800) }
        // 시트가 아직 열려 있으면 닫는다(없을 때 뒤로 가기를 누르면 책이 닫힘)
        if (has(By.text("테마 및 설정"), 1_000)) { device.pressBack(); SystemClock.sleep(500) }
        waitPage(120_000)
        closeMenu()
    }

    private fun rotationKeepsSpot(title: String, badge: String, hwpEbook: Boolean = false) {
        launchApp()
        openFromLibrary(title, badge)
        waitPage(120_000)
        dismissTapGuide()
        if (hwpEbook) toEbookView(title)
        var p = page()
        repeat(3) { p = tapTurn(0.9f, p, forward = true) ?: p }
        val before = settledPage()
        // 읽던 쪽 자리 표시가 잡히도록(쪽 글 읽기, 에뮬레이터 1~2초) — 넘기자마자 돌리면 자리 표시 없이 비율로만 옮긴다
        SystemClock.sleep(3_000)
        val total0 = pageTotal()
        android.util.Log.i("AshAnchor", "TEST $badge start before=$before total=$total0 rot=${device.displayRotation}")
        shot("$badge-before")
        val rot0 = device.displayRotation
        try {
            repeat(3) { i ->
                if (rot0 == 0 || rot0 == 2) device.setOrientationLeft() else device.setOrientationNatural()
                SystemClock.sleep(2_500); waitPage(120_000)
                android.util.Log.i("AshAnchor", "TEST $badge rot$i away page=${currentPage()}")
                shot("$badge-rot$i-away")
                if (rot0 == 0) device.setOrientationNatural() else shell("settings put system user_rotation $rot0")
                SystemClock.sleep(2_500); waitPage(120_000)
                // 다시 나눈 뒤 자리를 찾아 옮기기까지 에뮬레이터에서 2~3초 걸린다(새 배치의 쪽 글 읽기) — 최종 자리를 본다(최대 10초)
                val total = pageTotal()
                var now = page()
                val until = SystemClock.uptimeMillis() + 10_000
                while (now != before && SystemClock.uptimeMillis() < until) {
                    SystemClock.sleep(500); now = page()
                }
                android.util.Log.i("AshAnchor", "TEST $badge rot$i back page=$now total=$total (기대 $before/$total0)")
                shot("$badge-rot$i-back")
                // 지시 기준 = 같은 문장이 화면에 있는가. 작업 12: 같은 화면이면 배치(전체 쪽 수)도 같아야 하고, 그러면 정확히 같은 쪽
                assertEquals("'$title' 회전 ${i + 1}번 왕복 뒤 전체 쪽 수가 달라짐(같은 화면인데 배치가 흔들림)", total0, total)
                assertEquals("'$title' 회전 ${i + 1}번 왕복 뒤 읽던 쪽이 아님", before, now)
            }
        } finally {
            if (rot0 == 0) device.setOrientationNatural() else shell("settings put system user_rotation $rot0")
        }
        assertAlive()
    }

    /**
     * 작업 12: 같은 책을 5번 닫았다 다시 열어도 전체 쪽 수가 같다(결정적 배치 — 글꼴·CSS 가 다른 문서 것으로 섞이지 않음).
     */
    private fun reopenSameTotal(title: String, badge: String, hwpEbook: Boolean = false) {
        launchApp()
        val totals = ArrayList<Int?>()
        repeat(5) { i ->
            openFromLibrary(title, badge)
            waitPage(120_000)
            dismissTapGuide()
            if (hwpEbook && i == 0) toEbookView(title, mustSwitch = false)
            var t = pageTotal()
            val until = SystemClock.uptimeMillis() + 10_000
            while (t == null && SystemClock.uptimeMillis() < until) { SystemClock.sleep(500); t = pageTotal() }
            android.util.Log.i("AshAnchor", "TEST $badge reopen$i total=$t")
            totals += t
            device.pressBack(); SystemClock.sleep(600)
            if (!onTabs()) { device.pressBack(); SystemClock.sleep(600) }
            assertTrue("서재로 돌아오지 않음", device.wait(Until.hasObject(By.text("독서 기록")), 10_000))
        }
        assertNotNull("'$title' 전체 쪽 수를 읽지 못함", totals.first())
        assertTrue("'$title' 다시 열 때마다 전체 쪽 수가 다름: $totals", totals.all { it == totals.first() })
        assertAlive()
    }

    @Test fun reopen_sameTotal_epub() = reopenSameTotal(book, "EPUB")
    @Test fun reopen_sameTotal_txt() = reopenSameTotal("e2e-txt utf8", "TXT")
    @Test fun reopen_sameTotal_hwpEbook() = reopenSameTotal("gov-03-한글맞춤법-고시2017-12", "HWP", hwpEbook = true)

    @Test fun rotation_sameSpot_epub() = rotationKeepsSpot(book, "EPUB")
    @Test fun rotation_sameSpot_txt() = rotationKeepsSpot("e2e-txt utf8", "TXT")
    @Test fun rotation_sameSpot_hwpEbook() = rotationKeepsSpot("gov-03-한글맞춤법-고시2017-12", "HWP", hwpEbook = true)

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
        for (t in listOf("고요", "집중", "내 테마", "원본")) { clickText(t); SystemClock.sleep(300) }
        shot("aa-theme")
        clickText("문단")
        for (step in listOf("줄 간격", "문단 간격", "좌우 여백", "위아래 여백")) {
            val label = scrollFind(By.text(step)) ?: continue
            // 시트가 다시 그려지면 낡을 수 있다 → 위치를 못 읽으면 그 줄은 건너뜀
            val y = runCatching { label.visibleBounds.centerY() }.getOrNull() ?: continue
            stale { device.findObjects(By.text("+")).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) }?.click() }
            SystemClock.sleep(300)
        }
        tryClick(By.text("왼쪽 맞춤"))
        tryClick(By.text("2글자"))
        tryClick(By.text("글자 단위"))
        tryClick(By.text("영문 하이픈 넣기"))
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
        menuItem("검색")
        // 검색어가 실제로 들어갔는지 보고 검색 키(Enter)도 누른다. 결과가 안 나오면 다시 넣는다(흔들림 — PR #50·#49 CI)
        var found = false
        for (k in 0 until 3) {
            val field = obj(By.clazz("android.widget.EditText"))
            field.text = "반딧불이"
            SystemClock.sleep(400)
            if (runCatching { field.text }.getOrNull() != "반딧불이") continue
            device.pressEnter()
            // 작업 07 검색 시트는 "이 책에서 N곳"(07 CI 통과). 못 찾으면 화면 구조를 기록에 남긴다
            if (has(By.textStartsWith("이 책에서"), 30_000)) { found = true; break }
            shot("search-retry-$k")
            dumpTree("search-retry-$k")
        }
        assertTrue("검색 결과가 나오지 않음", found)
        shot("search-results")
        // 결과 줄("N쪽" + 글 조각)을 누르면 그 쪽으로
        // "이 책에서 N곳"이 먼저 뜨고 결과 줄은 조금 늦게 잡힐 때가 있다(CI phone-36-b: 화면엔 '13쪽'이 있는데 못 찾음) — 기다린다
        assertTrue("검색 결과 줄('N쪽')이 없음", has(By.text(Pattern.compile("\\d+쪽")), 10_000))
        val hit = device.findObjects(By.text(Pattern.compile("\\d+쪽"))).firstOrNull()
        assertNotNull("검색 결과 줄('N쪽')을 읽지 못함", hit)
        val target = hit!!.text.removeSuffix("쪽").toInt()
        hit.click()
        SystemClock.sleep(1_200)
        assertTrue("검색 표시 띠가 없음", has(By.desc("검색 표시 지우기"), 5_000))
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
        val page = settledPage() // 넘김이 끝난 뒤의 쪽(두 쪽 펼침은 1 → 3 처럼 늦게 바뀜)
        // 책갈피
        menuItem("책갈피")
        SystemClock.sleep(800)
        val bid = runBlocking { app.db.books().allBooks().first { it.title == book }.id }
        assertTrue("책갈피가 저장되지 않음", runBlocking { app.db.books().bookmarks(bid).first().isNotEmpty() })
        shot("bookmarked")
        // 쪽 메모
        closeMenu()
        menuItem("메모")
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
        // 7G: 빈칸만 칠한 형광펜은 없어야 한다
        val blankHl = runBlocking {
            val id = app.db.books().allHighlights().first().bookId
            app.db.books().allHighlights().count { it.bookId == id && it.text.isBlank() }
        }
        assertEquals("빈칸만 칠한 형광펜이 있음", 0, blankHl)
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

    /**
     * 7G: 정보줄이 본문 마지막 줄과 겹치지 않는다(휴대폰·태블릿, 세로·가로, 두 쪽 펼침의 두 쪽 모두).
     * 정보줄 글자 위 8dp 띠(정보줄 안쪽 여백 + 본문과의 틈)에 본문 글자 잉크가 없어야 한다.
     * 태블릿 가로(두 쪽 펼침): 표지 그림이 없는 글 EPUB 은 첫 펼침부터 두 쪽(빈 왼쪽 쪽이 없음).
     */
    @Test
    fun infoLine_clearOfText_firstSpread() {
        openBook()
        // 첫 쪽으로(앞 시험이 읽던 자리를 남겼을 수 있음)
        repeat(30) {
            val p = currentPage() ?: return@repeat
            if (p <= 1) return@repeat
            tap(0.1f, 0.5f)
        }
        val d = ctx.resources.displayMetrics.density
        val w = device.displayWidth; val h = device.displayHeight
        val spread = w > h && minOf(w, h) / d >= 600f
        shot("first-page")
        if (spread) {
            // 쪽 그림이 늦게 올라올 수 있어 몇 번 찍어 본다
            var ink = 0
            for (k in 0 until 8) {
                ink = inkIn(screen(), (w * 0.06f).toInt(), (h * 0.12f).toInt(), (w * 0.44f).toInt(), (h * 0.8f).toInt())
                if (ink > 200) break
            }
            assertTrue("두 쪽 펼침 첫 펼침의 왼쪽 쪽이 비어 있음(글로 시작하는 책은 표지 단독이 아니어야 함, 잉크 $ink)", ink > 200)
        }
        for (i in 0 until 4) {
            val info = device.findObjects(By.text(INFO)).maxByOrNull { it.visibleBounds.top }
            assertNotNull("정보줄이 보이지 않음", info)
            val top = info!!.visibleBounds.top
            val band = (8 * d).toInt()
            val bmp = screen()
            val ink = inkIn(bmp, 0, (top - band).coerceAtLeast(0), w, top - 1)
            if (ink > 6) shot("info-overlap-$i")
            assertTrue("정보줄 위 띠에 본문 글자가 걸림(쪽 ${currentPage()}, 잉크 $ink px, 정보줄 위 ${top}px)", ink <= 6)
            tap(0.9f, 0.5f)
            SystemClock.sleep(600)
        }
        shot("info-clear")
        assertAlive()
    }

    private fun screen(): android.graphics.Bitmap {
        SystemClock.sleep(500)
        val b = inst.uiAutomation.takeScreenshot() ?: error("화면을 찍지 못함")
        return b.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    }

    /** 사각형 안에서 바탕색(가장 흔한 색)과 밝기가 크게 다른 점 수. */
    private fun inkIn(b: android.graphics.Bitmap, l: Int, t: Int, r: Int, bot: Int): Int {
        val x0 = l.coerceIn(0, b.width - 1); val x1 = r.coerceIn(x0 + 1, b.width)
        val y0 = t.coerceIn(0, b.height - 1); val y1 = bot.coerceIn(y0 + 1, b.height)
        fun lum(c: Int) = (android.graphics.Color.red(c) * 3 + android.graphics.Color.green(c) * 6 + android.graphics.Color.blue(c)) / 10
        val hist = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1 step 2) hist[lum(b.getPixel(x, y))]++
        val bg = hist.indices.maxByOrNull { hist[it] } ?: 255
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (kotlin.math.abs(lum(b.getPixel(x, y)) - bg) > 70) n++
        return n
    }
}
