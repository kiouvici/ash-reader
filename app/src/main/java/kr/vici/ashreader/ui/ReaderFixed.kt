// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.runtime.saveable.listSaver
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.vici.ashreader.data.DocViewPrefs
import kr.vici.ashreader.doc.HwpReaderDoc
import kr.vici.ashreader.doc.ReaderDoc

/** 회전·복원에도 책마다 고른 맞춤·여백 자르기를 지킨다. */
internal val DocViewSaver = listSaver<DocViewPrefs, Any>(
    save = { listOf(it.fit, it.crop) },
    restore = { DocViewPrefs(it[0] as Int, it[1] as Boolean) },
)

/** 한글 원본 열기 결과(F-28). */
internal sealed interface HwpOpen {
    data class Done(val result: Result<ReaderDoc>) : HwpOpen
    /** [limitMs] 안에 열기·첫 쪽 그리기가 끝나지 않음. */
    data object TimedOut : HwpOpen
    /** 첫 쪽이 비었는데 글자는 있음(또는 첫 쪽 렌더 실패). */
    data object Blank : HwpOpen
}

/**
 * F-28 한글 원본 보기 열기: 열기와 (처음 열 때) 첫 쪽 검사를 따로 스레드에서 하고 [limitMs](10초)까지 기다린다.
 * 넘으면 [HwpOpen.TimedOut] — 늦게 끝난 문서는 그 스레드가 닫는다. 기다리는 쪽이 취소돼도 마찬가지.
 */
internal suspend fun openHwpOriginal(open: () -> ReaderDoc, checkBlank: Boolean, limitMs: Long = 10_000L): HwpOpen {
    val box = CompletableDeferred<HwpOpen>()
    val lock = Any()
    var abandoned = false
    Thread {
        val res: HwpOpen = try {
            val d = open()
            val blank = checkBlank && d is HwpReaderDoc && runCatching { d.firstPageFailed() }.getOrDefault(false)
            if (blank) { runCatching { d.close() }; HwpOpen.Blank } else HwpOpen.Done(Result.success(d))
        } catch (e: Throwable) {
            HwpOpen.Done(Result.failure(e))
        }
        val keep = synchronized(lock) { if (abandoned) false else { box.complete(res); true } }
        if (!keep) (res as? HwpOpen.Done)?.result?.getOrNull()?.let { runCatching { it.close() } }
    }.apply { isDaemon = true; name = "hwp-open" }.start()
    fun abandon(): Boolean = synchronized(lock) { if (box.isCompleted) false else { abandoned = true; true } }
    return try {
        withTimeoutOrNull(limitMs) { box.await() } ?: if (abandon()) HwpOpen.TimedOut else box.await()
    } catch (e: CancellationException) {
        if (!abandon()) {
            // 이미 끝났으면 받은 문서를 닫는다(화면이 사라짐)
            closeCompleted(box)
        }
        throw e
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun closeCompleted(box: CompletableDeferred<HwpOpen>) {
    val r = runCatching { box.getCompleted() }.getOrNull() as? HwpOpen.Done ?: return
    r.result.getOrNull()?.let { runCatching { it.close() } }
}
