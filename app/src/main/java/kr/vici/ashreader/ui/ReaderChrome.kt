// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Prefs
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Settings

// 작업 07 E 읽기 화면 새 디자인(ReaderNew 목업): 평소엔 쪽수만. 한 번 누르면 위 "챕터 끝까지 N쪽 남음" + ✕ /
// 아래 가운데 "4 / 67" / 오른쪽 아래 메뉴. 메뉴 → 떠 있는 메뉴 쌓기. 예전 위·아래 막대를 이것으로 바꾼다.

/** 읽기 테마 색에서 떠 있는 단추·메뉴 색(밝은 종이면 흰 유리, 어두운 종이면 #1C1C1E). */
private class ChromeColors(val btn: Color, val line: Color, val fg: Color, val sub: Color, val sheet: Color, val chip: Color, val dark: Boolean)

private fun chromeOf(c: ReadingColors): ChromeColors {
    val dark = luminance(c.bg) < 0.35
    // 단추·시트 바탕은 모든 메뉴와 같은 불투명도(GLASS_ALPHA, 대표님 00:02)
    return if (dark) ChromeColors(Color(0xFF2C2C2E).copy(alpha = glassAlpha), Color.White.copy(alpha = 0.08f), Color(0xFFE8E8EA), Color(0xFF98989F), Color(0xFF1C1C1E).copy(alpha = glassAlpha), Color(0xFF3A3A3C).copy(alpha = 0.6f), true)
    else ChromeColors(Color.White.copy(alpha = glassAlpha), Color.Black.copy(alpha = 0.07f), Color(0xFF111316), Color(0xFF6C6C70), Color.White.copy(alpha = glassAlpha), Color(0xFFEFEFF2).copy(alpha = 0.7f), false)
}

/**
 * 메뉴가 열린 동안의 바깥 틀: 위 왼쪽 "챕터 끝까지 N쪽 남음"(모르면 "N% 읽음") + 오른쪽 ✕(책 닫기),
 * 아래 가운데 "4 / 67"(누르면 쪽 옮기기 막대), 오른쪽 아래 메뉴(누르면 떠 있는 메뉴 — 안내선은 그 안에).
 * 만화·한글·Word 도 같은 틀을 쓴다.
 */
@Composable
fun ReaderChrome(
    c: ReadingColors, page: Int, count: Int, chapterLeft: Int?, stackOpen: Boolean,
    onClose: () -> Unit, onMenu: () -> Unit,
    onSeek: (Int) -> Unit, preview: (@Composable (Int, Float) -> Unit)?, modifier: Modifier = Modifier,
) {
    val k = chromeOf(c)
    var seeking by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().heightIn(min = 54.dp).padding(start = 24.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val pct = if (count > 0) ((page + 1) * 100 / count) else 0
            Text(
                when { chapterLeft != null && chapterLeft > 0 -> "챕터 끝까지 ${chapterLeft}쪽 남음"; chapterLeft == 0 -> "챕터 마지막 쪽"; else -> "$pct% 읽음" },
                fontSize = 12.5.sp, color = c.subC, modifier = Modifier.weight(1f),
            )
            ChromeButton(k, Icons.Close, "책 닫기", onClick = onClose)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (seeking && count > 1) SeekBar(c, k, page, count, preview) { p -> onSeek(p); seeking = false }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // 왼쪽 아래 안내선 단추는 대표님 01:05 지시로 뺐다(떠 있는 메뉴에 같은 단추가 있음). 쪽수가 가운데 오게 같은 폭만 비운다
                Spacer(Modifier.width(48.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { seeking = !seeking }
                        .semantics { contentDescription = "${page + 1}쪽, 전체 ${count}쪽. 눌러서 쪽 옮기기" },
                    contentAlignment = Alignment.Center,
                ) { Text("${page + 1} / $count", fontSize = 12.5.sp, color = c.subC) }
                ChromeButton(k, Icons.Menu, if (stackOpen) "메뉴 닫기" else "메뉴", on = stackOpen, onClick = onMenu)
            }
        }
    }
}

