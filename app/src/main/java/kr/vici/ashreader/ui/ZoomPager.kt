// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.ComicBookPrefs

/** 쪽 그림 하나: [nativeW] = 원본 화소 폭(원본 맞춤 배율). */
class ZoomImage(val bmp: Bitmap, val nativeW: Int)

/**
 * 맞춤 방식·확대로 보는 쪽 화면(F-54 맞춤 방식, F-55 두 번 탭 2배, F-23 두 손가락 확대, S-32 배율 표시).
 * - 맞춤: 화면 / 폭 / 높이 / 원본. 폭 맞춤 등으로 화면보다 크면 읽기 방향 시작 모서리(왼→오 = 왼쪽 위, 오→왼 = 오른쪽 위)에서 시작한다.
 * - '다음'(탭·볼륨 키·자동 넘김)은 쪽 안에서 먼저 화면 90%씩 옮기고, 끝에 닿으면 다음 쪽. '이전'은 반대(이전 쪽은 끝 모서리에서 시작).
 * - 끌기 = 옮기기, 두 손가락 = 확대(1~4배), 두 번 탭 = 누른 곳 2배 ↔ 맞춤. 배율이 바뀌면 오른쪽 아래 "200%"를 2초 보인다.
 * - [onExit] 가 있으면 '확대해 보기' 덧화면으로 쓴다: 처음 [initialZoom] 배로 [initialFocus] 를 확대하고, 맞춤 배율로 돌아오면 닫힌다.
 * 넘김 효과(F-15)는 쓰지 않는다(확대 상태에서는 쪽 넘김을 가장자리 탭·볼륨 키로만 — F-08).
 */
