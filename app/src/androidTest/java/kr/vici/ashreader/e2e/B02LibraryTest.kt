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
        assertTrue(has(By.text("모든 책"), 8_000))
        shot("recent-all")
        device.pressBack(); SystemClock.sleep(900)
        // 문서 메뉴(길게 누르기) → 즐겨찾기 → 문서 정보
        val tile = obj(By.desc(tileP))
        tile.longClick()
        obj(By.text("열기"), 8_000)
        shot("book-menu")
        obj(By.desc("즐겨찾기")).click()
        SystemClock.sleep(400)
        // 시트가 반만 펼쳐져 있으면 아래 줄은 밀어 올려야 보인다
        (scrollFind(By.text("문서 정보")) ?: obj(By.text("문서 정보"))).click()
        SystemClock.sleep(500)
        obj(By.text("실제 형식"))
        shot("book-info")
        clickText("닫기")
        device.pressBack()
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
            val card = obj(By.desc(Pattern.compile(".+, \\d+권")), 10_000)
            shot("cards-${home.substringBefore(',')}")
            card.click()
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
            // 정렬 바꾸기·검색 열기
            obj(By.desc(Pattern.compile("정렬: .*"))).click(); SystemClock.sleep(500)
            clickText("이름")
            obj(By.desc("검색")).click(); SystemClock.sleep(500)
            shot("group-sort-search-${home.substringBefore(',')}")
            obj(By.desc("뒤로")).click(); SystemClock.sleep(900)
            assertTrue("뒤로 해도 카드 목록이 아님", has(By.desc(Pattern.compile(".+, \\d+권")), 8_000))
            device.pressBack(); SystemClock.sleep(900)
            assertTrue("두 번 뒤로 해도 첫 화면이 아님", has(By.desc(Pattern.compile("전체, \\d+권")), 8_000))
            assertAlive()
        }
    }

    @Test
    fun libraryFolders_pickerOpens_removeAndUndo() {
        launchApp()
        // 작업 06: 서재 첫 화면에서 '더 보기'가 빠짐 → 설정 › 서재 폴더(S-17)
        goTab("설정")
        (scrollFind(By.text("서재 폴더")) ?: obj(By.text("서재 폴더"))).click()
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
        assertTrue("고르기를 닫은 뒤 서재로 돌아오지 않음", device.wait(Until.hasObject(By.text("독서 기록")), 10_000))
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
