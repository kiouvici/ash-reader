// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 다른 앱에서 '열기'(ACTION_VIEW, content://) — 앱이 떠 있는 동안 연달아 열기.
 * 회귀(보고된 버그): 두 번째로 열면 문서 대신 서재가 보였다. 같은 파일 두 번, 서재로 돌아간 뒤 다시, 다른 파일, 홈 화면을 거쳐서.
 * 파일은 서재가 모으지 않는 /sdcard/Documents/ash-e2e-view 에 있어 '한 번만 보기'로 열린다.
 */
@RunWith(AndroidJUnit4::class)
class G07IntentTest : E2eTest() {
    private lateinit var one: Uri
    private lateinit var two: Uri
    private lateinit var three: Uri
    private val epub = "application/epub+zip"

    @Before fun uris() {
        // 앞 시험이 서재에 넣었으면 빼 둔다(서재에 있는 문서는 한 번만 보기가 아니라 서재 기록으로 열린다)
        runBlocking {
            app.db.books().allBooks().filter { it.title.startsWith("e2e-intent") && it.hidden == 0 }.forEach { app.library.remove(it) }
        }
        one = contentUriOf("$VIEW_DIR/e2e-intent 하나.epub", epub)
        two = contentUriOf("$VIEW_DIR/e2e-intent 둘.epub", epub)
        three = contentUriOf("$VIEW_DIR/e2e-intent 셋.pdf", "application/pdf")
    }

    /** 읽기 화면에 [title] 문서가 떠 있어야 한다(서재가 보이면 실패). */
    private fun assertReading(title: String, step: String) {
        val o = waitOpened(30_000)
        shot(step)
        assertTrue("$step: 문서 대신 다른 화면($o)", o is Opened.Page)
        val d = (o as Opened.Page).desc
        assertTrue("$step: '$title' 이 아니라 '$d' 가 열림", d.startsWith(title))
        assertTrue("$step: 한 번만 보기 띠가 없음", has(By.text("한 번만 보는 중이에요"), 3_000))
        assertTrue("$step: 서재가 보임", !onTabs())
        assertAlive()
    }

    @Test
    fun sameFileTwice_whileReading() {
        launchApp()
        sendView(one, epub)
        assertReading("e2e-intent 하나", "first-open")
        sendView(one, epub)
        SystemClock.sleep(1_500)
        assertReading("e2e-intent 하나", "second-open-same")
    }

    @Test
    fun sameFileTwice_afterBackToLibrary() {
        launchApp()
        sendView(one, epub)
        assertReading("e2e-intent 하나", "first-open")
        device.pressBack()
        assertTrue(has(By.text("독서 기록"), 8_000))
        shot("back-in-library")
        sendView(one, epub)
        assertReading("e2e-intent 하나", "second-open-after-back")
    }

    @Test
    fun differentFiles_inARow() {
        launchApp()
        sendView(one, epub)
        assertReading("e2e-intent 하나", "open-1")
        sendView(two, epub)
        assertReading("e2e-intent 둘", "open-2")
        sendView(three, "application/pdf")
        assertReading("e2e-intent 셋", "open-3")
        sendView(one, epub)
        assertReading("e2e-intent 하나", "open-1-again")
    }

    @Test
    fun viaHomeScreen_twice() {
        launchApp()
        sendView(one, epub)
        assertReading("e2e-intent 하나", "open-1")
        device.pressHome(); SystemClock.sleep(1_000)
        sendView(two, epub)
        assertReading("e2e-intent 둘", "open-2-from-home")
        device.pressHome(); SystemClock.sleep(1_000)
        sendView(two, epub)
        assertReading("e2e-intent 둘", "open-2-again-from-home")
    }

    @Test
    fun coldStart_viewIntent() {
        // 앱이 닫힌 상태에서 바로 열기
        // (시험이 앱 프로세스 안에서 돌아 강제 종료는 못 한다 → 홈으로 보낸 뒤 열기)
        device.pressHome()
        sendView(one, epub)
        assertReading("e2e-intent 하나", "cold-open")
        // 서재에 추가 → 한 번만 보기 띠가 사라지고 서재에 들어감
        clickText("서재에 추가")
        SystemClock.sleep(1_500)
        shot("added-to-library")
        assertTrue("서재에 추가했는데 한 번만 보기 띠가 남음", !device.hasObject(By.text("한 번만 보는 중이에요")))
        device.pressBack()
        assertTrue(has(By.desc(Pattern.compile("e2e-intent 하나, .*")), 10_000))
        assertAlive()
    }
}
