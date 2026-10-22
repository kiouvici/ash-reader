// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchLogicTest {
    @Test
    fun medianOddAndEven() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }

    @Test
    fun summarizeGivesMedianAndMax() {
        val rows = Stats.summarize("e", "시간", "ms", (1..10).map { it.toDouble() })
        assertEquals(5.5, rows[0].value, 0.0)
        assertEquals(10.0, rows[1].value, 0.0)
        assertTrue(rows.all { it.note == "n=10" })
    }

    @Test
    fun csvEscaping() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
    }

    @Test
    fun csvHasBomHeaderAndRows() {
        val csv = CsvExporter.build(
            DeviceInfo(listOf("모델" to "X, Y")),
            listOf(Measurement("실험", "파일.hwp", "시간", 12.5, "ms", "n=1", timestampMs = 0)),
        )
        assertTrue(csv.startsWith("﻿# 모델,\"X, Y\"\r\n"))
        assertTrue(csv.contains("측정시각,실험,항목,지표,값,단위,비고\r\n"))
        assertTrue(csv.contains(",실험,파일.hwp,시간,12.500,ms,n=1\r\n"))
    }

    @Test
    fun vulkanVersionDecode() {
        // VK_MAKE_API_VERSION(0, 1, 3, 0) = (1 << 22) | (3 << 12)
        assertEquals("1.3.0", DeviceInfoCollector.decodeVulkan((1 shl 22) or (3 shl 12)))
    }

    @Test
    fun experimentListHasFivePlaceholders() {
        val ids = Experiments.all.map { it.id }
        assertTrue(ids.containsAll(listOf("1", "2", "3", "4", "5")))
    }
}