@Composable
fun ZoomPager(
    count: Int,
    startIndex: Int,
    fit: Int,
    rtl: Boolean,
    colors: ReadingColors,
    /** 자리 [i] 를 폭 [w] 근처로 그린 그림. 백그라운드에서 불린다. 못 그리면 null. */
    load: (i: Int, w: Int) -> ZoomImage?,
    /** 미리 풀기(F-58). 백그라운드에서 불린다. */
    prefetch: ((i: Int, forward: Boolean, w: Int) -> Unit)? = null,
    tapAction: (xf: Float, yf: Float) -> TapAction,
    menuVisible: Boolean,
    onToggleMenu: () -> Unit,
    onPage: (Int) -> Unit,
    onLongPress: ((Int) -> Unit)? = null,
    turnRequest: Int,
    turnForward: Boolean,
    jumpRequest: Int? = null,
    onJumpHandled: () -> Unit = {},
    /** 바뀌면 지금 쪽을 다시 그린다(그림 보기 값·AI 결과). */
    reloadKey: Any? = null,
    initialZoom: Float = 1f,
    initialFocus: Offset? = null,
    onExit: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        // 확대해도 흐리지 않게 화면 폭의 2배까지 그린다(화소 상한은 공급자가 둔다)
        val renderW = (wPx * 2f).toInt()
        var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (count - 1).coerceAtLeast(0))) }
        var enterFromEnd by remember { mutableStateOf(false) }
        var lastForward by remember { mutableStateOf(true) }
        var shown by remember { mutableStateOf<Pair<Int, ZoomImage>?>(null) }
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        var uz by remember { mutableFloatStateOf(1f) }
        var ox by remember { mutableFloatStateOf(0f) }
        var oy by remember { mutableFloatStateOf(0f) }
        var zoomTick by remember { mutableIntStateOf(0) }
        var firstPlaced by remember { mutableStateOf(false) }
        val pageCb by rememberUpdatedState(onPage)
        LaunchedEffect(index) { pageCb(index) }

        fun base(z: ZoomImage): Float {
            val bw = z.bmp.width.toFloat(); val bh = z.bmp.height.toFloat()
            return when (fit) {
                ComicBookPrefs.FIT_WIDTH -> wPx / bw
                ComicBookPrefs.FIT_HEIGHT -> hPx / bh
                ComicBookPrefs.FIT_ORIGINAL -> z.nativeW / bw
                else -> minOf(wPx / bw, hPx / bh)
            }
        }
        fun size(): Pair<Float, Float> {
            val z = shown?.second ?: return wPx to hPx
            val s = base(z) * uz
            return z.bmp.width * s to z.bmp.height * s
        }
        fun clamp() {
            val (cw, ch) = size()
            ox = if (cw <= wPx) (wPx - cw) / 2f else ox.coerceIn(wPx - cw, 0f)
            oy = if (ch <= hPx) (hPx - ch) / 2f else oy.coerceIn(hPx - ch, 0f)
        }
        /** 쪽 시작(또는 끝) 모서리에 놓는다. */
        fun place(fromEnd: Boolean) {
            val (cw, ch) = size()
            ox = when {
                cw <= wPx -> (wPx - cw) / 2f
                rtl != fromEnd -> wPx - cw // 오→왼 시작 = 오른쪽, 왼→오 끝 = 오른쪽
                else -> 0f
            }
            oy = if (ch <= hPx) (hPx - ch) / 2f else if (fromEnd) hPx - ch else 0f
        }

        // 쪽 그림 읽기(앞 쪽 그림은 새 그림이 올 때까지 둔다)
        LaunchedEffect(index, reloadKey, renderW) {
            val i = index
            val z = withContext(Dispatchers.IO) { runCatching { load(i, renderW) }.getOrNull() } ?: return@LaunchedEffect
            val samePage = shown?.first == i
            shown = i to z
            image = z.bmp.asImageBitmap()
            if (!samePage) {
                if (!firstPlaced && initialFocus != null && initialZoom > 1f) {
                    uz = 1f; place(false)
                    // 누른 곳을 그대로 두고 확대
                    val f = initialFocus
                    val k = initialZoom
                    ox = f.x - (f.x - ox) * k; oy = f.y - (f.y - oy) * k; uz = k
                    clamp(); zoomTick++
                } else {
                    if (onExit == null) uz = 1f
                    place(enterFromEnd)
                }
                firstPlaced = true
            } else clamp()
            if (prefetch != null) launch(Dispatchers.IO) { runCatching { prefetch(i, lastForward, renderW) } }
        }
        LaunchedEffect(jumpRequest) {
            val j = jumpRequest ?: return@LaunchedEffect
            enterFromEnd = false
            index = j.coerceIn(0, (count - 1).coerceAtLeast(0))
            onJumpHandled()
        }
        LaunchedEffect(zoomTick) { if (zoomTick > 0) { delay(2000); zoomTick = 0 } }

        suspend fun slide(tx: Float, ty: Float) {
            val sx = ox; val sy = oy
            animate(0f, 1f, animationSpec = tween(220)) { v, _ -> ox = sx + (tx - sx) * v; oy = sy + (ty - sy) * v }
        }
        fun go(forward: Boolean) {
            lastForward = forward
            val (cw, ch) = size()
            val more = if (forward) oy + ch > hPx + 1f else oy < -1f
            if (more) {
                val ty = if (forward) maxOf(oy - hPx * 0.9f, hPx - ch) else minOf(oy + hPx * 0.9f, 0f)
                scope.launch { slide(ox, ty) }
                return
            }
            // 가로로 남은 쪽(높이·원본 맞춤): 읽는 방향으로 한 화면
            val towardRight = forward != rtl
            val moreX = if (towardRight) ox + cw > wPx + 1f else ox < -1f
            if (cw > wPx && moreX) {
                val tx = if (towardRight) maxOf(ox - wPx * 0.9f, wPx - cw) else minOf(ox + wPx * 0.9f, 0f)
                val ty = if (ch <= hPx) oy else if (forward) 0f else hPx - ch
                scope.launch { slide(tx, ty) }
                return
            }
            val np = index + if (forward) 1 else -1
            if (np in 0 until count) { enterFromEnd = !forward; index = np }
        }
        // 처음 그릴 때의 요청 번호는 이미 처리된 것(덧화면을 열거나 보기를 바꿔도 넘기지 않게)
        val turn0 = remember { turnRequest }
        LaunchedEffect(turnRequest) { if (turnRequest > turn0) go(turnForward) }

        val exit by rememberUpdatedState(onExit)
        val tapOf by rememberUpdatedState(tapAction)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(wPx, hPx) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            val nz = (uz * zoomChange).coerceIn(1f, 4f)
                            val k = nz / uz
                            ox = centroid.x - (centroid.x - ox) * k; oy = centroid.y - (centroid.y - oy) * k
                            uz = nz; zoomTick++
                        }
                        ox += pan.x; oy += pan.y
                        clamp()
                        if (uz <= 1.01f && zoomChange < 1f) exit?.invoke()
                    }
                }
                .pointerInput(wPx, hPx, menuVisible) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            if (uz > 1.01f) {
                                uz = 1f; clamp(); zoomTick++
                                exit?.invoke()
                            } else {
                                val k = 2f
                                ox = p.x - (p.x - ox) * k; oy = p.y - (p.y - oy) * k; uz = k
                                clamp(); zoomTick++
                            }
                        },
                        onLongPress = { onLongPress?.invoke(index) },
                    ) { p ->
                        if (menuVisible) { onToggleMenu(); return@detectTapGestures }
                        when (tapOf(p.x / wPx, p.y / hPx)) {
                            TapAction.NEXT -> go(true)
                            TapAction.PREV -> go(false)
                            TapAction.MENU -> onToggleMenu()
                            TapAction.NONE -> {}
                        }
                    }
                }
                .semantics { contentDescription = "${index + 1}쪽" },
        ) {
            val img = image
            val z = shown?.second
            if (img != null && z != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = base(z) * uz
                    drawImage(
                        img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((img.width * s).roundToInt().coerceAtLeast(1), (img.height * s).roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else CircularProgressIndicator(color = colors.accentC, modifier = Modifier.align(Alignment.Center))
        }
        if (zoomTick > 0) Text(
            "${(uz * 100).toInt()}%", fontSize = 12.sp, color = colors.fgC,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).background(colors.barC, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
