package kr.vici.ashreader

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun appNameIsAshReader() {
        assertEquals("ASH Reader", AppInfo.NAME)
    }
}
