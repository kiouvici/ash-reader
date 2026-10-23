// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 다른 앱에서 받은 문서(VIEW·SEND) 받은편지함(I-714).
 *
 * - 받을 때마다 번호([Item.seq])를 붙인다. 같은 파일을 다시 받아도(카카오톡에서 같은 문서를 또 '열기') 새 번호라 다시 연다.
 * - 처리를 끝낸 뒤에만 [done]으로 비운다. 처리 중에 화면이 회전·재생성돼도 남아 있어 다시 처리한다(처리를 시작할 때
 *   비우면, 복사 중에 Activity 가 다시 만들어질 때 문서가 사라졌다).
 * - [save]로 SavedStateHandle 에 남겨 프로세스가 죽었다 살아나도 이어서 연다. 마지막으로 끝낸 번호도 남겨,
 *   이미 처리한 것을 복원해 다시 여는 일(예전에 연 문서가 다시 열리던 문제)이 없게 한다.
 */
class IntentInbox<T>(restored: Item<T>?, lastDone: Long, private val save: (Item<T>?, Long) -> Unit) {
    data class Item<T>(val seq: Long, val uris: List<T>)

    private var done = lastDone
    private var seq = maxOf(lastDone, restored?.seq ?: 0L)
    private val _state = MutableStateFlow(restored?.takeIf { it.seq > lastDone && it.uris.isNotEmpty() })
    val state: StateFlow<Item<T>?> = _state

    /** 받은 문서를 넣는다. 빈 목록은 무시. 넣은 항목을 돌려준다. */
    fun offer(uris: List<T>): Item<T>? {
        if (uris.isEmpty()) return null
        val it = Item(++seq, uris)
        _state.value = it
        save(it, done)
        return it
    }

    /** [seq] 처리를 끝냈다(성공·실패 모두). 그 사이 새로 받은 것이 있으면 그대로 둔다. */
    fun done(seq: Long) {
        if (seq > done) done = seq
        val cur = _state.value
        if (cur != null && cur.seq <= seq) {
            _state.value = null
            save(null, done)
        } else save(cur, done)
    }

    val lastHandled: Long get() = done
}
