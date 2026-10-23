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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.vici.ashreader.pagecurl.PageSource

/**
 * F-22 스크롤 보기: 쪽을 위아래로 이어 붙여 보인다(M-05 쪽 사이 빈칸 없음). 쪽 경계에는 가는 선과 작은 쪽 번호(표지)만 둔다.
 * - 이북류(다시 배치)는 화면 크기로 나눈 쪽을 그대로 잇고, 고정 쪽(PDF·DjVu·한글/Word 원본)은 폭 맞춤으로 잇는다.
 * - 조작(화면 설계서 4.3): 위 30% = 위로 한 화면, 아래 30% = 아래로 한 화면(화면 높이 90%), 가운데 = 메뉴 — 판단은 [onTap] 이 한다.
 * - 고정 쪽은 두 손가락 확대·두 번 탭 2배(F-23·F-55), 확대 배율은 오른쪽 아래에 2초 보인다(S-32).
 * - F-44 자동 스크롤: [autoDpPerSec] > 0 이면 화면 프레임마다 조금씩 내린다. 끝에 닿으면 [onAutoEnd].
 * 넘김 효과는 쓰지 않는다(F-17).
 */
@Composable
fun ScrollReader(
    source: PageSource,
    /** 쪽 [i] 의 세로/가로 비율. 모르면 null(첫 쪽 비율로 자리를 잡고, 알면 고친다). 백그라운드에서 불린다. */
    aspectOf: (Int) -> Float?,
    defaultAspect: Float,
    /** 본문 칸 최대 폭(dp). 이북류는 화면보다 좁게 둘 수 있다. */
    columnMaxDp: Float,
    zoomable: Boolean,
    colors: ReadingColors,
    startPage: Int,
    onPage: (Int) -> Unit,
    /** 누름: 화면 비율 좌표. */
    onTap: (xf: Float, yf: Float) -> Unit,
    /** 길게 누름: 쪽 [page] 와 그 쪽이 화면에서 차지하는 칸(px), 누른 곳(px). */
    onLongPress: (slot: PageSlot, x: Float, y: Float) -> Unit,
    /** 손가락으로 끌기 시작(글자 선택 지우기). */
    onUserScroll: () -> Unit,
    turnRequest: Int,
    turnForward: Boolean,
    jumpRequest: Int?,
    onJumpHandled: () -> Unit,
    /** 쪽 [p] 가 화면에 보이게(듣기 따라가기). 이미 보이면 움직이지 않는다. */
    revealRequest: Int?,
    autoDpPerSec: Int,
    onAutoEnd: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wPx = constraints.maxWidth.coerceAtLeast(1)
        val hPx = constraints.maxHeight.coerceAtLeast(1)
        val colPx = minOf(wPx.toFloat(), with(density) { columnMaxDp.dp.toPx() }).toInt().coerceAtLeast(1)
        val colLeft = (wPx - colPx) / 2f
        val count = source.pageCount
        // 쪽 비율(아는 것만). 바뀌면 그 쪽 자리 높이만 고친다.
        val aspects = remember(source) { mutableStateMapOf<Int, Float>() }
        fun heightOf(i: Int): Int = (colPx * (aspects[i] ?: defaultAspect)).toInt().coerceAtLeast(1)
        val cache = remember(source, colPx) {
            object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 shl 20)) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            }
        }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (count - 1).coerceAtLeast(0)))
        // 읽는 쪽 = 화면 위에서 1/3 높이에 걸친 쪽
        val curPage by remember(state) {
            derivedStateOf {
                val probe = state.layoutInfo.viewportStartOffset + hPx / 3
                state.layoutInfo.visibleItemsInfo.firstOrNull { probe >= it.offset && probe < it.offset + it.size }?.index
                    ?: state.firstVisibleItemIndex
            }
        }
        val pageCb by rememberUpdatedState(onPage)
        LaunchedEffect(curPage) { pageCb(curPage) }

        // 손가락으로 끌면 선택 지우기
        val userScroll by rememberUpdatedState(onUserScroll)
        LaunchedEffect(state) {
            state.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScroll() }
        }

        var zoom by remember { mutableFloatStateOf(1f) }
        var panX by remember { mutableFloatStateOf(0f) }
        var zoomShownAt by remember { mutableIntStateOf(0) }
        fun clampPan() { val m = (zoom - 1f) * wPx / 2f; panX = panX.coerceIn(-m, m) }

        // 처음 그릴 때의 요청 번호는 이미 처리된 것(보기를 바꿔 다시 그려도 넘기지 않게)
        val turn0 = remember { turnRequest }
        LaunchedEffect(turnRequest) {
            if (turnRequest > turn0) state.animateScrollBy((if (turnForward) 1f else -1f) * hPx * 0.9f / zoom)
        }
        LaunchedEffect(jumpRequest) {
            val j = jumpRequest ?: return@LaunchedEffect
            state.scrollToItem(j.coerceIn(0, (count - 1).coerceAtLeast(0)))
            onJumpHandled()
        }
        LaunchedEffect(revealRequest) {
            val r = revealRequest ?: return@LaunchedEffect
            val vis = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == r }
            val fully = vis != null && vis.offset >= 0 && vis.offset + vis.size <= hPx
            if (vis == null) state.animateScrollToItem(r.coerceIn(0, (count - 1).coerceAtLeast(0)))
            else if (!fully && vis.offset > hPx / 2) state.animateScrollBy((vis.offset - hPx / 6).toFloat())
        }
        // F-44 자동 스크롤
        val autoEnd by rememberUpdatedState(onAutoEnd)
        LaunchedEffect(autoDpPerSec, state) {
            if (autoDpPerSec <= 0) return@LaunchedEffect
            val pxPerSec = with(density) { autoDpPerSec.dp.toPx() }
            var last = 0L
            var carry = 0f
            while (true) {
                val t = withFrameNanos { it }
                if (last != 0L) {
                    carry += pxPerSec * ((t - last) / 1e9f).coerceAtMost(0.1f)
                    if (carry >= 1f) {
                        val step = carry.toInt().toFloat()
                        carry -= step
                        if (!state.isScrollInProgress) state.dispatchRawDelta(step)
                    }
                    if (!state.canScrollForward) { autoEnd(); break }
                }
                last = t
            }
        }
        LaunchedEffect(zoomShownAt) { if (zoomShownAt > 0) { delay(2000); zoomShownAt = 0 } }

        val tap by rememberUpdatedState(onTap)
        val longPress by rememberUpdatedState(onLongPress)
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (!zoomable) Modifier else Modifier.pointerInput(wPx) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val down = ev.changes.count { it.pressed }
                                if (down >= 2) {
                                    zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 4f)
                                    panX += ev.calculatePan().x
                                    clampPan()
                                    zoomShownAt++
                                    ev.changes.forEach { it.consume() }
                                } else if (down == 1 && zoom > 1f) {
                                    panX += ev.changes.first().positionChange().x
                                    clampPan()
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    },
                )
                .pointerInput(wPx, hPx, zoomable) {
                    detectTapGestures(
                        onDoubleTap = if (!zoomable) null else ({ o: Offset ->
                            if (zoom > 1f) { zoom = 1f; panX = 0f } else { zoom = 2f; panX = (wPx / 2f - o.x); clampPan() }
                            zoomShownAt++
                        }),
                        onLongPress = { o ->
                            if (zoom > 1f) return@detectTapGestures
                            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { o.y >= it.offset && o.y < it.offset + it.size } ?: return@detectTapGestures
                            longPress(PageSlot(info.index, colLeft, info.offset.toFloat(), colPx.toFloat(), info.size.toFloat()), o.x, o.y)
                        },
                    ) { o -> tap(o.x / wPx, o.y / hPx) }
                },
        ) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { transformOrigin = TransformOrigin(0.5f, 0.5f); scaleX = zoom; scaleY = zoom; translationX = panX },
            ) {
                items(count, key = { it }) { i ->
                    val hItem = heightOf(i)
                    val key = "$i:$colPx:$hItem"
                    LaunchedEffect(i, source) {
                        if (aspects[i] == null) {
                            val a = withContext(Dispatchers.IO) { runCatching { aspectOf(i) }.getOrNull() }
                            if (a != null && a > 0.05f && a < 20f) aspects[i] = a
                        }
                    }
                    val img by produceState<ImageBitmap?>(cache.get(key)?.asImageBitmap(), key, cache) {
                        if (value != null) return@produceState
                        value = withContext(Dispatchers.IO) {
                            try {
                                source.render(i, colPx, hItem).also { cache.put(key, it) }.asImageBitmap()
                            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { cache.evictAll(); null }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(with(density) { hItem.toDp() })
                            .semantics { contentDescription = "${i + 1}쪽" },
                    ) {
                        val b = img
                        if (b != null) Image(
                            b, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium,
                            modifier = Modifier.align(Alignment.TopCenter).width(with(density) { colPx.toDp() }).height(with(density) { hItem.toDp() }),
                        )
                        // 쪽 경계 표지: 맨 위 가는 선 + 오른쪽 작은 쪽 번호(빈칸 없이 잇는다, M-05)
                        if (i > 0) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(colors.lineC))
                        Text(
                            "${i + 1}", fontSize = 12.sp, color = colors.subC,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)
                                .background(colors.bgC.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        if (zoomable && zoomShownAt > 0) Text(
            "${(zoom * 100).toInt()}%", fontSize = 12.sp, color = colors.fgC,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).background(colors.barC, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
