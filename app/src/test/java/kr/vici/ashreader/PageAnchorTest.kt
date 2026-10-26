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

    /** 쪽 경계에 걸친 줄이 앞 쪽 끝과 뒤 쪽 첫머리 두 쪽 글에 다 들어가도(한글 표 문서) 첫머리에 있는 뒤 쪽을 고른다. */
    @Test fun duplicatedBoundaryLine_prefersPageStart() {
        val line = "제4항 띄어쓰기는 단어별로 하되 조사는 앞말에 붙여 쓴다"
        val pages = listOf(
            "앞 쪽 글 ".repeat(30),
            "둘째 쪽 본문입니다 ".repeat(20) + line, // 경계 줄이 끝에 걸쳐 들어감
            line + " 다음 내용이 이어집니다 ".repeat(20),
            "넷째 쪽 ".repeat(30),
        )
        val a = PageAnchor.of(pages[2])!!
        // 비율로 짐작한 쪽이 앞 쪽(1)이어도 첫머리에 있는 2쪽
        assertEquals(2, PageAnchor.find(a, 1, pages.size, radius = 3) { pages[it] })
    }

    /**
     * 같은 문단이 번호만 바꿔 되풀이되는 책(시험용 EPUB): 쪽 첫머리가 되풀이 문장 한가운데여도, 앞뒤 쪽에서 한 번만 나오는
     * 길이까지 늘린 자리 표시로 세로↔가로 3번 왕복 뒤 같은 쪽.
     */
    @Test fun repetitiveBook_captureIsUnique() {
        val body = "그는 창밖을 오래 바라보았다. 비는 그칠 기색이 없었고, 골목 끝 가로등은 젖은 길 위에 노란 빛을 길게 늘어뜨리고 있었다."
        val text = (1..60).joinToString(" ") { "${it}문단. $body" }
        // 쪽 = 글자 수로 자른 배치(문단 중간에서 끊김)
        fun layout(perPage: Int) = text.chunked(perPage)
        val portrait = layout(230); val landscape = layout(410)
        for (p in listOf(2, 3, 5, 9, 14)) {
            val a = PageAnchor.capture(p, portrait.size, radius = 8) { portrait.getOrNull(it) }!!
            var cur = p
            repeat(3) {
                val estL = cur * landscape.size / portrait.size
                val l = PageAnchor.find(a, estL, landscape.size, radius = 8) { landscape.getOrNull(it) }!!
                val estP = l * portrait.size / landscape.size
                cur = PageAnchor.find(a, estP, portrait.size, radius = 8) { portrait.getOrNull(it) }!!
                assertEquals("되풀이 책 $p 쪽 왕복", p, cur)
            }
        }
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
