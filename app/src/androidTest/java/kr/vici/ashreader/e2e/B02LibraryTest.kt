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

    @Test
    fun shelves_sort_viewToggle_bookMenu() {
        launchApp()
        assertTrue(has(By.desc(tileP), 30_000))
        shot("library")
        for (s in listOf("전체", "형식별", "폴더별", "시리즈", "최근")) {
            clickText(s)
            assertAlive()
            shot("shelf-$s")
        }
        obj(By.desc("보기 전환")).click(); SystemClock.sleep(600); shot("list-mode")
        obj(By.desc("보기 전환")).click(); SystemClock.sleep(600)
        // 정렬
        clickText("전체")
        obj(By.desc("더 보기")).click()
        obj(By.textStartsWith("정렬:")).click()
        SystemClock.sleep(700)
        shot("sort-dialog")
        device.pressBack()
        // 상태 칩
        for (c in listOf("읽는 중", "읽을 책", "다 읽음", "★ 즐겨찾기")) {
            device.findObject(By.textStartsWith(c))?.let { it.click(); SystemClock.sleep(400); shot("chip-$c"); it.click() }
        }
        // 문서 메뉴(길게 누르기) → 즐겨찾기 → 문서 정보
        clickText("최근")
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

    @Test
    fun libraryFolders_pickerOpens_removeAndUndo() {
        launchApp()
        obj(By.desc("더 보기")).click()
        // 7F: '서재 폴더'(S-17). 옛 판 이름 '모으는 폴더 관리'도 받는다
        obj(By.text(Pattern.compile("서재 폴더|모으는 폴더 관리"))).click()
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
