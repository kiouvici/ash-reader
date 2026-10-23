// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 화면 상태 복원(필수 2): '활동 유지 안 함'(always_finish_activities)으로 홈에 가면 Activity 가 없어지고,
 * 다시 열 때 저장된 상태(SavedStateHandle 번들)로 읽던 책·쪽으로 돌아와야 한다(프로세스 종료 뒤 복원과 같은 길).
 * 진짜 프로세스 종료(am kill) 뒤 복원은 시험 프로세스 밖에서 해야 해서 CI 스크립트(e2e-run.sh)가 따로 본다.
 */
@RunWith(AndroidJUnit4::class)
class H08RestoreTest : E2eTest() {
    @After fun keepActivities() { shell("settings put global always_finish_activities 0") }

    private fun relaunchFromLauncher() {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        SystemClock.sleep(2_000)
    }

    @Test
    fun libraryBook_restoredAfterActivityDestroyed() {
        launchApp()
        openFromLibrary("e2e-epub 소설", "EPUB")
        waitPage()
        dismissTapGuide()
        repeat(3) { tap(0.9f, 0.5f) }
        val before = currentPage()!!
        shot("before-home")
        shell("settings put global always_finish_activities 1")
        device.pressHome()
        SystemClock.sleep(2_000)
        relaunchFromLauncher()
        val d = waitPage(30_000)
        shot("restored")
        assertTrue("복원 뒤 읽던 책이 아님: $d", d.startsWith("e2e-epub 소설"))
        assertTrue("복원 뒤 쪽이 달라짐 ($before → ${currentPage()})", currentPage() == before)
        assertAlive()
    }

    @Test
    fun onceDocument_restoredAfterActivityDestroyed() {
        val uri = contentUriOf("$VIEW_DIR/e2e-intent 둘.epub", "application/epub+zip")
        launchApp()
        sendView(uri, "application/epub+zip")
        waitPage()
        tap(0.9f, 0.5f)
        shot("once-before-home")
        shell("settings put global always_finish_activities 1")
        device.pressHome()
        SystemClock.sleep(2_000)
        relaunchFromLauncher()
        val d = waitPage(30_000)
        shot("once-restored")
        assertTrue("한 번만 보기 문서가 복원되지 않음: $d", d.startsWith("e2e-intent 둘"))
        assertTrue("한 번만 보기 띠가 없음", has(By.text("한 번만 보는 중이에요"), 3_000))
        assertAlive()
    }

    @Test
    fun settingsTab_restored() {
        launchApp()
        goTab("독서 기록")
        shot("stats")
        shell("settings put global always_finish_activities 1")
        device.pressHome()
        SystemClock.sleep(1_500)
        relaunchFromLauncher()
        assertTrue(has(By.text("독서 기록"), 10_000))
        shot("stats-restored")
        assertAlive()
    }
}
