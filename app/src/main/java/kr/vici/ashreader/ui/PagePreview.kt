// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.vici.ashreader.doc.TocEntry

/**
 * 쪽 축소 그림 캐시(F-60). 슬라이더 미리보기·쪽 목록 그리드가 같이 쓴다. 문서마다 따로 만든다(문서를 닫으면 버림).
 * 상한 = 그림 바이트 합 24MB.
 */
class ThumbCache {
    private val lru = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(key: String): Bitmap? = lru.get(key)?.takeIf { !it.isRecycled }
    fun put(key: String, b: Bitmap) { lru.put(key, b) }
}

/** 축소 그림 폭(px). */
private const val THUMB_W_DP = 120

/**
 * 슬라이더를 끄는 동안 뜨는 쪽 미리보기(S-30). 고정 쪽·만화 = 축소 그림, 이북류 = 장 이름 + 쪽 번호.
 * 끄는 속도가 빨라 그림이 아직 없으면 회색 종이 틀 + 쪽 번호, 손가락이 0.2초 멈추면 그림을 그려 바꿔 끼운다.
 * [thumb] = 백그라운드에서 [index] 쪽을 폭 w(px)로 그린다(닫힌 문서·실패면 null).
 * [notReady] = 아직 풀리지 않은 쪽(solid CBR) → "아직 준비 중" 꼬리표.
 */
@Composable
fun SliderPreview(
    index: Int,
    fraction: Float,
    colors: ReadingColors,
    fixedPage: Boolean,
    cache: ThumbCache,
    aspect: Float,
    thumb: suspend (index: Int, w: Int) -> Bitmap?,
    chapter: suspend (index: Int) -> String?,
    notReady: (Int) -> Boolean = { false },
) {
    val density = LocalDensity.current
    val wPx = with(density) { THUMB_W_DP.dp.roundToPx() }
    val bubbleW: Dp = if (fixedPage) THUMB_W_DP.dp + 16.dp else 220.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val x = ((maxWidth - bubbleW) * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)
        Column(
            Modifier.offset(x = x).width(bubbleW).clip(RoundedCornerShape(12.dp)).background(colors.barC)
                .border(1.dp, colors.lineC, RoundedCornerShape(12.dp)).padding(8.dp)
                .semantics { contentDescription = "${index + 1}쪽 미리보기" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (fixedPage) {
                val key = "s:$index:$wPx"
                var bmp by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(index) {
                    bmp = cache.get(key)
                    if (bmp == null && !notReady(index)) {
                        delay(200) // 손가락이 멈추면 그린다
                        bmp = thumb(index, wPx)?.also { cache.put(key, it) }
                    }
                }
                // 그림이 오면 그 그림의 비율로(그 전에는 [aspect] = 세로/가로)
                val ar = bmp?.takeIf { !it.isRecycled && it.width > 0 }?.let { it.height.toFloat() / it.width } ?: aspect
                Box(Modifier.width(THUMB_W_DP.dp).aspectRatio(1f / ar.coerceIn(0.3f, 3f)).background(Color(0xFFBDBDBD)), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null && !b.isRecycled) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else Text("${index + 1}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF616161))
                    if (notReady(index)) Text("아직 준비 중", fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text("${index + 1}쪽", fontSize = 13.sp, color = colors.fgC, modifier = Modifier.padding(top = 4.dp))
            } else {
                val ch by produceState<String?>(null, index) { delay(120); value = chapter(index) }
                ch?.let { Text(it, fontSize = 13.sp, color = colors.fgC, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text("${index + 1}쪽", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.accentC)
            }
        }
    }
}

/** 목차에서 [index] 쪽이 든 장 이름(그 쪽 이전 마지막 항목). */
fun chapterOf(toc: List<TocEntry>, index: Int): String? = toc.filter { it.page in 0..index }.maxByOrNull { it.page }?.title

/**
 * 만화 쪽 목록 그리드(S-30, F-60): 전체 화면, 휴대폰 3열 · 태블릿 가로 6열. 현재 쪽 테두리 강조, 책갈피 쪽 모서리 표시.
 * 오→왼 책은 오른쪽부터 채운다. 아직 그리지 못한 칸은 회색 틀 + 쪽 번호, 화면에 보이는 칸부터 그린다(LazyGrid).
 * 쪽 번호는 원본(압축 안) 쪽 기준. [thumb] = 원본 쪽 [p] 를 폭 w(px)로, [ready] = 풀린 쪽인가(solid CBR).
 */
@Composable
fun ComicPageGrid(
    count: Int,
    current: Int,
    bookmarked: Set<Int>,
    rtl: Boolean,
    colors: ReadingColors,
    cache: ThumbCache,
    thumb: suspend (p: Int, w: Int) -> Bitmap?,
    ready: (Int) -> Boolean,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val conf = LocalConfiguration.current
    val cols = if (conf.screenWidthDp >= 840 || (conf.screenWidthDp > conf.screenHeightDp && conf.smallestScreenWidthDp >= 600)) 6 else 3
    val density = LocalDensity.current
    val cellPx = with(density) { (conf.screenWidthDp / cols).dp.roundToPx() }.coerceIn(96, 480)
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (current - cols).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().background(colors.bgC)) {
        Row(Modifier.fillMaxWidth().background(colors.barC).statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, "닫기", tint = colors.fgC) }
            Text("쪽 목록 · ${count}쪽", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.fgC)
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), state = state, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count, key = { it }) { p ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        GridCell(p, p == current, p in bookmarked, colors, cache, cellPx, thumb, ready(p)) { onPick(p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    p: Int, current: Boolean, bookmark: Boolean, colors: ReadingColors, cache: ThumbCache, wPx: Int,
    thumb: suspend (Int, Int) -> Bitmap?, ready: Boolean, onClick: () -> Unit,
) {
    val key = "g:$p:$wPx"
    var bmp by remember(p) { mutableStateOf(cache.get(key)) }
    LaunchedEffect(p, ready) {
        if (bmp == null && ready) bmp = thumb(p, wPx)?.also { cache.put(key, it) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(4.dp)).background(Color(0xFFBDBDBD))
                .then(if (current) Modifier.border(3.dp, colors.accentC, RoundedCornerShape(4.dp)) else Modifier)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "${p + 1}쪽" + (if (current) ", 지금 쪽" else "") + (if (bookmark) ", 책갈피" else "") },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null && !b.isRecycled) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text("${p + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF616161))
            if (!ready) Text("준비 중", fontSize = 12.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (bookmark) Box(Modifier.align(Alignment.TopEnd).size(14.dp, 22.dp).background(Color(0xFF8C5A2B)))
        }
        Text("${p + 1}", fontSize = 12.sp, color = if (current) colors.accentC else colors.subC, modifier = Modifier.padding(top = 2.dp))
    }
}
