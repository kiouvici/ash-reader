// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.ReaderDoc
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage

/** 형광펜 4색 (노랑 = 시안의 Gold). */
val HighlightColors = listOf(0xFFF0C674.toInt(), 0xFFA8D5A2.toInt(), 0xFFA8C5EA.toInt(), 0xFFF2A7B8.toInt())
val HighlightNames = listOf("노랑", "초록", "파랑", "분홍")
private const val SEARCH_HIT = 0xFFE8873A.toInt()

/** 쪽 그림 위에 칠할 범위. [color] = 불투명 색(칠할 때 반투명으로). */
class Mark(val start: Int, val end: Int, val color: Int)

/** 검색에서 고른 자리. */
data class Hit(val page: Int, val start: Int, val end: Int, val snippet: String)

/** 고른 글자. [start]..[end] 끝 제외. */
data class Selection(val slot: PageSlot, val tp: TextPage, val start: Int, val end: Int) {
    val text: String get() = tp.text.substring(start, end).replace('\n', ' ').trim()
}

/** 렌더된 쪽 비트맵에 형광펜·검색 표시를 칠한다(비트맵은 [fitInto] 결과와 같은 배치). */
fun paintMarks(bmp: Bitmap, tp: TextPage, marks: List<Mark>) {
    if (marks.isEmpty() || !bmp.isMutable) return
    val c = TextGeo.contentRect(tp.aspect, bmp.width.toFloat(), bmp.height.toFloat())
    val cw = c.r - c.l; val ch = c.b - c.t
    val canvas = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    for (m in marks) {
        p.color = (m.color and 0x00FFFFFF) or 0x66000000
        for (r in TextGeo.rects(tp, m.start, m.end)) {
            canvas.drawRect(c.l + r.l * cw, c.t + r.t * ch, c.l + r.r * cw, c.t + r.b * ch, p)
        }
    }
}

fun searchMark(h: Hit) = Mark(h.start, h.end, SEARCH_HIT)

/** 사전 앱으로 글자 보내기: [pkg](설정 '사전 앱', 작업 06 — 비어 있으면 말글사전). 없으면 PROCESS_TEXT 를 받는 앱 고르기. */
fun lookUp(context: Context, text: String, pkg: String = "", onError: (String) -> Unit) {
    val base = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    try {
        context.startActivity(Intent(base).setPackage(pkg.ifBlank { "kr.vici.malgeul" }))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent.createChooser(base, "사전으로 찾기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            onError("글자를 받을 사전 앱이 없어요.")
        }
    }
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("ASH Reader", text))
}

/**
 * S-28 글자 선택 층: 고른 글자 칠하기 + 양 끝 손잡이 + 복사·사전·검색·형광펜·메모 띠.
 * 바깥(빈 곳)을 누르면 [onDismiss]. 쪽 칸([PageSlot])이 화면보다 커도(확대 화면, 7B) 띠는 화면 안에 둔다.
 */