/** 쪽 옮기기 막대(예전 아래 막대의 쪽 이동 + 끄는 동안 미리보기). */
@Composable
private fun SeekBar(c: ReadingColors, k: ChromeColors, page: Int, count: Int, preview: (@Composable (Int, Float) -> Unit)?, onSeek: (Int) -> Unit) {
    var drag by remember(page) { mutableFloatStateOf(page.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (dragging && preview != null) Box(Modifier.padding(bottom = 8.dp)) { preview(drag.toInt().coerceIn(0, count - 1), drag / (count - 1)) }
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.btn).border(1.dp, k.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("1", fontSize = 12.sp, color = k.sub)
            AppleSlider(
                value = drag, onValueChange = { drag = it; dragging = true }, onValueChangeFinished = { dragging = false; onSeek(drag.toInt()) },
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "쪽 이동" },
            )
            Text("${drag.toInt() + 1} / $count", fontSize = 12.sp, color = k.sub)
        }
    }
}

/** 떠 있는 둥근 단추(44dp → 누름 48dp, 감수 2). [on] = 켜진 상태(검은 바탕 흰 아이콘). */
@Composable
private fun ChromeButton(k: ChromeColors, icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).padding(2.dp)
            .shadow(if (k.dark) 0.dp else 8.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.14f))
            .clip(RoundedCornerShape(12.dp)).background(if (on) k.fg else k.btn).border(1.dp, k.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) (if (k.dark) Color.Black else Color.White) else k.fg, modifier = Modifier.size(20.dp)) }
}

/**
 * 떠 있는 메뉴 쌓기(오른쪽 아래): [목차 · 4%] [책 검색] [테마 및 설정 가가] [공유 · 안내선 · 책갈피].
 * 감수 4: 아이콘만 있는 단추는 이름을 읽어 준다. 큰 글자 200% 에서도 잘리지 않게 줄 높이는 내용에 맞춰 늘어난다.
 */
@Composable
fun ReaderMenuStack(
    c: ReadingColors, percent: Int, bookmarked: Boolean, guideOn: Boolean, modifier: Modifier = Modifier,
    onToc: () -> Unit, onSearch: () -> Unit, onTheme: () -> Unit, onShare: () -> Unit, onGuide: () -> Unit, onBookmark: () -> Unit,
) {
    val k = chromeOf(c)
    val shape = RoundedCornerShape(14.dp)
    fun Modifier.glass() = shadow(if (k.dark) 0.dp else 16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.2f))
        .clip(shape).background(k.btn).border(1.dp, k.line, shape)
    Column(modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StackRow(k, "목차", "$percent%", Icons.ListView, Modifier.glass(), onToc)
        StackRow(k, "책 검색", null, Icons.Search, Modifier.glass(), onSearch)
        StackRow(k, "테마 및 설정", "가가", null, Modifier.glass(), onTheme)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrioButton(k, Icons.Share, "이 쪽 공유", false, Modifier.weight(1f).glass(), onShare)
            TrioButton(k, Icons.Guide, if (guideOn) "안내선 끄기" else "안내선", guideOn, Modifier.weight(1f).glass(), onGuide)
            TrioButton(k, Icons.Bookmark, if (bookmarked) "책갈피 빼기" else "책갈피", bookmarked, Modifier.weight(1f).glass(), onBookmark)
        }
    }
}

@Composable
private fun StackRow(k: ChromeColors, label: String, value: String?, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = if (value != null && icon != null) "$label, $value" else label; role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontSize = 15.sp, color = k.fg, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = if (icon == null) 15.sp else 13.sp, color = if (icon == null) k.fg else k.sub, fontFamily = if (icon == null) Myeongjo else null, fontWeight = if (icon == null) FontWeight.Bold else null)
        if (icon != null) Icon(icon, null, tint = k.fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TrioButton(k: ChromeColors, icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 50.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (on) stateDescription = "켜짐" },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) Color(0xFF0A84FF) else k.fg, modifier = Modifier.size(21.dp)) }
}

