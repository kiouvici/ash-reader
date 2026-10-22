// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.SeriesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesParserTest {
    @Test
    fun volumeAtEnd() {
        assertEquals("바다 건너 마을" to 3, SeriesParser.parse("바다 건너 마을 3"))
        assertEquals("바다 건너 마을" to 4, SeriesParser.parse("바다 건너 마을 4권"))
        assertEquals("야행" to 12, SeriesParser.parse("야행 12화"))
    }

    @Test
    fun plainTitleIsNotSeries() {
        assertNull(SeriesParser.parse("어린 왕자"))
    }
}
