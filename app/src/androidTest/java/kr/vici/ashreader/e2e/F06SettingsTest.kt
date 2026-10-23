// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정 화면(S-51): 모든 스위치를 켰다 끄고, 값 줄은 한 번씩 눌러 돌려 보고, 화면·창·바깥 앱으로 가는 줄은 열었다 돌아온다.
 * 오픈소스 라이선스 화면, 백업 내보내기·가져오기(시스템 문서 고르기 = UiAutomator).
 * 줄 찾기·스크롤은 Compose 시험 규칙(performScrollTo)으로 한다.
 */
@RunWith(AndroidJUnit4::class)
class F06SettingsTest : E2eTest() {
    private val switches = listOf(
        "서점 바로가기 줄 보이기", "TXT 끊긴 줄 합치기", "TXT 줄 앞 공백 정리", "가장자리로 밝기 조절", "왼손잡이(좌우 바꾸기)",
        "볼륨 버튼으로 넘기기", "스크롤 보기", "정보줄을 위에", "두 쪽 펼침에서 쪽마다 정보줄", "야간에 그림은 반전 안 함",
        "AI 화질 개선", "한 번만 보기 기록 지우기",
    )

    /** 눌러서 값만 도는 줄(마지막에 설정을 기본값으로 되돌린다). */
    private val cycles = listOf(
        "본문 글꼴", "TXT 빈 줄", "TXT 기본 인코딩", "넘김 효과", "화면 누르는 곳", "자동 넘김 간격", "자동 스크롤 속도",
        "화면 꺼짐 시간", "화면 회전", "기본 속도", "잠자기 타이머 기본", "칸 단위 보기", "AI 화질 캐시 상한",
    )

    /** 안내 창이 뜨는 줄 → [확인]/[닫기]/[취소]. */
    private val dialogs = listOf("탭 영역 안내 다시 보기", "AI 화질 캐시 지우기", "사전", "따뜻한 색 자동 시각", "내 방식 편집", "정보줄 항목", "백업·내보내기")

    /** 다른 화면·바깥 앱으로 가는 줄 → 뒤로. */
    private val screens = listOf("파일 접근 권한", "모으는 폴더", "서점 바로가기 편집", "형광펜·메모 모두 내보내기", "읽어 주기 엔진 설정 열기")

    /** 설정 화면이 그려져 있나(우리 창이 앞에 없으면 Compose 규칙이 뿌리를 못 찾아 예외 → false). */
    private fun onSettings(): Boolean = runCatching { compose.onAllNodesWithText("글자와 화면").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun row(name: String) {
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.waitForIdle()
        SystemClock.sleep(500)
    }

    private fun backToSettings() {
        repeat(4) {
            if (device.currentPackageName == PKG && onTabs() && onSettings()) return
            device.pressBack()
            SystemClock.sleep(700)
        }
        if (device.currentPackageName != PKG) launchApp(clear = false)
        if (!onSettings()) goTab("설정")
    }

    @Test
    fun everySwitch_everyRow() {
        launchApp()
        goTab("설정")
        assertTrue(onSettings())
        shot("settings-top")
        for (s in switches) {
            row(s); shot("switch-$s-1")
            row(s); assertAlive()
        }
        for (c in cycles) { row(c); shot("cycle-$c"); assertAlive() }
        for (d in dialogs) {
            row(d)
            shot("dialog-$d")
            val btn = device.wait(Until.findObject(By.text(Pattern.compile("확인|닫기|취소"))), 5_000)
            assertTrue("'$d' 를 눌렀는데 창이 없음", btn != null)
            // '정보줄 항목' 은 스위치 하나를 눌러 본다
            if (d == "정보줄 항목") device.findObject(By.checkable(true))?.click()
            if (d == "내 방식 편집") device.findObject(By.desc(Pattern.compile("1행 1열 .*")))?.click()
            btn!!.click()
            SystemClock.sleep(500)
            assertAlive()
            assertTrue("'$d' 창을 닫은 뒤 설정이 아님", onSettings())
        }
        for (sc in screens) {
            row(sc)
            SystemClock.sleep(1_000)
            shot("screen-$sc")
            assertAlive(allowOther = true)
            if (sc == "서점 바로가기 편집") {
                device.findObject(By.text("+ 앱 추가"))?.let { it.click(); SystemClock.sleep(1_500); shot("store-picker"); device.findObject(By.text("닫기"))?.click() }
            }
            backToSettings()
            assertTrue("'$sc' 에서 설정으로 돌아오지 못함", onSettings())
        }
        resetPrefs()
        assertAlive()
    }

    @Test
    fun licenses_openEntry() {
        launchApp()
        goTab("설정")
        row("오픈소스 라이선스")
        val header = obj(By.text(Pattern.compile(".+ \\(\\d+\\)")), 15_000)
        shot("licenses")
        val y = header.visibleBounds.centerY()
        val first = device.findObjects(By.clickable(true)).filter { it.visibleBounds.centerY() > y }.minByOrNull { it.visibleBounds.centerY() }
        assertTrue("라이선스 항목이 없음", first != null)
        first!!.click()
        SystemClock.sleep(1_500)
        assertTrue("라이선스 원문 화면이 아님", !device.hasObject(By.text("오픈소스 라이선스")))
        shot("license-text")
        device.pressBack(); SystemClock.sleep(600)
        assertTrue(has(By.text("오픈소스 라이선스"), 5_000))
        device.pressBack(); SystemClock.sleep(600)
        assertTrue(onSettings())
        assertAlive()
    }

    @Test
    fun backup_export_then_import() {
        launchApp()
        goTab("설정")
        row("백업·내보내기")
        clickText("파일로 내보내기")
        // 시스템 문서 만들기(DocumentsUI) → [저장]
        assertTrue("문서 고르기가 열리지 않음", device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 15_000))
        SystemClock.sleep(1_500)
        shot("export-picker")
        val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|저장"))), 10_000)
        assertTrue("[저장] 단추가 없음", save != null)
        save!!.click()
        assertTrue("내보내기 결과가 없음", has(By.textStartsWith("내보냈어요."), 20_000))
        shot("export-done")
        clickText("확인")
        // 가져오기: 방금 만든 파일(최근 파일·다운로드)
        row("백업·내보내기")
        clickText("파일에서 가져오기")
        assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui.*"))), 15_000))
        SystemClock.sleep(2_000)
        var file = device.wait(Until.findObject(By.textStartsWith("ASH-백업-")), 6_000)
        if (file == null) {
            // 최근 목록에 없으면 다운로드로
            device.findObject(By.desc(Pattern.compile("(?i)show roots|루트 표시")))?.click()
            SystemClock.sleep(800)
            device.findObject(By.text(Pattern.compile("(?i)downloads?|다운로드")))?.click()
            SystemClock.sleep(1_500)
            file = device.wait(Until.findObject(By.textStartsWith("ASH-백업-")), 8_000)
        }
        shot("import-picker")
        assertTrue("내보낸 백업 파일을 고르기에서 찾지 못함", file != null)
        file!!.click()
        assertTrue("가져오기 결과가 없음", has(By.textStartsWith("가져와서 합쳤어요."), 20_000))
        shot("import-done")
        clickText("확인")
        assertAlive()
    }
}