/**
 * 테마 및 설정 시트(ReaderNew ③): 가/가 크기 · 쪽 넘김/스크롤 · 어둡게 · 밝기 막대 · 테마 6개 타일(고른 것 파란 테두리) · [사용자화].
 * AI 화질 향상은 작업 04에서 정한 대로 이 시트 맨 위([ai]). 고정 쪽 문서는 글자 크기가 바뀌지 않아 가/가를 흐리게 둔다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    s: Settings, c: ReadingColors, onSettings: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit,
    reflow: Boolean, scrollAvailable: Boolean, level: Float, onLive: (Float?) -> Unit,
    /** AI 화질 향상 한 줄 — 받는 값 = 칸 바탕·글자색. */
    ai: (@Composable (Color, Color) -> Unit)?, onCustomize: () -> Unit,
    /** 한글·Word '원본 | eBook', 그림 PDF 'PDF | 만화' 보기 전환(대표님 23:57: 맨 위에). null = 없음. */
    viewSwitch: ViewSwitch? = null,
) {
    val k = chromeOf(c)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = k.sheet, contentColor = k.fg, scrimColor = Color.Black.copy(alpha = 0.22f)) {
        BlurBehind(dim = 0f)
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 18.dp).verticalScroll(rememberScrollState())) {
            Text("테마 및 설정", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = k.fg, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            // 대표님 00:40: 보기 전환도 넘김·어둡게와 같은 줄(한 칸, 누르면 다른 보기로) — 시트 세로를 줄인다
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                viewSwitch?.let { v ->
                    val now = if (v.secondOn) v.right else v.left
                    val other = if (v.secondOn) v.left else v.right
                    Column(
                        Modifier.weight(1.3f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(k.chip)
                            .clickable(role = Role.Button) { v.onPick(!v.secondOn) }
                            .semantics { contentDescription = "보기 전환, 지금 $now, 누르면 $other 보기" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(now, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = k.fg, maxLines = 1)
                        Text("⇄ $other", fontSize = 10.sp, color = k.sub, maxLines = 1)
                    }
                }
                ToggleTile(k, Icons.ArrowsLR, "쪽 넘김", !s.scrollView, enabled = scrollAvailable, Modifier.weight(1f)) { onSettings { it.copy(scrollView = false) } }
                ToggleTile(k, Icons.ArrowsUD, "스크롤", s.scrollView, enabled = scrollAvailable, Modifier.weight(1f)) { onSettings { it.copy(scrollView = true) } }
                // 어둡게: 집중(검은 종이) ↔ 원본
                val darkOn = s.theme == ReadTheme.NIGHT
                ToggleTile(k, Icons.Moon, "어둡게", darkOn, enabled = true, Modifier.weight(1f), inverse = true) {
                    onSettings { it.copy(theme = if (it.theme == ReadTheme.NIGHT) ReadTheme.DAY else ReadTheme.NIGHT) }
                }
            }
            // 글자 크기 눈금 막대(대표님 01:05): 가 ━━●━━ 가 16 — 한 눈금 = 1, 미는 동안 숫자만 바뀌고 손을 떼면 한 번 적용(쪽 다시 나누기를 한 번만).
            // 고정 쪽 문서(PDF·원본 보기)는 글자 크기가 바뀌지 않아 흐리게 둔다
            var liveSize by remember { mutableStateOf<Float?>(null) }
            val sizeNow = (liveSize ?: s.fontSize.toFloat()).roundToInt()
            val fa = if (reflow) 1f else 0.35f
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(k.chip).padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("가", fontSize = 13.sp, color = k.fg.copy(alpha = 0.6f * fa))
                AppleSlider(
                    value = liveSize ?: s.fontSize.toFloat(), onValueChange = { liveSize = it }, enabled = reflow,
                    onValueChangeFinished = { val n = sizeNow; liveSize = null; if (n != s.fontSize) onSettings { it.copy(fontSize = n) } },
                    valueRange = Prefs.MIN_SIZE.toFloat()..Prefs.MAX_SIZE.toFloat(), steps = Prefs.MAX_SIZE - Prefs.MIN_SIZE - 1,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "글자 크기" },
                )
                Text("가", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = k.fg.copy(alpha = fa))
                Text("$sizeNow", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = k.fg.copy(alpha = fa), textAlign = TextAlign.End, modifier = Modifier.width(24.dp))
            }
            ai?.invoke(k.chip, k.fg)
            // 밝기 막대(창 밝기 + 0 아래는 추가 어둡게 — 예전 밝기 시트와 같은 눈금)
            var live by remember { mutableStateOf<Float?>(null) }
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(k.chip).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Sun, null, tint = k.fg.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                AppleSlider(
                    value = live ?: level, onValueChange = { live = it; onLive(it) },
                    onValueChangeFinished = { val v = live ?: level; val (b, d) = LightMath.split(v); onSettings { it.copy(brightness = b, dim = d) }; live = null; onLive(null) },
                    valueRange = -0.6f..1f,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "밝기" },
                )
                Icon(Icons.Sun, null, tint = k.fg, modifier = Modifier.size(20.dp))
            }
            // 테마 타일 6개(원본·고요·종이 / 볼드·평온·집중) — 고른 것 파란 테두리, 선택 상태 읽어 주기(감수 4)
            ReadTheme.TILES.chunked(3).forEach { row ->
                // 대표님 00:40: 타일 세로를 줄임(84 → 52dp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { t ->
                        val tc = t.colors(); val on = s.theme == t
                        Column(
                            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(tc.bgC)
                                .border(if (on) 2.5.dp else 1.dp, if (on) Color(0xFF0A84FF) else Color(0x40808080), RoundedCornerShape(12.dp))
                                .selectable(on, role = Role.RadioButton) { onSettings { it.copy(theme = t) } }
                                .semantics { contentDescription = "테마 ${t.label}" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("가가", fontSize = 17.sp, lineHeight = 20.sp, color = tc.fgC, fontFamily = if (t == ReadTheme.BOLD || t == ReadTheme.NIGHT) FontFamily.SansSerif else Myeongjo,
                                fontWeight = if (t == ReadTheme.BOLD) FontWeight.Black else if (t == ReadTheme.NIGHT) FontWeight.Medium else FontWeight.Normal)
                            Text(t.label, fontSize = 11.sp, lineHeight = 13.sp, color = tc.fgC)
                        }
                    }
                }
            }
            if (s.theme == ReadTheme.CUSTOM) Text("지금은 ‘내 테마’예요. 위 타일을 누르면 바뀌어요.", fontSize = 12.sp, color = k.sub, modifier = Modifier.padding(top = 6.dp))
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(k.chip)
                    .clickable(role = Role.Button, onClick = onCustomize).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Gear, null, tint = k.fg, modifier = Modifier.size(18.dp))
                Text("사용자화 (글꼴·줄 간격·여백·넘김 효과)", modifier = Modifier.padding(start = 8.dp), fontSize = 14.5.sp, color = k.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ToggleTile(k: ChromeColors, icon: ImageVector, label: String, on: Boolean, enabled: Boolean, modifier: Modifier, inverse: Boolean = false, onClick: () -> Unit) {
    val bg = if (inverse && on) k.fg else if (on) k.fg.copy(alpha = 0.14f) else k.chip
    val fg = (if (inverse && on) (if (k.dark) Color.Black else Color.White) else k.fg).copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .selectable(on, enabled = enabled, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)) }
}