@Composable
fun SelectionLayer(
    sel: Selection,
    c: ReadingColors,
    onChange: (Selection) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDict: () -> Unit,
    onHighlight: (color: Int) -> Unit,
    onMemo: () -> Unit,
    /** 고른 글자로 본문 검색(7B). null 이면 띠에 없음. */
    onSearch: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val cur by rememberUpdatedState(sel)
    val box = sel.slot.content(sel.tp)
    val bw = box.r - box.l; val bh = box.b - box.t
    val rects = remember(sel) { TextGeo.rects(sel.tp, sel.start, sel.end) }
    var palette by remember { mutableStateOf(false) }

    fun toIndex(px: Float, py: Float): Int {
        val s = cur
        return SelectGeo.charAt(s.slot, s.tp, px, py, maxDist = 0.5f)
    }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGesturesCompat(onDismiss) }) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        ComposeCanvas(Modifier.fillMaxSize()) {
            val fill = Color(c.accent).copy(alpha = 0.28f)
            for (r in rects) {
                drawRect(fill, Offset(box.l + r.l * bw, box.t + r.t * bh), Size((r.r - r.l) * bw, (r.b - r.t) * bh))
            }
        }
        if (rects.isNotEmpty()) {
            val first = rects.first(); val last = rects.last()
            val startPt = Offset(box.l + first.l * bw, box.t + first.b * bh)
            val endPt = Offset(box.l + last.r * bw, box.t + last.b * bh)
            Handle(startPt, c, "선택 시작 손잡이") { p ->
                val i = toIndex(p.x + 1f, p.y - 8f)
                val s = cur
                if (i >= 0) onChange(if (i < s.end) s.copy(start = i) else s.copy(start = s.end - 1, end = i + 1))
            }
            Handle(endPt, c, "선택 끝 손잡이") { p ->
                val i = toIndex(p.x - 1f, p.y - 8f)
                val s = cur
                if (i >= 0) onChange(if (i + 1 > s.start) s.copy(end = i + 1) else s.copy(start = i, end = s.start + 1))
            }

            // 띠: 고른 글자 위(공간이 없으면 아래). 화면 밖으로 나가지 않게(확대 화면).
            val barH = with(density) { 56.dp.toPx() }
            val barW = with(density) { 300.dp.toPx() }.coerceAtMost(screenW)
            val top = box.t + first.t * bh
            val xMid = ((box.l + rects.minOf { it.l } * bw) + (box.l + rects.maxOf { it.r } * bw)) / 2f
            val (bx, by) = SelectGeo.barPos(
                centerX = xMid, selTop = top, selBottom = box.t + last.b * bh,
                barW = barW, barH = barH, screenW = screenW, screenH = screenH,
                topLimit = maxOf(sel.slot.top, 0f) + with(density) { 24.dp.toPx() }, below = with(density) { 28.dp.toPx() },
            )
            Row(
                Modifier
                    .offset { IntOffset(bx.roundToInt(), by.roundToInt()) }
                    .widthIn(max = 300.dp)
                    .background(c.barC, RoundedCornerShape(14.dp))
                    .border(1.dp, c.lineC, RoundedCornerShape(14.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!palette) {
                    PopItem("복사", c, onCopy)
                    PopItem("사전", c, onDict)
                    if (onSearch != null) PopItem("검색", c, onSearch)
                    PopItem("형광펜", c) { palette = true }
                    PopItem("메모", c, onMemo)
                } else {
                    HighlightColors.forEachIndexed { i, col ->
                        Box(
                            Modifier.size(48.dp).clickable { onHighlight(i) }.semantics { contentDescription = "${HighlightNames[i]} 형광펜" },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(26.dp).background(Color(col), CircleShape).border(1.dp, c.lineC, CircleShape)) }
                    }
                    PopItem("취소", c) { palette = false }
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: () -> Unit) {
    detectTapGestures(onTap = { onTap() })
}

@Composable
private fun PopItem(label: String, c: ReadingColors, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, color = c.fgC, fontWeight = FontWeight.Medium)
    }
}

/** 48dp 손잡이. [at] = 손잡이가 붙는 글자 아래 모서리(px). 끌면 손가락 위치(px)를 넘긴다. */
@Composable
private fun Handle(at: Offset, c: ReadingColors, label: String, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val half = with(density) { 24.dp.toPx() }
    var drag by remember { mutableStateOf<Offset?>(null) }
    val pos by rememberUpdatedState(at)
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), at.y.roundToInt()) }
            .size(48.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drag = pos },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { change, d ->
                    change.consume()
                    val p = (drag ?: pos) + d
                    drag = p
                    onDrag(p)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = 2.dp).size(18.dp).background(c.accentC, CircleShape))
    }
}

/**
 * S-26 본문 검색(작업 07 ReaderNew ④ 책 검색 시트): 위에서 내려온 시트 — 찾기 칸 + "이 책에서 N곳" + 장·쪽과 문장(검색어 노랗게).
 * 누르면 그 쪽으로 가고, 읽기 화면 위 띠의 ‹ › 로 찾은 곳 사이를 옮긴다. 백그라운드에서 쪽마다 찾고, 글을 바꾸면 앞 검색은 취소된다.
 */
