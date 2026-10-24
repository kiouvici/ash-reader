// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.ComicUpscale

/** AI 화질 향상 보기 방식(S-33 [화질] 탭): 0 원본, 1 AI 향상, 2 전후 비교. */
internal val AI_MODES = listOf("원본", "AI 향상", "전후 비교")

/**
 * [원본 | AI 향상 | 전후 비교] 나눔 단추(48dp 이상). [compareEnabled] = false 면 '전후 비교'를 흐리게(칸 보기·웹툰).
 */
@Composable
internal fun AiModeSegmented(selected: Int, compareEnabled: Boolean, onPick: (Int) -> Unit, modifier: Modifier = Modifier, dark: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bg = if (dark) Color(0xFF1C1D20) else cs.surfaceVariant
    val onBg = if (dark) Color(0xFFE3E3E3) else cs.onSurface
    val selBg = if (dark) Ash.InkDark else cs.primary
    val selFg = if (dark) Color(0xFF0E2238) else cs.onPrimary
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(bg).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AI_MODES.forEachIndexed { i, name ->
            val enabled = i != 2 || compareEnabled
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp)).background(if (on) selBg else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onPick(i) }
                    .alpha(if (enabled) 1f else 0.38f),
                contentAlignment = Alignment.Center,
            ) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (on) selFg else onBg, maxLines = 1) }
        }
    }
}

/** 비교할 한 쪽: [orig] 원본, [ai] AI 결과(없으면 null), [state], [busy] 지금 이 쪽을 처리 중. */
internal class ComparePage(val orig: Bitmap?, val ai: Bitmap?, val state: Int, val busy: Boolean = false) {
    companion object { const val READY = 0; const val WAITING = 1; const val SHARP = 2; const val FAILED = 3; const val NONE = 4 }
}

/**
 * AI 화질 전후 비교(S-33 '전후 비교', F-73) — 만화. 읽기 화면의 도구 막대 자리를 차지하는 전체 화면.
 * [startSource] = 처음 볼 원본 쪽. [onExit] = 끝낼 때 보고 있던 원본 쪽. [onMode] = 아래 나눔 단추에서 원본(0)·AI 향상(1)을 고름.
 */
@Composable
fun AiCompareScreen(doc: ComicReaderDoc, upscale: ComicUpscale?, startSource: Int, onExit: (Int) -> Unit, onMode: (Int) -> Unit) {
    val ready = upscale?.ready?.collectAsState()?.value.orEmpty()
    val busy = upscale?.busy?.collectAsState()?.value
    val paused = upscale?.paused?.collectAsState()?.value
    AiCompareFrame(
        pageCount = doc.sourceCount, start = startSource, refresh = ready,
        notReadyText = when {
            upscale == null -> "AI 화질 향상을 준비하고 있어요"
            paused != null -> "기기가 뜨겁거나 배터리가 적어서 잠시 멈췄어요"
            else -> null
        },
        request = { src, w, h -> upscale?.request(src, w, h) },
        load = { src, w ->
            val state = when {
                src in ready -> ComparePage.READY
                upscale?.isAlreadySharp(src) == true -> ComparePage.SHARP
                upscale?.isFailed(src) == true -> ComparePage.FAILED
                else -> ComparePage.WAITING
            }
            val o = runCatching { doc.pageBitmap(src, w) }.getOrNull()
            val a = if (state == ComparePage.READY) upscale?.load(src, w) else null
            ComparePage(o, a, state, busy == src)
        },
        showProgress = paused == null,
        onExit = onExit, onMode = onMode,
    )
}

/**
 * 작업 04: 이북·PDF 문서 그림 AI 전후 비교. 만화와 같은 손잡이 방식, 쪽 단위.
 * 왼쪽 = 처리된 그림을 덧그리지 않은 쪽, 오른쪽 = 덧그린 쪽. 그림이 없는 쪽은 "이 쪽에는 바꿀 그림이 없어요".
 */
