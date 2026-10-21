// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.vici.ashreader.pagecurl.PageCurlView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CI 에뮬레이터 스모크: 넘김 표면이 그려지고, 자동 넘김 3회가 끝나며 쪽이 앞으로 간다. 성능 판정은 하지 않는다. */
@RunWith(AndroidJUnit4::class)
class CurlSmokeTest {
    @Test
    fun autoTurnsAdvancePages() {
        ActivityScenario.launch(CurlBenchActivity::class.java).use { sc ->
            CurlBenchActivity.lastReport = null
            waitUntil(10_000) { CurlBenchActivity.lastPage == 0 }
            var view: PageCurlView? = null
            sc.onActivity { a -> view = findCurl(a.window.decorView) }
            assertNotNull("PageCurlView 없음", view)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view!!.runAutoTurns(3, 200) { CurlBenchActivity.lastReport = it }
            }
            waitUntil(30_000) { CurlBenchActivity.lastReport != null }
            val r = CurlBenchActivity.lastReport!!
            assertEquals(3, r.turns)
            assertTrue("그린 프레임 없음", r.frames > 3)
            waitUntil(5_000) { CurlBenchActivity.lastPage >= 3 }
        }
    }

    @Test
    fun tapWaitScreenOpens() {
        ActivityScenario.launch(TapWaitActivity::class.java).use { }
    }

    private fun findCurl(v: android.view.View): PageCurlView? {
        if (v is PageCurlView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findCurl(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun waitUntil(ms: Long, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (!cond()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("시간 초과 (${ms}ms)")
            SystemClock.sleep(50)
        }
    }
}
