// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.PageNotReady
import kr.vici.ashreader.engine.comic.Webtoon

/** 웹툰 띠 하나(원본 쪽 [page] 의 [y0]~[y1] 줄, 화면 높이 [h] px). [y1] == 0 = 크기를 아직 모르는 쪽(자리만). */
private data class Strip(val page: Int, val y0: Int, val y1: Int, val h: Int, val lastOfPage: Boolean)

/**
 * F-53 웹툰 보기(세로 연속 스크롤). 폭 맞춤, 그림 사이 간격(없음/작게), 좌우 여백.
 * 메모리: 쪽을 화면 높이 정도의 띠로 나눠 보이는 띠만 디코딩한다(큰 그림은 BitmapRegionDecoder 영역 디코딩). 띠 캐시는 앱 메모리의 1/8(F-58).
 * 조작(화면 설계서 4.3): 위 30% = 위로 한 화면, 아래 30% = 아래로 한 화면(화면 높이 90% 이동), 가운데 = 메뉴. 두 손가락 = 확대, 두 번 탭 = 2배 ↔ 원래.
 */
@Composable
fun ComicWebtoonReader(
    cs: ComicSession,
    startPage: Int,
    colors: ReadingColors,
    menuVisible: Boolean,
    onToggleMenu: () -> Unit,
    onPage: (Int) -> Unit,
    turnRequest: Int,
    turnForward: Boolean,
    jumpRequest: Int? = null,
    onJumpHandled: () -> Unit = {},
) {
    val doc = cs.doc
    val p = cs.prefs
    val ext = doc.extract?.collectAsState()?.value
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wPx = constraints.maxWidth.coerceAtLeast(1)
        val hPx = constraints.maxHeight.coerceAtLeast(1)
        val marginPx = wPx * p.margin / 100
        val dispW = (wPx - marginPx * 2).coerceAtLeast(1)
        val sizes by produceState<List<Pair<Int, Int>?>?>(null, doc, ext?.done, ext?.finished) {
            value = withContext(Dispatchers.IO) { doc.sizes() }
        }
        val list = sizes
        val stripH = (hPx * 3 / 4).coerceIn(256, 2048)
        val strips = remember(list, dispW, stripH, hPx) {
            val out = ArrayList<Strip>()
            list?.forEachIndexed { i, s ->
                if (s == null || s.first <= 0) { out += Strip(i, 0, 0, hPx, true); return@forEachIndexed }
                val parts = Webtoon.strips(s.first, s.second, dispW, stripH)
                parts.forEachIndexed { k, r ->
                    val h = ((r[1] - r[0]).toLong() * dispW / s.first).toInt().coerceAtLeast(1)
                    out += Strip(i, r[0], r[1], h, k == parts.size - 1)
                }
            }
            out
        }
        val firstOf = remember(strips) {
            val a = IntArray(doc.sourceCount) { -1 }
            strips.forEachIndexed { idx, s -> if (a.getOrNull(s.page) == -1) a[s.page] = idx }
            a
        }
        // 띠 캐시(그림 보기 값이 바뀌면 새로)
        val cache = remember(doc.look, dispW) {
            object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 shl 20)) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            }
        }
        val state = rememberLazyListState()
        var positioned by remember { mutableStateOf(false) }
        LaunchedEffect(strips) {
            if (!positioned && strips.isNotEmpty()) {
                val i = firstOf.getOrNull(startPage.coerceIn(0, (doc.sourceCount - 1).coerceAtLeast(0))) ?: -1
                if (i >= 0) state.scrollToItem(i)
                positioned = true
            }
        }
        val curPage by remember(strips) { derivedStateOf { strips.getOrNull(state.firstVisibleItemIndex)?.page ?: startPage } }
        val pageCb by rememberUpdatedState(onPage)
        LaunchedEffect(curPage, positioned) { if (positioned) pageCb(curPage) }
        LaunchedEffect(jumpRequest, strips) {
            val j = jumpRequest ?: return@LaunchedEffect
            if (strips.isEmpty()) return@LaunchedEffect
            val i = firstOf.getOrNull(j.coerceIn(0, (doc.sourceCount - 1).coerceAtLeast(0))) ?: -1
            if (i >= 0) state.scrollToItem(i)
            onJumpHandled()
        }

        // 확대(두 손가락·두 번 탭). 좌우는 확대했을 때만 끌어서 옮긴다.
        var zoom by remember { mutableFloatStateOf(1f) }
        var panX by remember { mutableFloatStateOf(0f) }
        fun clampPan() { val m = (zoom - 1f) * wPx / 2f; panX = panX.coerceIn(-m, m) }
        LaunchedEffect(turnRequest) {
            if (turnRequest > 0) state.animateScrollBy((if (turnForward) 1f else -1f) * hPx * 0.9f / zoom)
        }

        val hq = p.hq
        val gapPx = with(density) { (if (p.gap == 1) 8.dp else 0.dp).toPx() }.toInt()
        Box(
            Modifier.fillMaxSize()
                .pointerInput(wPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val down = ev.changes.count { it.pressed }
                            if (down >= 2) {
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 4f)
                                panX += ev.calculatePan().x
                                clampPan()
                                ev.changes.forEach { it.consume() }
                            } else if (down == 1 && zoom > 1f) {
                                // 한 손가락: 위아래는 목록이, 좌우는 여기서
                                panX += ev.changes.first().positionChange().x
                                clampPan()
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(menuVisible, hPx) {
                    detectTapGestures(
                        onDoubleTap = { o ->
                            if (zoom > 1f) { zoom = 1f; panX = 0f } else { zoom = 2f; panX = (wPx / 2f - o.x) * 1f; clampPan() }
                        },
                    ) { o ->
                        val yf = o.y / hPx
                        when {
                            menuVisible -> onToggleMenu()
                            yf < 0.3f -> scope.launch { state.animateScrollBy(-hPx * 0.9f / zoom) }
                            yf > 0.7f -> scope.launch { state.animateScrollBy(hPx * 0.9f / zoom) }
                            else -> onToggleMenu()
                        }
                    }
                },
        ) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { transformOrigin = TransformOrigin(0.5f, 0.5f); scaleX = zoom; scaleY = zoom; translationX = panX }
                    .semantics { contentDescription = "웹툰 보기 ${curPage + 1}쪽" },
            ) {
                items(strips.size, key = { i -> strips[i].let { "${it.page}:${it.y0}:${it.y1}" } }) { i ->
                    val s = strips[i]
                    val key = "${s.page}:${s.y0}:${s.y1}"
                    val ready = doc.ready(s.page) && s.y1 > 0
                    val img by produceState<ImageBitmap?>(cache.get(key)?.asImageBitmap(), key, ready, cache) {
                        if (value != null || !ready) return@produceState
                        value = withContext(Dispatchers.IO) {
                            try {
                                doc.strip(s.page, s.y0, s.y1, dispW)?.also { cache.put(key, it) }?.asImageBitmap()
                            } catch (e: PageNotReady) { null } catch (e: Exception) { null } catch (e: OutOfMemoryError) { cache.evictAll(); null }
                        }
                    }
                    val hDp = with(density) { s.h.toDp() }
                    val padB = with(density) { (if (s.lastOfPage) gapPx else 0).toDp() }
                    Box(Modifier.fillMaxWidth().padding(bottom = padB).padding(horizontal = with(density) { marginPx.toDp() }).height(hDp)) {
                        val b = img
                        if (b != null) {
                            Image(b, contentDescription = null, contentScale = ContentScale.FillBounds,
                                filterQuality = if (hq) FilterQuality.High else FilterQuality.Low, modifier = Modifier.fillMaxSize())
                        } else if (!ready && s.y0 == 0) {
                            Text(
                                if (ext != null && !ext.finished) "쪽 준비 중… ${ext.done} / ${ext.total}" else "${s.page + 1}쪽을 준비하지 못했어요",
                                fontSize = 14.sp, color = colors.subC, modifier = Modifier.align(Alignment.Center),
                            )
                        } else if (s.y0 == 0) {
                            CircularProgressIndicator(color = colors.accentC, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
        if (zoom > 1f && !menuVisible) Text(
            "${(zoom * 100).toInt()}%", fontSize = 12.sp, color = colors.subC,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}