@Composable
fun DocAiCompareScreen(doc: kr.vici.ashreader.doc.MupdfReaderDoc, colors: ReadingColors, start: Int, onExit: (Int) -> Unit, onMode: (Int) -> Unit) {
    val up = doc.imageUpscale
    val version = up?.version?.collectAsState()?.value
    AiCompareFrame(
        pageCount = doc.pageCount, start = start, refresh = version,
        notReadyText = if (up == null) "AI 화질 향상을 준비하고 있어요" else null,
        request = { _, _, _ -> },
        load = { p, w ->
            val h = (w * (doc.pageAspect(p) ?: 1.414f)).toInt().coerceAtLeast(1)
            // 덧그린 쪽을 먼저 그려 처리를 요청하게 한다
            val a = runCatching { doc.render(p, w, h, colors, ai = true) }.getOrNull()
            val n = up?.itemsOn(p)
            val o = runCatching { doc.render(p, w, h, colors, ai = false) }.getOrNull()
            when {
                n == null -> ComparePage(o, null, ComparePage.WAITING, true).also { a?.recycle() }
                n == 0 -> ComparePage(o, null, ComparePage.NONE).also { a?.recycle() }
                else -> ComparePage(o, a, ComparePage.READY)
            }
        },
        showProgress = true,
        onExit = onExit, onMode = onMode,
    )
}