/**
 * 이 쪽 공유(떠 있는 메뉴의 공유): 지금 쪽을 그림(PNG)으로 만들어 캐시 share/ 에 두고 넘긴다(만화 쪽 그림 공유와 같은 길, F-59).
 * [render] 는 읽기 화면의 안전한 그리기(동시 그리기 막음)를 IO 에서 부른다.
 */
fun sharePageImage(context: android.content.Context, page: Int, render: () -> android.graphics.Bitmap?, onMsg: (String) -> Unit) {
    val app = context.ash
    app.appScope.launch {
        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bmp = render() ?: return@runCatching null
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000L) it.delete() }
                val f = java.io.File(dir, "page-${page + 1}.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
            }.getOrNull()
        }
        if (uri == null) { onMsg("이 쪽을 공유하지 못했어요."); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png").putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(android.content.Intent.createChooser(send, "이 쪽 공유")) } catch (_: android.content.ActivityNotFoundException) { onMsg("공유할 앱이 없어요.") }
    }
}

/** 테마 및 설정 맨 위 보기 전환: [left]·[right] 이름, [secondOn] = 지금 오른쪽 보기인가, [onPick] = 오른쪽을 고르면 true. */
class ViewSwitch(val left: String, val right: String, val secondOn: Boolean, val onPick: (Boolean) -> Unit)

