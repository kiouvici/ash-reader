// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정 화면(S-51, 작업 06 재구성): 첫 화면 묶음 목록 → 하위 화면마다 모든 스위치를 켰다 끄고, 값 줄은 한 번씩 눌러 돌려 보고,
 * 창이 뜨는 줄은 열었다 닫고, 다른 화면·바깥 앱으로 가는 줄은 열었다 돌아온다.
 * 오픈소스 라이선스 화면, 백업 내보내기·가져오기(시스템 문서 고르기 = UiAutomator).
 * 줄 찾기·스크롤은 Compose 시험 규칙(performScrollTo)으로 한다. 태블릿 가로는 왼쪽 목록 + 오른쪽 하위 화면(뒤로 단추 없음).
 */
@RunWith(AndroidJUnit4::class)
class F06SettingsTest : E2eTest() {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()

    /** Compose 시험 규칙이 걸린 동안은 프레임이 시험 시계로 돈다 → UiAutomator 로 볼 때마다 한 번 돌린다. */
    override fun pump() { runCatching { compose.waitForIdle() } }

    private class Page(
        val switches: List<String> = emptyList(), val cycles: List<String> = emptyList(),
        val dialogs: List<String> = emptyList(), val screens: List<String> = emptyList(),
    )

    /** 하위 화면 → 줄(지금 S-51 표의 모든 줄이 어디로 옮겨졌는지도 이 표가 보여 준다). */
    private val pages = linkedMapOf(
        "서재 폴더" to Page(
            switches = listOf("서점 바로가기 줄 보이기", "시리즈 자동으로 묶기"), cycles = listOf("서재 기본 보기"),
            screens = listOf("파일 접근 권한", "서점 바로가기 편집", "폴더 추가·빼기"),
        ),
        "책 정보" to Page(switches = listOf("인터넷에서 책 정보 찾기"), dialogs = listOf("지금 모든 책 정보 다시 찾기")),
        "백업과 기록" to Page(switches = listOf("한 번만 보기 기록 지우기"), dialogs = listOf("독서 기록 지우기"), screens = listOf("형광펜·메모 모두 내보내기")),
        "글자와 화면" to Page(
            switches = listOf("TXT 끊긴 줄 합치기", "TXT 줄 앞 공백 정리"),
            cycles = listOf("본문 글꼴", "글자 크기", "줄 간격", "여백", "정렬", "줄 바꿈 단위", "TXT 기본 인코딩", "TXT 빈 줄"),
            dialogs = listOf("내 글꼴 관리"),
        ),
        "테마와 밝기" to Page(
            switches = listOf("가장자리로 밝기 조절"), cycles = listOf("테마", "앱 테마", "앱 테마", "앱 테마"),
            dialogs = listOf("따뜻한 색 자동 시각", "내 테마 만들기"),
        ),
        "넘김과 조작" to Page(
            switches = listOf("두 손가락으로 글자 크기", "왼손잡이(좌우 바꾸기)", "볼륨 버튼으로 넘기기"),
            cycles = listOf("넘김 효과", "쪽·스크롤", "쪽·스크롤", "화면 누르는 곳", "자동 넘김 간격", "자동 스크롤 속도"),
            dialogs = listOf("탭 영역 안내 다시 보기", "내 방식 편집"),
        ),
        "읽는 동안" to Page(
            switches = listOf("정보줄을 위에", "두 쪽 펼침에서 쪽마다 정보줄", "메뉴 버튼 이름 보이기"),
            cycles = listOf("화면 꺼짐 시간", "화면 회전"), dialogs = listOf("정보줄 항목"),
        ),
        "만화" to Page(
            switches = listOf("첫 쪽 단독", "테두리 자르기", "웹툰 제안", "새 만화에 AI 화질 향상 켜기"),
            cycles = listOf("읽는 방향", "두 쪽", "가로로 긴 쪽", "맞춤", "확대 방식"),
        ),
        "문서" to Page(switches = listOf("야간에 그림은 반전 안 함", "여백 자르기 기본"), cycles = listOf("처음 열 때", "두 번 탭 확대")),
        "AI 화질 향상" to Page(switches = listOf("새 만화에 기본으로 켜기", "문서 그림 AI 화질 향상"), cycles = listOf("저장 공간 상한")),
        "듣기와 사전" to Page(cycles = listOf("기본 속도", "잠자기 타이머 기본"), dialogs = listOf("사전 앱"), screens = listOf("읽어 주기 엔진 설정 열기")),
        "앱 정보" to Page(dialogs = listOf("소스 코드 받는 법"), screens = listOf("오픈소스 라이선스", "처음 안내 다시 보기")),
    )
    /**
     * 줄 누르기: 화면까지 굴린 뒤 화면 터치 대신 그 줄의 누름 동작을 바로 실행한다.
     * 설정이 아래 내비게이션 밑까지 이어지게 된 뒤(대표님 2026-10-26) 맨 아래로 굴려 온 줄을 터치하면
     * 위를 덮은 내비게이션('독서 기록')이 눌렸다(PR #51 CI). 누름 동작이 없는 줄만 예전처럼 터치한다.
     */
    private fun tapRow(name: String) {
        val node = compose.onAllNodesWithText(name)[0].performScrollTo()
        val clickable = runCatching { SemanticsActions.OnClick in node.fetchSemanticsNode().config }.getOrDefault(false)
        if (clickable) node.performSemanticsAction(SemanticsActions.OnClick) else node.performClick()
    }