@Composable
private fun AiCompareFrame(
    pageCount: Int, start: Int, refresh: Any?, notReadyText: String?,
    request: (Int, Int, Int) -> Unit, load: (Int, Int) -> ComparePage, showProgress: Boolean,
    onExit: (Int) -> Unit, onMode: (Int) -> Unit,
) {
    var src by remember { mutableIntStateOf(start.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    var split by remember { mutableFloatStateOf(0.5f) }
    Dialog(
        onDismissRequest = { onExit(src) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111214))) {
            // 위: 도구 막대 자리(뒤로 · 제목 · 쪽 단추)
            Row(
                Modifier.fillMaxWidth().background(Color(0xEB2E4A6B)).statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onExit(src) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, "비교 끝내기", tint = Color.White) }
                Text("전후 비교 · ${src + 1} / ${pageCount}쪽", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { if (src > 0) src-- }, enabled = src > 0, modifier = Modifier.size(48.dp)) {
                    Text("◀", color = Color.White.copy(alpha = if (src > 0) 1f else 0.38f), fontSize = 18.sp, modifier = Modifier.semantics { contentDescription = "이전 쪽" })
                }
                IconButton(onClick = { if (src < pageCount - 1) src++ }, enabled = src < pageCount - 1, modifier = Modifier.size(48.dp)) {
                    Text("▶", color = Color.White.copy(alpha = if (src < pageCount - 1) 1f else 0.38f), fontSize = 18.sp, modifier = Modifier.semantics { contentDescription = "다음 쪽" })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                val wPx = constraints.maxWidth; val hPx = constraints.maxHeight
                LaunchedEffect(src, wPx, hPx) { request(src, wPx, hPx) }
                val page by produceState<ComparePage?>(null, src, refresh, wPx) {
                    value = withContext(Dispatchers.IO) { load(src, wPx) }
                }
                val pg = page
                val orig = pg?.orig?.asImage()
                val ai = pg?.ai?.asImage()
                val state = pg?.state ?: ComparePage.WAITING
                val img = ai ?: orig
                if (img != null) {
                    // 쪽을 칸 안에 비율대로 맞춘 자리
                    val k = minOf(wPx.toFloat() / img.width, hPx.toFloat() / img.height)
                    val dw = img.width * k; val dh = img.height * k
                    val left = (wPx - dw) / 2f; val top = (hPx - dh) / 2f
                    val compare = state == ComparePage.READY && ai != null && orig != null
                    Canvas(Modifier.fillMaxSize().alpha(if (compare || state == ComparePage.NONE) 1f else 0.45f).semantics {
                        contentDescription = if (compare) "왼쪽 원본, 오른쪽 AI 향상" else "원본"
                    }) {
                        val dst = IntSize(dw.roundToInt(), dh.roundToInt())
                        val at = IntOffset(left.roundToInt(), top.roundToInt())
                        if (compare) {
                            drawImage(ai!!, dstOffset = at, dstSize = dst, filterQuality = FilterQuality.High)
                            clipRect(right = left + dw * split) { drawImage(orig!!, dstOffset = at, dstSize = dst, filterQuality = FilterQuality.Low) }
                        } else drawImage(img, dstOffset = at, dstSize = dst, filterQuality = FilterQuality.Low)
                    }
                    if (compare) {
                        val density = LocalDensity.current
                        val lineX = left + dw * split
                        // 나누는 선(노랑 3dp + 어두운 1dp 테두리)
                        Box(
                            Modifier.offset { IntOffset((lineX - with(density) { 2.5.dp.toPx() }).roundToInt(), top.roundToInt()) }
                                .width(5.dp).fillMaxHeight(dh / hPx).background(Color(0xFF1F1F1F)).padding(horizontal = 1.dp).background(Ash.Gold),
                        )
                        Tag("원본", Modifier.offset { IntOffset((left + with(density) { 8.dp.toPx() }).roundToInt(), (top + with(density) { 8.dp.toPx() }).roundToInt()) })
                        Tag("AI 향상", Modifier.align(Alignment.TopEnd).offset { IntOffset(-(wPx - left - dw + with(density) { 8.dp.toPx() }).roundToInt(), (top + with(density) { 8.dp.toPx() }).roundToInt()) })
                        // 손잡이만 끈다(48dp, 노랑 + 1dp 어두운 테두리). 쪽 밀기는 없다.
                        val handle = with(density) { 48.dp.toPx() }
                        Box(
                            Modifier.offset { IntOffset((lineX - handle / 2f).roundToInt(), (top + dh / 2f - handle / 2f).roundToInt()) }
                                .size(48.dp).clip(CircleShape).background(Ash.Gold).border(1.dp, Color(0xFF1F1F1F), CircleShape)
                                .pointerInput(dw, left) {
                                    detectHorizontalDragGestures { ch, dx -> ch.consume(); split = (split + dx / dw).coerceIn(0.02f, 0.98f) }
                                }
                                .semantics {
                                    contentDescription = "비교 손잡이. 좌우로 끌어요"
                                    stateDescription = "원본 ${(split * 100).roundToInt()}%"
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("⇔", color = Color(0xFF1F1F1F), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                if (state != ComparePage.READY || img == null) {
                    Column(
                        Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xE61C1D20)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            notReadyText ?: when (state) {
                                ComparePage.SHARP -> "이 쪽은 이미 선명해서 처리하지 않았어요"
                                ComparePage.FAILED -> "이 쪽은 처리하지 못했어요"
                                ComparePage.NONE -> "이 쪽에는 바꿀 그림이 없어요"
                                else -> "이 쪽은 아직 처리 중이에요"
                            },
                            color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
                        )
                        if (state == ComparePage.WAITING && showProgress) {
                            LinearProgressIndicator(Modifier.padding(top = 12.dp).width(200.dp).semantics { contentDescription = if (pg?.busy == true) "이 쪽 처리 중" else "처리 차례 기다리는 중" })
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "노란 손잡이를 좌우로 끌어서 비교해요 · 왼쪽 원본 / 오른쪽 AI", color = Color(0xFFBFC3C7), fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                // 끌기 어려울 때: 단추로도 선을 옮긴다
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    OutlinedButton(onClick = { split = (split + 0.15f).coerceAtMost(0.98f) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("◀ 원본 더 보기", color = Color(0xFFE3E3E3), fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = { split = (split - 0.15f).coerceAtLeast(0.02f) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("AI 더 보기 ▶", color = Color(0xFFE3E3E3), fontSize = 14.sp)
                    }
                }
                AiModeSegmented(2, compareEnabled = true, dark = true, onPick = { m -> if (m != 2) onMode(m) })
            }
        }
    }
}
@Composable
private fun Tag(t: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(t, color = Color.White, fontSize = 14.sp)
    }
}

private fun Bitmap.asImage(): ImageBitmap = asImageBitmap()
