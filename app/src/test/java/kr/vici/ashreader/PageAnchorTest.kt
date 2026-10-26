// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.PageAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 작업 11: 다시 나눈 뒤 읽던 자리를 본문 글자로 찾기. */
class PageAnchorTest {
    /** 문장 n개를 쪽마다 [per]개씩 나눈 책(쪽 = 문장 묶음). 회전 = 쪽마다 문장 수가 바뀌는 것으로 흉내 낸다. */
    private val sentences = (1..400).map { "${it}번째 문장입니다 그는 창밖을 오래 바라보았다 $it." }
    private fun pages(per: Int) = sentences.chunked(per).map { it.joinToString(" ") }

    /**
     * 세로(쪽당 5문장) → 가로(쪽당 9문장) → 세로를 3번: 자리 표시는 회전으로 옮겨진 쪽에서 새로 잡지 않으므로
     * (ReaderScreen heldPage) 처음 쪽의 첫 글자로 계속 찾아 정확히 처음 쪽으로 돌아온다(가로 쪽 첫 글자로 잡으면 1~2쪽씩 밀림).
     */
    @Test fun roundTrips_backToSamePage() {
        val portrait = pages(5); val landscape = pages(9)
        for (p in listOf(0, 1, 3, 4, 7, 17, 40, portrait.size - 1)) {
            val a = PageAnchor.of(portrait[p])!!
            var cur = p
            repeat(3) {
                // 비율로 짐작한 쪽(앞쪽에서 반올림으로 깎이는 경우를 일부러 2쪽 어긋나게)
                val estL = (cur * landscape.size / portrait.size - 2).coerceAtLeast(0)
                val l = PageAnchor.find(a, estL, landscape.size, radius = 8) { landscape[it] }!!
                assertEquals("가로에서 $p 쪽 첫 문장이 든 쪽", (p * 5) / 9, l)
                val estP = (l * portrait.size / landscape.size - 2).coerceAtLeast(0)
                cur = PageAnchor.find(a, estP, portrait.size, radius = 8) { portrait[it] }!!
                assertEquals("세로로 돌아와 같은 쪽", p, cur)
            }
        }
    }

    /** 자리 표시가 쪽 끝에 걸쳐 다음 쪽으로 이어져도 시작한 쪽을 찾는다. */
    @Test fun anchorSpanningPageEnd() {
        val pages = listOf("가나다라마바사아자차", "카타파하거너더러머버", "서어저처커터퍼허")
        val a = PageAnchor.norm("아자차카타파하거너더").take(10)
        assertEquals(0, PageAnchor.find(a, 2, pages.size, radius = 3) { pages[it] })
    }

    /** 띄어쓰기·줄바꿈이 배치마다 달라도 같은 글자로 본다. */
    @Test fun ignoresWhitespace() {
        val a = PageAnchor.of("그는 창밖을\n오래 바라보았다. 비는")!!
        assertEquals(1, PageAnchor.find(a, 0, 2, radius = 2) { listOf("다른 쪽 글입니다 여기는", "그는 창 밖을 오래\n바라보았다. 비는 그칠") [it] })
    }

    /** 글자가 거의 없는 쪽(그림)은 자리 표시를 만들지 않고, 못 찾으면 null(짐작한 쪽을 쓴다). */
    @Test fun noAnchorAndNotFound() {
        assertNull(PageAnchor.of("  그림 "))
        assertNull(PageAnchor.find("없는글자없는글자", 1, 3, radius = 3) { "아무 글" })
    }
}
