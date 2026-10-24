// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.MetaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 06 책 정보 규칙: 믿지 않는 값, 검색어 정리·권수, 일치도·자동 적용 조건. */
class MetaRulesTest {
    private fun cand(title: String) = MetaRules.Candidate(title, "", "", "", "", "", "구글 북스")

    @Test
    fun untrustedFileValues() {
        assertFalse(MetaRules.trustAuthor("Administrator"))
        assertFalse(MetaRules.trustAuthor("user"))
        assertFalse(MetaRules.trustAuthor("DESKTOP-4KQ9ZL1"))
        assertFalse(MetaRules.trustAuthor(""))
        assertFalse(MetaRules.trustAuthor("someone@mail.com"))
        assertTrue(MetaRules.trustAuthor("헤르만 헤세"))
        assertFalse(MetaRules.trustTitle("Microsoft Word - 보고서.docx", "보고서"))
        assertFalse(MetaRules.trustTitle("데미안", "데미안"))      // 파일 이름과 같으면 정보가 아님
        assertFalse(MetaRules.trustTitle("Untitled", "x"))
        assertTrue(MetaRules.trustTitle("데미안: 에밀 싱클레어의 청소년 시절", "demian_scan"))
    }

    @Test
    fun queryCleansFileName() {
        assertEquals(MetaRules.Query("슬램덩크", 3), MetaRules.query("[대원] 슬램덩크 03권 (완) 1200px"))
        assertEquals(MetaRules.Query("원피스", 12), MetaRules.query("원피스_vol.12"))
        assertEquals(MetaRules.Query("데미안", 0), MetaRules.query("데미안 (스캔본)"))
        assertEquals(MetaRules.Query("어린 왕자", 0), MetaRules.query("어린 왕자 v1.2"))
        assertEquals(MetaRules.Query("코트 위의 여름", 2), MetaRules.query("코트 위의 여름 2"))
        assertEquals(MetaRules.Query("1984", 0), MetaRules.query("1984"))
    }

    @Test
    fun autoOnlyWhenHighAndSameVolume() {
        val q = MetaRules.query("슬램덩크 3권")
        assertTrue(MetaRules.match(q, cand("슬램덩크 3")).auto)
        assertFalse("권수가 다르면 자동으로 바꾸지 않음", MetaRules.match(q, cand("슬램덩크 4")).auto)
        assertFalse(MetaRules.match(q, cand("농구 이야기 3")).auto)
        // 부제가 붙은 같은 책은 높게
        assertTrue(MetaRules.similarity("데미안", "데미안: 에밀 싱클레어의 청소년 시절") >= 70)
        assertEquals(100, MetaRules.similarity("어린 왕자", "어린왕자"))
    }

    @Test
    fun rankPutsBestFirst() {
        val q = MetaRules.query("데미안")
        val r = MetaRules.rank(q, listOf(cand("데미안 (영한 대역)"), cand("데미안"), cand("수레바퀴 아래서")))
        assertEquals("데미안", r.first().first.title)
        assertEquals("수레바퀴 아래서", r.last().first.title)
    }

    @Test
    fun isbnOnlyDigits() {
        assertEquals("9788937460449", MetaRules.isbn("978-89-374-6044-9"))
        assertEquals("", MetaRules.isbn("abc"))
    }
}
