// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 서재: 선반·보기 전환·정렬·상태 칩·문서 메뉴, 모으는 폴더 더하기·빼기, 책 추가(시스템 고르기), 압축 파일 안 고르기. */
@RunWith(AndroidJUnit4::class)
class B02LibraryTest : E2eTest() {
    private val tileP = Pattern.compile(".*, .*% 읽음")

    /** 글자판(IME) 창이 떠 있나. */
    private fun imeShown(): Boolean = runCatching {
        inst.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** 작업 06 B안: 첫 화면 카드(전체·형식·폴더·시리즈) → 들어가기 → 뒤로, '최근 ›', 전체의 상태 칸, 문서 메뉴. */
    @Test
    fun homeCards_statusChips_bookMenu() {
        launchApp()
        assertTrue(has(By.desc(tileP), 30_000))
        shot("library")
        for (p in listOf("전체, \\d+권", "형식, \\d+가지", "폴더, \\d+개", "시리즈, \\d+개")) {
            obj(By.desc(Pattern.compile(p))).click()
            SystemClock.sleep(900)
            assertTrue("카드를 눌렀는데 들어간 화면이 아님($p)", has(By.desc("뒤로"), 8_000))
            assertAlive()
            shot("home-card-${p.substringBefore(',')}")
            if (p.startsWith("전체")) {
                // 상태 칸(F-71)
                for (c in listOf("읽는 중", "읽을 책", "다 읽음", "★ 즐겨찾기")) {
                    device.findObject(By.textStartsWith(c))?.let { it.click(); SystemClock.sleep(400); shot("chip-$c"); it.click() }
                }
            }
            obj(By.desc("뒤로")).click(); SystemClock.sleep(900)
            assertTrue("뒤로 해도 첫 화면이 아님", has(By.desc(Pattern.compile("전체, \\d+권")), 8_000))
        }
        // '최근 ›' → 최근 격자
        obj(By.desc("최근 읽은 책 모두 보기")).click()
        assertTrue("최근 화면이 아님", has(By.desc("뒤로"), 8_000))
        shot("recent-all")
        device.pressBack(); SystemClock.sleep(900)
        // 작업 07 책 ⋯ 메뉴(길게 누르기): 위 아이콘[공유·책 정보·파일 위치] + 즐겨찾기 등 → 파일 위치(문서 정보)
        obj(By.desc(tileP)).longClick()
        obj(By.desc("책 정보"), 8_000)
        shot("book-menu")
        obj(By.text(Pattern.compile("즐겨찾기( 빼기)?"))).click()
        SystemClock.sleep(500)
        obj(By.desc(tileP)).longClick()
        obj(By.desc("파일 위치"), 8_000).click()
        SystemClock.sleep(500)
        obj(By.text("실제 형식"))
        shot("book-info")
        clickText("닫기")
        SystemClock.sleep(600)
        assertAlive()
    }

    /** 작업 05: 형식·폴더 = 묶음 카드 → 눌러 들어가기 → (하위 폴더) → 뒤로. 카드 이름은 TalkBack "이름, n권". */
    @Test
    fun groupCards_enter_subfolder_back() {
        launchApp()
        assertTrue(has(By.desc(tileP), 30_000))
        for (home in listOf("형식, \\d+가지", "폴더, \\d+개")) {
            obj(By.desc(Pattern.compile(home))).click()
            obj(By.desc(Pattern.compile(".+, \\d+권")), 10_000)
            shot("cards-${home.substringBefore(',')}")
            // 누르는 순간 화면이 바뀌며 요소가 사라져 StaleObjectException 이 날 수 있어, 자리를 읽어 좌표로 누른다
            val cb = obj(By.desc(Pattern.compile(".+, \\d+권"))).visibleBounds
            device.click(cb.centerX(), cb.centerY())
            assertTrue("카드를 눌렀는데 묶음 화면이 아님", has(By.text("모든 책"), 8_000))
            assertTrue(has(By.desc("뒤로")))
            shot("group-${home.substringBefore(',')}")
            // 하위 폴더 줄이 있으면 그 안으로 한 단계 더
            device.findObject(By.desc(Pattern.compile(".+, 폴더 열기")))?.let { sub ->
                sub.click()
                assertTrue("하위 폴더 안으로 들어가지 않음", has(By.text("모든 책"), 8_000))
                shot("subgroup-${home.substringBefore(',')}")
                device.pressBack(); SystemClock.sleep(900)
                assertTrue(has(By.text("모든 책")))
            }
            // 작업 07: 정렬은 ⋯ 보기·정렬 메뉴
            obj(By.desc("보기·정렬")).click(); SystemClock.sleep(500)
            clickText("제목")
            SystemClock.sleep(500)
            shot("group-sort-${home.substringBefore(',')}")
            obj(By.desc("뒤로")).click(); SystemClock.sleep(900)
            assertTrue("뒤로 해도 카드 목록이 아님", has(By.desc(Pattern.compile(".+, \\d+권")), 8_000))
            device.pressBack(); SystemClock.sleep(900)
            assertTrue("두 번 뒤로 해도 첫 화면이 아님", has(By.desc(Pattern.compile("전체, \\d+권")), 8_000))
            assertAlive()
        }
    }

    /** 작업 06: 문서 메뉴 → 책 정보 → 표지 고르기·원래대로·다른 정보 찾기(인터넷 꺼짐 → 묻는 창만) → 뒤로. */
    @Test
    fun bookInfo_open_reset() {
        launchApp()
        // 이북 한 권(압축 파일은 인터넷 찾기 대상이 아니다)을 검색으로 찾아 길게 누른다
        obj(By.desc("검색"), 30_000).click()
        obj(By.clazz("android.widget.EditText")).text = "e2e-epub"
        SystemClock.sleep(600)
        obj(By.desc(Pattern.compile("e2e-epub 소설, EPUB, .*")), 10_000)
        // 가로 태블릿은 글자판이 화면 절반을 덮어 결과 대신 글자판을 길게 누를 수 있다(PR #49 CI land-a) — 닫고 누른다
        if (imeShown()) { device.pressBack(); SystemClock.sleep(600) }
        obj(By.desc(Pattern.compile("e2e-epub 소설, EPUB, .*")), 10_000).longClick()
        // 작업 07 책 ⋯ 메뉴의 위 아이콘 '책 정보'. 검색 글자판이 닫히며 메뉴 자리가 옮겨질 수 있어,
        // 자리를 잡은 뒤 새로 찾아 누르고 넘어가지 않으면 다시 누른다
        obj(By.desc("책 정보"), 8_000)
        var opened = false
        repeat(3) {
            if (opened) return@repeat
            SystemClock.sleep(700)
            device.findObject(By.desc("책 정보"))?.click()
            opened = has(By.text("표지 고르기"), 4_000)
        }
        assertTrue("책 정보 화면이 아님", opened)
        shot("book-info")
        obj(By.desc("표지: 첫 쪽")).click(); SystemClock.sleep(1_500)
        shot("book-info-first-page")
        clickText("원래대로"); SystemClock.sleep(800)
        obj(By.text("다른 정보 찾기")).click()
        // 시험에서는 '인터넷에서 책 정보 찾기'가 꺼져 있다 → 켤지 묻는 창
        assertTrue("인터넷을 켤지 묻지 않음", has(By.text("인터넷에서 찾을까요?"), 5_000))
        shot("book-info-ask-net")
        clickText("취소")
        obj(By.desc("뒤로")).click(); SystemClock.sleep(800)
        // 검색 화면에서 들어왔으므로 한 번 더 뒤로
        repeat(2) { if (!device.hasObject(By.desc(Pattern.compile("전체, \\d+권")))) { device.pressBack(); SystemClock.sleep(800) } }
        assertTrue(has(By.desc(Pattern.compile("전체, \\d+권")), 8_000))
        assertAlive()
    }
    @Test
    fun libraryFolders_pickerOpens_removeAndUndo() {
        launchApp()
        // 작업 06: 서재 첫 화면에서 '더 보기'가 빠짐 → 설정 › 서재 폴더(S-17)
        goTab("설정")
        (scrollFind(By.text("서재 폴더")) ?: obj(By.text("서재 폴더"))).click()
        SystemClock.sleep(600)
        obj(By.text(Pattern.compile("폴더 추가·빼기|폴더 추가"))).click()
        SystemClock.sleep(1_000)
        shot("folders")
        // 폴더 추가 → 시스템 폴더 고르기(SAF)가 열리는지만 보고 닫는다
        val add = scrollFind(By.text(Pattern.compile("\\+ 폴더 추가|폴더 추가|\\+ 폴더 고르기")))
        assertNotNull("폴더 추가 단추가 없음", add)
        add!!.click()
        has(By.pkg(Pattern.compile(".*documentsui.*")), 10_000)
        SystemClock.sleep(1_000)
        shot("folder-picker")
        // 시스템 폴더 고르기(com.android.documentsui / com.google.android.documentsui)는 '뒤로'가 서랍부터 닫을 수 있다
        repeat(3) { if (device.currentPackageName != PKG) { device.pressBack(); SystemClock.sleep(1_000) } }
        assertAlive()
        // Download 줄 [빼기] → 스낵바 [되돌리기] → 다시 들어옴
        val before = runBlocking { app.db.books().allScanFolders() }.count { it.include }
        val remove = scrollFind(By.text("빼기"))
        if (remove != null) {
            remove.click()
            SystemClock.sleep(800)
            shot("folder-removed")
            assertEquals(before - 1, runBlocking { app.db.books().allScanFolders() }.count { it.include })
            obj(By.text("되돌리기"), 5_000).click()
            SystemClock.sleep(1_000)
            shot("folder-undo")
            assertEquals(before, runBlocking { app.db.books().allScanFolders() }.count { it.include })
        }
        device.pressBack()
        assertTrue(has(By.text("독서 기록")))
        assertAlive()
    }

    @Test
    fun addBook_systemPicker_cancel() {
        launchApp()
        val add = scrollFind(By.text("+ 책 추가"))
        if (add == null) { shot("no-add-chip"); return } // 서점 줄을 끈 경우
        add.click()
        // 시스템 문서 고르기
        device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 10_000)
        shot("system-picker")
        device.pressBack()
        // 가로 태블릿은 제목 줄(내비게이션)이 위로 밀려 있어 '+ 책 추가'로도 본다
        assertTrue("고르기를 닫은 뒤 서재로 돌아오지 않음", device.wait(Until.hasObject(By.text(Pattern.compile("독서 기록|\\+ 책 추가"))), 10_000))
        assertAlive()
    }

    @Test
    fun zipWithTwoDocs_showsPicker_opensInner() {
        launchApp()
        openFromLibrary("e2e-zip 묶음")
        val o = waitOpened()
        shot("zip-pick")
        assertTrue("압축 파일 안 고르기가 아님: $o", o is Opened.ZipPick)
        clickText("첫째 책.epub")
        waitPage()
        dismissTapGuide()
        shot("zip-inner-open")
        assertAlive()
    }
}
