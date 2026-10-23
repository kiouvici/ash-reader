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
    fun addFolder_byPath_thenRemove() {
        launchApp()
        obj(By.desc("더 보기")).click()
        clickText("모으는 폴더 관리")
        obj(By.text("+ 폴더 고르기"))
        shot("folders")
        clickText("+ 폴더 고르기")
        // '모든 파일 접근'이 있으면 앱 안 경로 고르기 창, 없으면 시스템 폴더 고르기(SAF)
        if (has(By.text("이 폴더 고르기"), 5_000)) {
            // 줄 글자는 "  이름"(앞 빈칸 둘). 뒤 화면의 'Download 를 자동으로…' 같은 글과 헷갈리지 않게 통째로 맞춘다
            obj(By.text(Pattern.compile("\\s*Download"))).click(); SystemClock.sleep(600)
            obj(By.text(Pattern.compile("\\s*ash-e2e"))).click(); SystemClock.sleep(600)
            shot("path-dialog")
            clickText("이 폴더 고르기")
            val row = device.wait(Until.findObject(By.text("Download/ash-e2e")), 10_000)
            shot("folder-added")
            assertNotNull("고른 폴더가 목록에 없음", row)
            val folders = runBlocking { app.db.books().allScanFolders() }
            assertTrue(folders.any { it.path.endsWith("/Download/ash-e2e") && it.include })
            // 빼기(같은 줄의 [빼기] — 'Download' 줄 아래 두 번째)
            val removes = device.findObjects(By.text("빼기"))
            val target = removes.minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - row!!.visibleBounds.centerY()) }
            target!!.click()
            SystemClock.sleep(800)
            assertEquals(0, runBlocking { app.db.books().allScanFolders() }.count { it.path.endsWith("/Download/ash-e2e") })
        } else {
            shot("saf-picker")
            device.pressBack()
        }
        obj(By.text("지금 다시 모으기")).click()
        clickText("확인")
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
