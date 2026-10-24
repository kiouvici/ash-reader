// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정 화면(S-51): 모든 스위치를 켰다 끄고, 값 줄은 한 번씩 눌러 돌려 보고, 화면·창·바깥 앱으로 가는 줄은 열었다 돌아온다.
 * 오픈소스 라이선스 화면, 백업 내보내기·가져오기(시스템 문서 고르기 = UiAutomator).
 * 줄 찾기·스크롤은 Compose 시험 규칙(performScrollTo)으로 한다.
 */
@RunWith(AndroidJUnit4::class)
class F06SettingsTest : E2eTest() {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()

    /** Compose 시험 규칙이 걸린 동안은 프레임이 시험 시계로 돈다 → UiAutomator 로 볼 때마다 한 번 돌린다. */
    override fun pump() { runCatching { compose.waitForIdle() } }

    private val switches = listOf(
        "서점 바로가기 줄 보이기", "TXT 끊긴 줄 합치기", "TXT 줄 앞 공백 정리", "가장자리로 밝기 조절", "왼손잡이(좌우 바꾸기)",
        "볼륨 버튼으로 넘기기", "스크롤 보기", "정보줄을 위에", "두 쪽 펼침에서 쪽마다 정보줄", "야간에 그림은 반전 안 함",
        "새 만화에 AI 화질 향상 기본으로 켜기", "문서 그림 AI 화질 향상", "한 번만 보기 기록 지우기",
    )

    /** 눌러서 값만 도는 줄(마지막에 설정을 기본값으로 되돌린다). */
    private val cycles = listOf(
        "본문 글꼴", "TXT 빈 줄", "TXT 기본 인코딩", "넘김 효과", "화면 누르는 곳", "자동 넘김 간격", "자동 스크롤 속도",
        "화면 꺼짐 시간", "화면 회전", "기본 속도", "잠자기 타이머 기본", "저장 공간 상한",
    )

    /** 안내 창이 뜨는 줄 → [확인]/[닫기]/[취소]. */
    private val dialogs = listOf("탭 영역 안내 다시 보기", "사전", "따뜻한 색 자동 시각", "내 방식 편집", "정보줄 항목", "백업·내보내기")

    /** 다른 화면·바깥 앱으로 가는 줄 → 뒤로. */
    private val screens = listOf("파일 접근 권한", "서재 폴더", "서점 바로가기 편집", "형광펜·메모 모두 내보내기", "읽어 주기 엔진 설정 열기")

    /** 설정 화면이 그려져 있나(우리 창이 앞에 없으면 Compose 규칙이 뿌리를 못 찾아 예외 → false). */
    private fun onSettings(): Boolean = runCatching { compose.onAllNodesWithText("글자와 화면").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    /** 줄이 없으면(다른 묶음에서 이름이 바뀜·기기에서 숨김) 건너뛰고 false. */
    private fun row(name: String): Boolean {
        if (runCatching { compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)) {
            android.util.Log.w(TAG, "설정 줄 없음(건너뜀): $name"); missing += name; return false
        }
        compose.onAllNodesWithText(name)[0].performScrollTo().performClick()
        compose.waitForIdle()
        SystemClock.sleep(500)
        return true
    }
    private val missing = ArrayList<String>()

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
            if (!row(s)) continue
            shot("switch-$s-1")
            // 켜면 확인 창이 뜨는 스위치가 있다(AI) → 닫고 다시 눌러 되돌린다
            find(By.text(Pattern.compile("확인|닫기|취소|켜기")), 800)?.let { it.click(); SystemClock.sleep(400) }
            row(s); find(By.text(Pattern.compile("확인|닫기|취소")), 500)?.click()
            assertAlive()
        }
        for (c in cycles) { if (row(c)) { shot("cycle-$c"); assertAlive() } }
        for (d in dialogs) {
            if (!row(d)) continue
            shot("dialog-$d")
            val btn = find(By.text(Pattern.compile("확인|닫기|취소")), 5_000)
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
            if (!row(sc)) continue
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
        // 이름이 바뀌어 못 찾은 줄이 많으면 시험 목록을 고쳐야 한다
        assertTrue("설정 줄을 많이 찾지 못함: $missing", missing.size <= 4)
    }

    @Test
    fun licenses_openEntry() {
        launchApp()
        goTab("설정")
        row("오픈소스 라이선스")
        // 이 시험은 Compose 규칙이 걸려 있어 화면이 시험 시계로 돈다 → 누르기·확인도 Compose API 로
        compose.waitUntil(15_000) { compose.onAllNodesWithText("MuPDF", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("licenses")
        compose.onAllNodesWithText("MuPDF", substring = true)[0].performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("오픈소스 라이선스").fetchSemanticsNodes().isEmpty() }
        shot("license-text")
        device.pressBack(); compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("오픈소스 라이선스").fetchSemanticsNodes().isNotEmpty() }
        device.pressBack(); compose.waitForIdle()
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
        assertTrue("문서 고르기가 열리지 않음", has(By.pkg(Pattern.compile(".*documentsui.*")), 15_000))
        SystemClock.sleep(1_500)
        shot("export-picker")
        val save = find(By.text(Pattern.compile("(?i)save|저장")), 10_000)
        assertTrue("[저장] 단추가 없음", save != null)
        save!!.click()
        assertTrue("내보내기 결과가 없음", has(By.textStartsWith("내보냈어요."), 20_000))
        shot("export-done")
        clickText("확인")
        // 가져오기(S-53, 7F): '백업 가져오기' 화면 — 기억한 백업 폴더의 .ashbak 목록에서 골라 [가져오기].
        // (옛 판: 시스템 파일 고르기). 폴더를 아직 고르지 않았으면 시스템 폴더 고르기가 기기마다 달라 화면만 남긴다.
        row("백업·내보내기")
        clickText(find(By.text("백업 가져오기"), 3_000)?.let { "백업 가져오기" } ?: "파일에서 가져오기")
        SystemClock.sleep(1_500)
        shot("import-screen")
        val newest = find(By.text("가장 최근 백업"), 8_000)
        if (newest != null) {
            newest.click()
            SystemClock.sleep(500)
            obj(By.text("가져오기")).click()
            assertTrue("가져오기 결과가 없음", has(By.textStartsWith("가져와서 합쳤어요."), 20_000))
            shot("import-done")
            find(By.text("확인"), 3_000)?.click()
        } else {
            android.util.Log.w(TAG, "백업 폴더를 아직 고르지 않아 가져오기는 화면만 확인")
            device.pressBack()
        }
        assertAlive()
    }
}
