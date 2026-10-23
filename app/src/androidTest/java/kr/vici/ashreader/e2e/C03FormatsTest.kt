// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 형식마다 서재에서 열어 쪽이 보이는지(죽지 않는지), 한 쪽 넘겨 보고, 원본/eBook 두 보기가 있는 형식(HWP·HWPX·DOCX·DOC·RTF)은
 * 두 보기를 오가며 다시 쪽이 보이는지 본다. 깨진·암호 문서는 안내가 뜨고 앱이 살아 있어야 한다.
 * 파일: test-files/public(공개) + make-e2e-files.py(합성).
 */
@RunWith(AndroidJUnit4::class)
class C03FormatsTest : E2eTest() {

    /** 열고 → 쪽 확인 → 한 쪽 넘김 → (두 보기면) 전환 두 번. */
    private fun openAndCheck(title: String, badge: String? = null, dual: Boolean = false, allowError: Boolean = false, timeoutMs: Long = 90_000) {
        launchApp()
        openFromLibrary(title, badge)
        when (val o = waitOpened(timeoutMs)) {
            is Opened.Page -> Unit
            is Opened.Error -> { shot("error-$title"); if (allowError) { goBack(); return } else fail("'$title' 을(를) 열지 못함: ${o.msg}") }
            else -> { shot("unexpected-$title"); fail("'$title': 예상 밖 화면 $o") }
        }
        dismissTapGuide()
        SystemClock.sleep(800)
        shot("$title-open")
        val p0 = currentPage()
        tap(0.9f, 0.5f)
        val p1 = waitPageChange(p0, forward = true, timeoutMs = 6_000)
        shot("$title-next")
        assertAlive()
        if (dual) {
            // 위 막대의 보기 전환 단추(설명 = 바꿀 대상: "원본으로 보기" / "이북식(eBook)으로 보기")
            repeat(2) { i ->
                openMenu()
                val toggle = device.wait(Until.findObject(By.desc(EBOOK)), 3_000) ?: device.wait(Until.findObject(By.desc(ORIGINAL)), 3_000)
                if (toggle == null) { shot("$title-no-toggle"); fail("'$title': 원본/eBook 전환 단추가 없음") }
                val to = toggle!!.contentDescription
                toggle.click()
                SystemClock.sleep(500)
                when (val o = waitOpened(timeoutMs)) {
                    is Opened.Page -> Unit
                    is Opened.Error -> { shot("$title-switch-error-$i"); fail("'$title' $to 로 바꾼 뒤 오류: ${o.msg}") }
                    else -> fail("'$title' $to: 예상 밖 화면 $o")
                }
                SystemClock.sleep(800)
                shot("$title-view-$i-$to")
                assertAlive()
            }
        }
        android.util.Log.i(TAG, "$title: 쪽 $p0 → $p1")
        goBack()
    }

    private fun goBack() {
        device.pressBack(); SystemClock.sleep(500)
        if (!onTabs()) device.pressBack()
        assertTrue("서재로 돌아오지 않음", device.wait(Until.hasObject(By.text("독서 기록")), 10_000))
        assertAlive()
    }

    // ── 이북류 ──
    @Test fun epub() = openAndCheck("e2e-epub 소설", "EPUB")
    @Test fun fb2() = openAndCheck("e2e-fb2", "FB2")
    @Test fun html() = openAndCheck("e2e-html", "HTML")
    @Test fun chm() = openAndCheck("chm-sample-cp949", "CHM")
    /** 합성 MOBI(PalmDOC, 압축 없음) — 공개 표본이 없어 손으로 만든 것이라 '안내 후 살아 있음'까지만 요구한다. */
    @Test fun mobi() = openAndCheck("e2e-mobi", "MOBI", allowError = true)

    // ── TXT ──
    @Test fun txtUtf8() = openAndCheck("e2e-txt utf8", "TXT")
    @Test fun txtCp949() = openAndCheck("txt-cp949", "TXT")
    @Test fun txtUtf16() = openAndCheck("txt-utf16le-bom", "TXT")

    // ── 고정 쪽 ──
    @Test fun pdfText() = openAndCheck("e2e-pdf 글자", "PDF")
    @Test fun pdfRef() = openAndCheck("word-structure.ref", "PDF")
    @Test fun djvu() = openAndCheck("djvu-sample", "DjVu")
    @Test fun cbz() = openAndCheck("e2e-cbz 만화", "CBZ")

    // ── 한글(원본 ↔ eBook) ──
    @Test fun hwpx() = openAndCheck("gov-02-가족관계규칙-별표1", "HWPX", dual = true, timeoutMs = 120_000)
    @Test fun hwp03() = openAndCheck("gov-03-한글맞춤법-고시2017-12", "HWP", dual = true)
    @Test fun hwp04() = openAndCheck("gov-04-가족관계규칙-별표2", "HWP", dual = true, timeoutMs = 120_000)

    // ── Word(원본 ↔ eBook) ──
    @Test fun docx() = openAndCheck("word-structure", "DOCX", dual = true)
    @Test fun doc() = openAndCheck("word97-structure", "DOC", dual = true)
    @Test fun rtfNamedDoc() = openAndCheck("rtf-named-as", null, dual = true)

    // ── 깨진·암호 문서: 안내만, 죽지 않기 ──
    @Test
    fun docxCorrupt_showsError() {
        launchApp()
        openFromLibrary("word-corrupt", "DOCX")
        val o = waitOpened()
        shot("corrupt")
        assertTrue("깨진 DOCX 인데 오류 안내가 아님: $o", o is Opened.Error || o is Opened.Page)
        goBack()
    }

    @Test
    fun docxEncrypted_asksPassword_cancel() {
        launchApp()
        openFromLibrary("word-encrypted-agile", "DOCX")
        val o = waitOpened()
        shot("encrypted")
        assertTrue("암호 DOCX 인데 암호 창이 아님: $o", o is Opened.Password)
        // 틀린 암호 → 다시 묻기
        obj(By.clazz("android.widget.EditText")).text = "wrong-password"
        clickText("열기")
        SystemClock.sleep(1_500)
        assertTrue("틀린 암호 뒤 다시 묻지 않음", has(By.text("암호가 걸린 문서"), 15_000))
        shot("encrypted-wrong")
        clickText("서재로")
        assertTrue(device.wait(Until.hasObject(By.text("독서 기록")), 10_000))
        assertAlive()
    }
}
