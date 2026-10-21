// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun appNameIsAshReader() {
        assertEquals("ASH Reader", AppInfo.NAME)
    }
}
