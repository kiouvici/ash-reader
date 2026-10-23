// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.engine.comic.PagePanels
import kr.vici.ashreader.engine.comic.PanelFraming
import kr.vici.ashreader.engine.comic.ReadingDirection

/**
 * S-34 칸 단위 보기 (F-72, D-007). 눌러서 확대(기본) / 자동 순서.
 * 탭 규칙(화면 설계서 4.3): 위 12% 띠 = 메뉴, 좌우 15% = 이전/다음 칸, 칸 위 = 확대 ↔ 쪽 전체.
 * 두 번 탭 = 누른 곳 중심 2배 확대 ↔ 원래대로(M-12). 확대 중에는 끌어서 옮긴다. 쪽·칸이 바뀌면 풀린다.
 * [upscale] = AI 화질 개선(켜져 있을 때). 원본을 먼저 보여 주고 2배 결과가 나오면 바꿔 낀다.
 */
@Composable
fun ComicPanelReader(
    doc: ComicReaderDoc,
    startPage: Int,
    autoDefault: Boolean,
    colors: ReadingColors,
    menuVisible: Boolean,
    onToggleMenu: () -> Unit,
    onPage: (Int) -> Unit,
    turnRequest: Int,
    turnForward: Boolean,
    upscale: ComicUpscale? = null,
    /** 목차·쪽 이동 막대에서 고른 쪽(처리 후 [onJumpHandled]). */
    jumpRequest: Int? = null,
    onJumpHandled: () -> Unit = {},
    /** 길게 누름(F-59): 원본 쪽 번호. */
    onLongPress: (Int) -> Unit = {},
) {
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))) }
    var sel by remember { mutableIntStateOf(if (autoDefault) 0 else -1) }
    var auto by remember { mutableStateOf(autoDefault) }
    val dir = if (doc.rtl) ReadingDirection.RTL else ReadingDirection.LTR
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val readySet = upscale?.ready?.collectAsState()?.value ?: emptySet()
        val upHere = page in readySet
        // solid CBR: 풀리면 다시 읽는다(N-02)
        doc.extract?.collectAsState()?.value
        val pageReady = doc.ready(page)
        var lastForward by remember { mutableStateOf(true) }
        val loaded by produceState<Pair<Bitmap, PagePanels>?>(null, page, upHere, doc.look, pageReady) {
            val w = wPx.toInt().coerceAtLeast(1)
            val p0 = page
            value = withContext(Dispatchers.IO) {
                runCatching {
                    // 2배 결과는 칸 확대에 쓰도록 화면 폭 2배까지 남긴다. 미리 풀어 둔 그림(F-58)이 있으면 그것부터.
                    val b = (if (upHere) upscale?.load(p0, w * 2)?.let { doc.withLook(it) } else null)
                        ?: doc.takeAhead("panel", p0, w)
                        ?: doc.withLook(doc.pageBitmap(p0, w))
                    b to doc.panels(p0, dir)
                }.getOrNull()
            }
            // F-58: 넘김 방향으로 2쪽, 반대로 1쪽(칸 인식도 같이 해 둔다)
            withContext(Dispatchers.IO) {
                runCatching {
                    doc.prefetch("panel", p0, lastForward, w, doc.pageCount) { i -> doc.withLook(doc.pageBitmap(i, w)).also { runCatching { doc.panels(i, dir) } } }
                }
            }
        }
        LaunchedEffect(page) { onPage(page) }
        LaunchedEffect(jumpRequest) {
            val j = jumpRequest ?: return@LaunchedEffect
            page = j.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
            sel = if (auto) 0 else -1
            onJumpHandled()
        }
        LaunchedEffect(page, upscale) { upscale?.request(page, wPx.toInt(), hPx.toInt()) }

        val bmp = loaded?.first
        val pp = loaded?.second
        // 쪽 그림을 화면에 맞춘 위치
        val k0 = if (bmp == null) 1f else minOf(wPx / bmp.width, hPx / bmp.height)
        val ox = if (bmp == null) 0f else (wPx - bmp.width * k0) / 2f
        val oy = if (bmp == null) 0f else (hPx - bmp.height * k0) / 2f
        val f = if (bmp == null || pp == null || pp.width == 0) 1f else bmp.width.toFloat() / pp.width
        val rects = pp?.panels?.map { r ->
            androidx.compose.ui.geometry.Rect(ox + r.x * f * k0, oy + r.y * f * k0, ox + r.r * f * k0, oy + r.b * f * k0)
        }.orEmpty()
        val n = rects.size

        fun go(forward: Boolean) {
            lastForward = forward
            if (n == 0 || (sel < 0 && !auto)) {
                val np = page + if (forward) 1 else -1
                if (np in 0 until doc.pageCount) { page = np; sel = if (auto) (if (forward) 0 else Int.MAX_VALUE) else -1 }
                return
            }
            val ns = sel + if (forward) 1 else -1
            when {
                ns in 0 until n -> sel = ns
                forward && page + 1 < doc.pageCount -> { page += 1; sel = 0 }
                !forward && page > 0 -> { page -= 1; sel = Int.MAX_VALUE }
            }
        }
        // 이전 쪽으로 갔을 때 마지막 칸 선택
        LaunchedEffect(loaded) { if (sel == Int.MAX_VALUE && loaded != null) sel = if (n > 0) n - 1 else -1 }

        LaunchedEffect(turnRequest) { if (turnRequest > 0) go(turnForward) }

        val target = rects.getOrNull(sel)
        // 칸 맞춤(7C): 가로로 긴 칸은 폭 맞춤(좌우 2% 여백), 세로로 긴 칸은 높이 맞춤. 계산은 PanelFraming(단위 시험)
        val fit = target?.let { PanelFraming.fit(it.left, it.top, it.right, it.bottom, wPx, hPx) }
        val z = fit?.scale ?: 1f
        val tx = fit?.tx ?: 0f
        val ty = fit?.ty ?: 0f
        val az by animateFloatAsState(z, tween(250), label = "z")
        val atx by animateFloatAsState(tx, tween(250), label = "tx")
        val aty by animateFloatAsState(ty, tween(250), label = "ty")

        // 두 번 탭 확대(사용자 확대): 화면 좌표 = u + uz × 내용 좌표
        var uz by remember { mutableFloatStateOf(1f) }
        var zoomTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(zoomTick) { if (zoomTick > 0) { delay(2000); zoomTick = 0 } }
        var ux by remember { mutableFloatStateOf(0f) }
        var uy by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(page, sel) { uz = 1f; ux = 0f; uy = 0f }
        fun clampPan() {
            ux = ux.coerceIn(wPx - wPx * uz, 0f)
            uy = uy.coerceIn(hPx - hPx * uz, 0f)
        }
        val auz by animateFloatAsState(uz, tween(200), label = "uz")
        val aux by animateFloatAsState(ux, tween(200), label = "ux")
        val auy by animateFloatAsState(uy, tween(200), label = "uy")

        Box(
            Modifier.fillMaxSize()
                .pointerInput(rects, sel, auto, page, doc.rtl) {
                    detectTapGestures(
                        onDoubleTap = { p: Offset ->
                            if (uz > 1f) { uz = 1f; ux = 0f; uy = 0f }
                            else { uz = 2f; ux = -p.x; uy = -p.y; clampPan() }
                            zoomTick++
                        },
                        onLongPress = { onLongPress(page) },
                    ) { p: Offset ->
                        val xf = p.x / size.width
                        val yf = p.y / size.height
                        // 확대 중이면 내용 좌표로 되돌려 칸을 찾는다
                        val q = Offset((p.x - ux) / uz, (p.y - uy) / uz)
                        when {
                            yf < 0.12f -> onToggleMenu()
                            xf < 0.15f -> go(doc.rtl)
                            xf > 0.85f -> go(!doc.rtl)
                            menuVisible -> onToggleMenu()
                            sel >= 0 -> sel = -1
                            else -> {
                                val hit = rects.indexOfFirst { it.contains(q) }
                                if (hit >= 0) sel = hit else onToggleMenu()
                            }
                        }
                    }
                }
                .then(
                    if (uz > 1f) Modifier.pointerInput(uz) {
                        detectDragGestures { change, d -> change.consume(); ux += d.x; uy += d.y; clampPan() }
                    } else Modifier,
                )
                .graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = auz; scaleY = auz; translationX = aux; translationY = auy },
        ) {
          Box(Modifier.fillMaxSize().graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = az; scaleY = az; translationX = atx; translationY = aty }) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "${page + 1}쪽", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (sel < 0) Canvas(Modifier.fillMaxSize()) {
                    rects.forEach { r -> drawRect(Color(0x99F0C674), topLeft = r.topLeft, size = Size(r.width, r.height), style = Stroke(width = 2.dp.toPx())) }
                }
            }
          }
        }
        if (upscale != null && !menuVisible) UpscaleChip(upscale, page, Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp))
        // 확대 배율(F-55, S-32): 바뀐 뒤 2초
        if (zoomTick > 0) Text(
            "${(uz * 100).toInt()}%", fontSize = 12.sp, color = colors.fgC,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 120.dp).background(colors.barC, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )

        // 위 띠(메뉴 숨김일 때 안내)
        if (!menuVisible) Text(
            "위 띠를 누르면 메뉴 · 칸 단위 보기 켜짐" + if (doc.rtl) " · 오른쪽→왼쪽 읽기" else "",
            fontSize = 12.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (!menuVisible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC1C1D20)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (sel < 0) "칸을 누르면 확대 · 자동은 칸을 차례로 보여 줘요" else "${sel + 1} / ${n}칸 · 다시 누르면 쪽 전체",
                fontSize = 12.sp, color = Color(0xFFCFCFCF),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { auto = !auto; if (auto && sel < 0) sel = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("자동 ${if (auto) "켬" else "끔"}", color = Color.White) }
                TextButton(onClick = { sel = -1 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("쪽 전체", color = Color.White) }
                TextButton(onClick = { if (sel < 0) sel = 0 else go(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다음 칸", color = Ash.Gold) }
            }
        }
    }
}

/** AI 화질 표시(작게): 처리 중 / 적용됨. 해당 없으면 아무것도 그리지 않는다. */
@Composable
fun UpscaleChip(up: ComicUpscale, page: Int, modifier: Modifier = Modifier) {
    val ready by up.ready.collectAsState()
    val busy by up.busy.collectAsState()
    val text = when {
        page in ready -> "AI 화질"
        busy == page -> "AI 화질 처리 중…"
        else -> null
    }
    if (text != null) Text(
        text, fontSize = 12.sp, color = Color.White,
        modifier = modifier.background(Color(0x80000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
