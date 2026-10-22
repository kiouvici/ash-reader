// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.engine.comic.PagePanels
import kr.vici.ashreader.engine.comic.ReadingDirection

/**
 * S-34 칸 단위 보기 (F-72, D-007). 눌러서 확대(기본) / 자동 순서.
 * 탭 규칙(화면 설계서 4.3): 위 12% 띠 = 메뉴, 좌우 15% = 이전/다음 칸(즉시), 칸 위 = 확대 ↔ 쪽 전체.
 * TODO(M-12): 두 번 탭 2배 확대·250ms 대기는 실험 ③ 확정 후.
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
) {
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))) }
    var sel by remember { mutableIntStateOf(if (autoDefault) 0 else -1) }
    var auto by remember { mutableStateOf(autoDefault) }
    val dir = if (doc.rtl) ReadingDirection.RTL else ReadingDirection.LTR
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val loaded by produceState<Pair<Bitmap, PagePanels>?>(null, page) {
            value = withContext(Dispatchers.IO) {
                runCatching { doc.pageBitmap(page, wPx.toInt().coerceAtLeast(1)) to doc.panels(page, dir) }.getOrNull()
            }
        }
        LaunchedEffect(page) { onPage(page) }

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
        val z = if (target == null) 1f else minOf(wPx / target.width, hPx / target.height) * 0.96f
        val tx = if (target == null) 0f else wPx / 2f - target.center.x * z
        val ty = if (target == null) 0f else hPx / 2f - target.center.y * z
        val az by animateFloatAsState(z, tween(250), label = "z")
        val atx by animateFloatAsState(tx, tween(250), label = "tx")
        val aty by animateFloatAsState(ty, tween(250), label = "ty")

        Box(
            Modifier.fillMaxSize()
                .pointerInput(rects, sel, auto, page, doc.rtl) {
                    detectTapGestures { p: Offset ->
                        val xf = p.x / size.width
                        val yf = p.y / size.height
                        when {
                            yf < 0.12f -> onToggleMenu()
                            xf < 0.15f -> go(doc.rtl)
                            xf > 0.85f -> go(!doc.rtl)
                            menuVisible -> onToggleMenu()
                            sel >= 0 -> sel = -1
                            else -> {
                                val hit = rects.indexOfFirst { it.contains(p) }
                                if (hit >= 0) sel = hit else onToggleMenu()
                            }
                        }
                    }
                }
                .graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = az; scaleY = az; translationX = atx; translationY = aty },
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "${page + 1}쪽", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (sel < 0) Canvas(Modifier.fillMaxSize()) {
                    rects.forEach { r -> drawRect(Color(0x99F0C674), topLeft = r.topLeft, size = Size(r.width, r.height), style = Stroke(width = 2.dp.toPx())) }
                }
            }
        }

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
