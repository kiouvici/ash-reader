// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.AutoScroll
import kr.vici.ashreader.tts.Sentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5묶음: 쪽 경계 문장 잇기(듣기), 자동 스크롤 속도(F-44). */
class Batch5Test {
    @Test
    fun sentenceAcrossPagesIsJoinedOnce() {
        val p0 = "첫 문장이다. 둘째 문장은 쪽을\n"
        val p1 = "넘어 이어진다. 셋째 문장. 넷째는 끝나지"
        val p2 = "않는다. 마지막."
        val a = Sentences.splitAcross(0, p0, null, p1)
        val b = Sentences.splitAcross(1, p1, p0, p2)
        val c = Sentences.splitAcross(2, p2, p1, null)
        assertEquals(2, a.size)
        assertTrue(a[1].spans)
        assertEquals("둘째 문장은 쪽을 넘어 이어진다.", a[1].text)
        assertEquals("넘어 이어진다.", p1.substring(a[1].start2, a[1].end2))
        assertEquals("넘어", a[1].text.substring(a[1].split).take(2))
        // 다음 쪽은 앞 쪽이 가져간 첫 문장을 빼고 시작한다
        assertEquals("셋째 문장.", b[0].text)
        assertTrue(b[1].spans)
        assertEquals(listOf("마지막."), c.map { it.text })
    }

    @Test
    fun finishedPageIsNotJoined() {
        assertFalse(Sentences.dangling("끝났다.”"))
        assertFalse(Sentences.dangling("정말?  \n"))
        assertTrue(Sentences.dangling("이어지는 중"))
        val a = Sentences.splitAcross(0, "한 문장. 두 문장!", null, "다음 쪽. 또.")
        assertFalse(a.any { it.spans })
        // 다음 쪽 문장이 하나뿐이면 잇지 않는다(그 쪽에서 그대로 읽음)
        assertFalse(Sentences.splitAcross(0, "끊긴 문장", null, "끝.").any { it.spans })
    }

    @Test
    fun autoScrollSpeedIsMonotonic() {
        var last = 0
        for (s in 1..10) { val v = AutoScroll.dpPerSec(s); assertTrue(v > last); last = v }
        assertEquals(AutoScroll.dpPerSec(1), AutoScroll.dpPerSec(-3))
        assertEquals(AutoScroll.dpPerSec(10), AutoScroll.dpPerSec(99))
    }
}