/** 사용자화 시트의 한 줄: 아이콘 + 이름 + 설명. */
class CustomItem(val icon: ImageVector, val label: String, val sub: String?, val onClick: () -> Unit)

/**
 * 사용자화(테마 및 설정 › 사용자화): 예전 위·아래 막대에 있던 것 중 떠 있는 메뉴에 없는 것을 빠짐없이 모은다
 * (글꼴·줄 간격·여백 = 보기 설정, 넘김 효과·자동 넘김, 밝기·따뜻한 색, 듣기, 이 쪽 메모, 회전 잠금, 보기 전환, 쪽 목록).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(c: ReadingColors, items: List<CustomItem>, onDismiss: () -> Unit) {
    val k = chromeOf(c)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = k.sheet, contentColor = k.fg, scrimColor = Color.Black.copy(alpha = 0.22f)) {
        BlurBehind(dim = 0f)
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 18.dp).verticalScroll(rememberScrollState())) {
            Text("사용자화", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = k.fg, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(k.chip)) {
                items.forEachIndexed { i, it ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(k.sub.copy(alpha = 0.2f)))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { it.onClick() }.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(it.icon, null, tint = k.fg, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(it.label, fontSize = 15.sp, color = k.fg)
                            if (it.sub != null) Text(it.sub, fontSize = 12.sp, color = k.sub)
                        }
                        Icon(Icons.Chevron, null, tint = k.sub, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * 안내선(ReaderNew ⑤): 한 줄 띠만 밝게(노란 옅은 칠), 나머지는 종이 색으로 흐리게. 띠를 위아래로 끌어 옮기고, 두 번 누르면 끈다.
 * 띠 밖은 누름을 받지 않아 쪽 넘김·스크롤이 그대로 된다. [y] = 띠 가운데(화면 높이 비율 0..1).
 */
@Composable
fun GuideLine(c: ReadingColors, lineDp: Float, y: Float, onMove: (Float) -> Unit, onOff: () -> Unit) {
    val band = (lineDp + 10f).coerceIn(28f, 80f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val bandPx = with(LocalDensity.current) { band.dp.toPx() }
        val center = (y * hPx).coerceIn(bandPx / 2, hPx - bandPx / 2)
        val dim = c.bgC.copy(alpha = 0.72f)
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(dim, Offset.Zero, Size(size.width, center - bandPx / 2))
                drawRect(dim, Offset(0f, center + bandPx / 2), Size(size.width, size.height - center - bandPx / 2))
                drawRect(Color(0x47FFD60A), Offset(0f, center - bandPx / 2), Size(size.width, bandPx))
            },
        )
        Box(
            Modifier.offset { IntOffset(0, (center - bandPx / 2).roundToInt()) }.fillMaxWidth().height(band.dp)
                .pointerInput(hPx) { detectVerticalDragGestures { ch, d -> ch.consume(); onMove(((center + d) / hPx).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onOff() }) }
                .semantics { contentDescription = "안내선. 위아래로 끌어 옮기고, 두 번 누르면 꺼요" },
        ) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(20.dp).shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Color.White))
        }
    }
}