    private fun count(text: String) = runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().size }.getOrDefault(0)
    private fun backCount() = runCatching { compose.onAllNodesWithContentDescription("뒤로").fetchSemanticsNodes().size }.getOrDefault(0)

    /** 설정 첫 화면(또는 태블릿 가로의 왼쪽 목록)이 보이나. 우리 창이 앞에 없으면 Compose 규칙이 뿌리를 못 찾아 false. */
    private fun onSettings(): Boolean = count("읽기 화면") > 0

    private fun openPage(name: String) {
        if (backCount() > 0 && count(name) > 0) return // 이미 그 하위 화면
        if (backCount() > 0) closePage()
        tapRow(name)
        compose.waitForIdle()
        SystemClock.sleep(400)
    }

    /** 하위 화면 닫기: 기기 '뒤로'(화면을 내려 둔 채면 위의 뒤로 단추가 화면 밖이라 누를 수 없다). 태블릿 가로 두 칸이면 할 일 없음. */
    private fun closePage() {
        if (backCount() > 0) { device.pressBack(); compose.waitForIdle(); SystemClock.sleep(400) }
    }

    /** 줄이 없으면(기기에서 숨김 등) 건너뛰고 false. */
    private fun row(name: String): Boolean {
        if (count(name) == 0) { android.util.Log.w(TAG, "설정 줄 없음(건너뜀): $name"); missing += name; return false }
        tapRow(name)
        compose.waitForIdle()
        SystemClock.sleep(500)
        return true
    }
    private val missing = ArrayList<String>()

    /**
     * 설정으로 돌아오기. 예전에는 '뒤로' 단추만 보이면 설정 하위 화면으로 봤는데, 서점 바로가기 편집·폴더 화면에도 '뒤로'와 아래 탭이 있어
     * 그 화면에 멈춘 채 다음 줄을 찾다 실패했다(PR #49 CI). → 설정 첫 화면이거나 [page] 하위 화면 제목이 보일 때까지 뒤로.
     */
    private fun backToSettings(page: String) {
        repeat(5) {
            if (device.currentPackageName == PKG && onTabs() && (onSettings() || (backCount() > 0 && count(page) > 0))) return
            device.pressBack()
            SystemClock.sleep(700)
        }
        if (device.currentPackageName != PKG) launchApp(clear = false)
        if (!onSettings() && backCount() == 0) goTab("설정")
    }

    @Test
    fun everySwitch_everyRow() {
        launchApp()
        goTab("설정")
        assertTrue(onSettings())
        shot("settings-top")
        for ((name, p) in pages) {
            openPage(name)
            shot("page-$name")
            for (s in p.switches) {
                if (!row(s)) continue
                shot("switch-$s-1")
                // 켜면 확인 창이 뜨는 스위치가 있다(AI) → 닫고 다시 눌러 되돌린다
                find(By.text(Pattern.compile("확인|닫기|취소|켜기")), 800)?.let { it.click(); SystemClock.sleep(400) }
                row(s); find(By.text(Pattern.compile("확인|닫기|취소")), 500)?.click()
                assertAlive()
            }
            for (c in p.cycles) { if (row(c)) { shot("cycle-$c"); assertAlive() } }
            for (d in p.dialogs) {
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
            }
            for (sc in p.screens) {
                if (!row(sc)) continue
                SystemClock.sleep(1_000)
                shot("screen-$sc")
                assertAlive(allowOther = true)
                if (sc == "서점 바로가기 편집") {
                    device.findObject(By.text("+ 앱 추가"))?.let { it.click(); SystemClock.sleep(1_500); shot("store-picker"); device.findObject(By.text("닫기"))?.click() }
                }
                backToSettings(name)
                assertTrue("'$sc' 에서 설정으로 돌아오지 못함", onSettings() || backCount() > 0)
                openPage(name) // 다른 화면에 다녀오면 설정 첫 화면부터 다시 보인다
            }
            closePage()
        }
        resetPrefs()
        // 만화 기본값(만화 화면의 값)도 처음으로 — 다른 시험이 기본값을 기대한다
        kr.vici.ashreader.data.ComicPrefsStore(ctx).saveDefaults(kr.vici.ashreader.data.ComicBookPrefs())
        assertAlive()
        // 이름이 바뀌어 못 찾은 줄이 많으면 시험 목록을 고쳐야 한다
        assertTrue("설정 줄을 많이 찾지 못함: $missing", missing.size <= 4)
    }

    @Test
    fun licenses_openEntry() {
        launchApp()
        goTab("설정")
        openPage("앱 정보")
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
        assertTrue(onSettings() || backCount() > 0)
        assertAlive()
    }

    @Test
    fun backup_export_then_import() {
        launchApp()
        goTab("설정")
        openPage("백업과 기록")
        row("지금 백업하기")
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
        openPage("백업과 기록")
        row("백업 가져오기")
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