@Composable
fun SearchPanel(
    doc: ReaderDoc,
    c: ReadingColors,
    onClose: () -> Unit,
    /** 처음 넣어 둘 검색어(글자 선택 '검색', 7B). */
    initialQuery: String = "",
    /** 쪽 → 장 이름(목차가 있을 때). */
    chapterOf: (Int) -> String? = { null },
    /** 고른 곳과 찾은 곳 모두(‹ › 이동용). */
    onPick: (Hit, List<Hit>) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val results = remember { mutableStateListOf<Hit>() }
    var progress by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var submitted by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, submitted) {
        results.clear()
        val q = query.trim()
        if (q.length < 1) { progress = ""; return@LaunchedEffect }
        delay(350) // 입력 중엔 기다림(새 글자가 오면 이 작업은 취소됨)
        val n = doc.pageCount
        var noText = 0
        for (i in 0 until n) {
            if (i % 5 == 0) progress = "${i + 1} / ${n}쪽 찾는 중…"
            val tp = withContext(Dispatchers.IO) { doc.pageText(i) }
            if (tp == null) { noText++; continue }
            for (s in TextGeo.find(tp.text, q)) {
                results += Hit(i, s, s + q.length, TextGeo.snippet(tp.text, s, q.length))
                if (results.size >= 500) break
            }
            if (results.size >= 500) break
        }
        progress = when {
            noText == n -> "이 문서는 글자 정보가 없어 본문 검색을 할 수 없어요."
            results.isEmpty() -> "‘$q’을(를) 찾지 못했어요."
            results.size >= 500 -> "이 책에서 500곳 넘게 찾아 앞쪽 500곳만 보여 줘요."
            else -> "이 책에서 ${results.size}곳"
        }
    }

    // 뒤 화면 흐리게 + 위에서 86dp 아래로 내려온 둥근 시트(목업)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(onClick = onClose).semantics { contentDescription = "검색 닫기" })
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 40.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(22.dp)).background(c.bgC).clickable(enabled = false) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("본문에서 찾기", fontSize = 15.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted++ }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.fgC, unfocusedTextColor = c.fgC, focusedBorderColor = c.accentC, unfocusedBorderColor = c.lineC, cursorColor = c.accentC,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "지우기", tint = c.fgC) }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기", color = c.fgC, fontSize = 15.sp) }
        }
        if (progress.isNotEmpty()) Text(progress, fontSize = 12.5.sp, color = c.subC, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        val q = query.trim()
        LazyColumn(Modifier.fillMaxSize()) {
            items(results) { h ->
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(h, results.toList()) }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Row {
                        Text(chapterOf(h.page).orEmpty(), fontSize = 12.sp, color = c.subC, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${h.page + 1}쪽", fontSize = 12.sp, color = c.subC)
                    }
                    Text(markQuery(h.snippet, q), fontSize = 14.sp, lineHeight = 21.sp, color = c.fgC, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                HorizontalDivider(color = c.lineC)
            }
        }
    }
}

/** 문장 속 검색어를 노란 바탕·굵게(목업 #FFE58A). 글자색은 검정(어두운 테마에서도 노란 칠 위가 읽히게). */
private fun markQuery(s: String, q: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(s)
    if (q.isEmpty()) return@buildAnnotatedString
    var i = s.indexOf(q, ignoreCase = true)
    while (i >= 0) {
        addStyle(androidx.compose.ui.text.SpanStyle(background = Color(0xFFFFE58A), color = Color.Black, fontWeight = FontWeight.Bold), i, i + q.length)
        i = s.indexOf(q, i + q.length, ignoreCase = true)
    }
}

/** 목차 목록(들여쓰기 = 깊이). */
@Composable
fun TocList(items: List<kr.vici.ashreader.doc.TocEntry>?, current: Int, onPick: (Int) -> Unit) {
    when {
        items == null -> Text("목차를 읽는 중…", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        items.isEmpty() -> Text("이 문서에는 목차가 없어요.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        else -> {
            val curIdx = items.indexOfLast { it.page in 0..current }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = t.page >= 0) { onPick(t.page) }
                            .padding(start = (t.depth.coerceAtMost(4) * 16).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.title.ifBlank { "(제목 없음)" }, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (i == curIdx) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
                        )
                        if (t.page >= 0) Text("${t.page + 1}", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
