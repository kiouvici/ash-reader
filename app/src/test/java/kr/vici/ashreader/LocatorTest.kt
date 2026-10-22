// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.locatorFrac
import kr.vici.ashreader.data.locatorPage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocatorTest {
    @Test
    fun samePaginationReturnsExactPage() {
        for (n in listOf(1, 7, 120, 999)) for (p in 0 until n) {
            assertEquals(p, locatorPage(p, locatorFrac(p, n), n))
            assertEquals(p, locatorPage(p, locatorFrac(p, n, 0.999f), n))
            assertEquals(p, locatorPage(p, locatorFrac(p, n, 0f), n))
        }
    }

    @Test
    fun ratioMapsAcrossModes() {
        // 원본 10쪽 중 5쪽(가운데) → 이북식 40쪽에서는 20쪽 근처
        assertEquals(22, locatorPage(5, locatorFrac(5, 10), 40))
        // 옛 기록(frac = -1)은 저장된 쪽 그대로
        assertEquals(3, locatorPage(3, -1f, 40))
        assertEquals(39, locatorPage(0, 1f, 40))
    }
}
