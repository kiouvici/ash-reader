// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.TxtToc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RC 전 점검: TXT 목차 사용자 정규식 ReDoS 막기(F-33). */
class PreRcTest {
    @Test
    fun riskyShapes() {
        listOf("(a+)+$", "(a|aa)*b", "(\\w+\\s?)*$", "(.*a){2,}", "(x)\\1", "(?<n>a)\\k<n>", ".*.*.*.*.*a").forEach {
            assertTrue(it, TxtToc.riskyShape(it))
        }
        listOf("^【.+】", "^제\\s*\\d+\\s*화", "^#{1,3} .+", "(ab){2,3}", "[(+)]+x", "^(프롤로그|에필로그)$", "(?:제|第)\\d+[장章]").forEach {
            assertFalse(it, TxtToc.riskyShape(it))
        }
    }

    @Test
    fun validCustomRejectsSlowAndBroken() {
        assertTrue(TxtToc.validCustom(""))
        assertTrue(TxtToc.validCustom("^【.+】"))
        assertFalse(TxtToc.validCustom("(a+)+$"))
        assertFalse(TxtToc.validCustom("[abc"))
        assertEquals("", TxtToc.safeCustom("(a+)+$"))
        assertEquals("^【.+】", TxtToc.safeCustom(" ^【.+】 "))
    }

    @Test
    fun riskyCustomFallsBackToBuiltIns() {
        val lines = listOf("제1장 시작", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!", "제2장 끝")
        val f = TxtToc.findEx(lines, TxtToc.ALL, "(a+)+$")
        assertTrue(f.customTimedOut)
        assertEquals(listOf(0, 2), f.headings.map { it.line })
        val ok = TxtToc.findEx(listOf("글", "【외전】", "글"), 0, "^【.+】")
        assertFalse(ok.customTimedOut)
        assertEquals(listOf(1), ok.headings.map { it.line })
    }
}
