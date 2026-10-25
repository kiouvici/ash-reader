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

    private fun cand(title: String, author: String) = MetaRules.Candidate(title, author, "", "", "", "", "카카오")

    @Test
    fun authorSplitFromFileName() {
        assertEquals(MetaRules.Query("정의란 무엇인가", 0, "마이클 샌델"), MetaRules.query("정의란 무엇인가 - 마이클 샌델"))
        assertEquals(MetaRules.Query("어린왕자", 0, "생텍쥐페리"), MetaRules.query("어린왕자(생텍쥐페리)_완역본"))
        assertEquals(MetaRules.Query("Pride and Prejudice", 0, "Jane Austen"), MetaRules.query("Pride and Prejudice by Jane Austen"))
        assertEquals(MetaRules.Query("Animal Farm", 0, "George Orwell"), MetaRules.query("Animal Farm (George Orwell) [EN]"))
        // 권수 괄호는 지은이가 아님
        assertEquals(MetaRules.Query("나루토", 3, ""), MetaRules.query("나루토 (3)"))
    }

    @Test
    fun noAutoForWrongAuthorOrDerivedBook() {
        val q = MetaRules.query("정의란 무엇인가 - 마이클 샌델")
        assertTrue(MetaRules.match(q, cand("정의란 무엇인가", "마이클 샌델")).auto)
        assertFalse("요약·해설서", MetaRules.match(q, cand("[필독서 따라잡기] 정의란 무엇인가(마이클 샌델)", "베리타스알파 편집국")).auto)
        assertFalse("지은이가 다름", MetaRules.match(MetaRules.query("1984 - George Orwell"), cand("1984 George Orwell", "송성태")).auto)
        assertFalse("세트", MetaRules.match(MetaRules.query("드래곤볼 완전판 01"), cand("드래곤볼 완전판 세트(1-34권)", "토리야마 아키라")).auto)
        assertFalse("만화판", MetaRules.match(MetaRules.query("토지 1권"), cand("만화 토지 1", "박경리")).auto)
        assertEquals(true, MetaRules.sameAuthor("George Orwell", "Orwell George"))
        assertEquals(null, MetaRules.sameAuthor("", "조지 오웰"))
    }

    @Test
    fun isbnOnlyDigits() {
        assertEquals("9788937460449", MetaRules.isbn("978-89-374-6044-9"))
        assertEquals("", MetaRules.isbn("abc"))
    }
}
