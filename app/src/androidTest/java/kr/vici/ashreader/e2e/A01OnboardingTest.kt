// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import kr.vici.ashreader.data.StorageAccess
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 첫 실행(S-02 파일 접근 안내 → 시스템 허용 화면/권한 창 → 서재). CI 는 이 시험을 권한을 주기 전에 맨 먼저 돌린다.
 * Android 11+: [허용하러 가기] → 설정 '모든 파일 접근' → 스위치 켜고 돌아오기. 8~10: 저장소 권한 창 [허용].
 */
@RunWith(AndroidJUnit4::class)
class A01OnboardingTest : E2eTest() {
    @Before override fun setUp() {
        device.wakeUp()
        shell("input keyevent 82")
        resetPrefs(storageAsked = false, tapGuideShown = false)
    }

    @Test
    fun firstLaunch_permissionFlow_reachesLibraryWithBooks() {
        launchApp()
        obj(By.textContains("허락이 필요해요"), 20_000)
        shot("intro")
        clickText("허용하러 가기")
        if (API >= 30) {
            // 시스템 설정 '모든 파일 접근' 화면(기기마다 글자가 달라 스위치를 찾고, 없으면 셸로 허용)
            device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.android\\.settings|com\\.google\\.android\\.settings.*"))), 10_000)
            shot("system-all-files")
            val sw = device.wait(Until.findObject(By.clazz("android.widget.Switch")), 5_000)
                ?: device.findObject(By.res("android:id/switch_widget"))
            if (sw != null && !sw.isChecked) { sw.click(); SystemClock.sleep(800) }
            if (!StorageAccess.hasFileAccess(ctx)) shell("appops set $PKG MANAGE_EXTERNAL_STORAGE allow")
            shot("system-all-files-on")
            device.pressBack()
        } else {
            // 런타임 권한 창
            val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(allow|허용)"))), 10_000)
            shot("permission-dialog")
            if (allow != null) allow.click() else shell("pm grant $PKG android.permission.READ_EXTERNAL_STORAGE")
        }
        // 돌아오면 서재 + Download 를 모은다
        assertTrue("서재로 오지 않음", device.wait(Until.hasObject(By.text("독서 기록")), 20_000))
        assertTrue("파일 접근이 허용되지 않음", StorageAccess.hasFileAccess(ctx))
        val anyBook = device.wait(Until.hasObject(By.desc(Pattern.compile(".*, .*% 읽음"))), 60_000)
        shot("library-after-grant")
        assertTrue("Download 의 시험 파일이 서재에 들어오지 않음", anyBook)
        assertAlive()
        val folders = runBlocking { app.db.books().allScanFolders() }
        assertTrue("Download 가 모으는 폴더로 들어가지 않음", folders.any { it.path == StorageAccess.downloadDir().path && it.include })
    }
}
