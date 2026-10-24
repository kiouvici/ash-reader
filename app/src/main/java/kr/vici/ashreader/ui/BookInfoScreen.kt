// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.MetaRules
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.BookMeta
import kr.vici.ashreader.doc.Covers
import kr.vici.ashreader.net.BookInfoNet

// 작업 06 B 3순위: 책 정보 찾기·고치기(대표님 목업 BookInfo 통과 2026-10-24 19:22).
// 책 정보 = 칸마다 출처(파일/파일 이름/인터넷/직접) · 표지 고르기[인터넷/첫 쪽/파일 속 그림/사진에서] · [다른 정보 찾기] · [원래대로].
// 파일은 고치지 않고 서재 정보만 바꾼다.

private class InfoColors(val bg: Color, val card: Color, val ink: Color, val ink2: Color, val sub: Color, val line: Color, val blue: Color, val blueBg: Color, val button: Color, val onButton: Color)

@Composable
private fun infoColors(): InfoColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 작업 07: 어둡게는 애플 북스 다크(바탕 #000·카드 #1C1C1E), 작은 보조 글자는 감수 1 값
    return if (dark) InfoColors(Color.Black, Color(0xFF1C1C1E), Color.White, Color(0xFFC7C7CC), Color(0xFF98989F), Color(0xFF2C2C2E), Color(0xFF409CFF), Color(0x29409CFF), Color.White, Color.Black)
    else InfoColors(Color(0xFFF2F2F4), Color.White, Color(0xFF111316), Color(0xFF3A3A3C), Color(0xFF6C6C70), Color(0xFFECECEF), Color(0xFF0A6CD6), Color(0x1A0A84FF), Color(0xFF111316), Color.White)
}

/** 정보 출처 글자. */
private fun metaLabel(src: Int): String = when (src) {
    Book.META_FILE -> "파일"
    Book.META_NET_AUTO, Book.META_NET_PICKED -> "인터넷"
    Book.META_USER -> "직접"
    else -> "파일 이름"
}

/** 책 정보 화면(표지를 길게 누름 → 문서 메뉴 → 책 정보). [book] 은 서재 흐름에서 늘 새 값. */
@Composable
fun BookInfoScreen(book: Book, all: List<Book>, s: Settings, onSettings: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.ash
    val dao = app.db.books()
    val scope = rememberCoroutineScope()
    val c = infoColors()
    var searching by rememberSaveable(book.id) { mutableStateOf(false) }
    var askNet by remember { mutableStateOf(false) }
    var askSeries by remember { mutableStateOf<List<Book>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { Covers.saveChosenBytes(context, book.id, it) } == true
            }
            if (ok) dao.setCoverSource(book.id, Book.COVER_PHOTO) else note = "그림을 읽을 수 없어요."
            busy = false
        }
    }
    fun search() { if (s.metaNet) searching = true else askNet = true }

    if (searching) {
        BackHandler { searching = false }
        SearchMore(book, c, onBack = { searching = false }) { cand, score ->
            scope.launch {
                busy = true
                BookMeta.applyCandidate(context, dao, book, cand, Book.META_NET_PICKED, score, cover = true)
                busy = false
                searching = false
                // 시리즈: 한 권을 직접 고르면 나머지 권도 맞출지 묻는다
                BookMeta.seriesMates(book, all).takeIf { it.isNotEmpty() }?.let { askSeries = it }
            }
        }
    } else {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InfoSquare(c, onBack)
                    Text("책 정보", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = { scope.launch { busy = true; BookMeta.reset(context, dao, book); busy = false } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("원래대로", color = c.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)) {
                    ShelfCover(book, Modifier.width(112.dp).aspectRatio(1f / 1.48f))
                    Column(Modifier.weight(1f).padding(start = 16.dp, top = 4.dp)) {
                        Text(book.shownTitle, fontSize = 19.sp, fontWeight = FontWeight.Black, lineHeight = 25.sp, color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (book.author.isNotBlank()) Text(book.author, fontSize = 14.sp, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                        if (book.publisher.isNotBlank()) Text(book.publisher, fontSize = 12.5.sp, color = c.sub, modifier = Modifier.padding(top = 2.dp))
                        val tag = when (book.metaSource) {
                            Book.META_NET_AUTO -> "인터넷 · 자동 매칭"
                            Book.META_NET_PICKED -> "인터넷 · 직접 고름"
                            Book.META_FILE -> "파일 속 정보"
                            Book.META_USER -> "직접 입력"
                            else -> if (book.needsReview) "확인 필요" else "파일 이름"
                        }
                        Box(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(6.dp)).background(c.blueBg).heightIn(min = 24.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text(tag, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = c.blue)
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.card)) {
                    val titleSrc = if (book.infoTitle.isBlank()) "파일 이름" else metaLabel(book.metaSource)
                    Field("제목", book.shownTitle, titleSrc, c)
                    Field("지은이", book.author.ifBlank { "—" }, if (book.author.isBlank()) "" else metaLabel(book.metaSource), c)
                    Field("출판사", book.publisher.ifBlank { "—" }, if (book.publisher.isBlank()) "" else metaLabel(book.metaSource), c)
                    if (book.isbn.isNotBlank()) Field("ISBN", book.isbn, metaLabel(book.metaSource), c)
                    val series = SeriesGroups.keyOf(book)?.let { k -> SeriesGroups.groups(all)[k]?.let { "${SeriesGroups.nameOf(k)} · ${it.size}권" } }
                    Field("시리즈", series ?: "— (없음)", "", c, last = true)
                }
                Text("표지 고르기", fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(start = 16.dp, top = 14.dp).semantics { heading() })
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoverChoice("인터넷", book.coverSource == Book.COVER_INTERNET, c) { if (book.coverSource != Book.COVER_INTERNET) search() }
                    CoverChoice("첫 쪽", book.coverSource == Book.COVER_FIRST_PAGE, c) {
                        scope.launch {
                            busy = true
                            val bmp = Covers.renderFirstPage(context, book)
                            if (bmp != null) { withContext(Dispatchers.IO) { Covers.saveChosen(context, book.id, bmp) }; bmp.recycle(); dao.setCoverSource(book.id, Book.COVER_FIRST_PAGE) }
                            else note = "첫 쪽을 그릴 수 없어요."
                            busy = false
                        }
                    }
                    CoverChoice("파일 속 그림", book.coverSource == Book.COVER_AUTO, c) {
                        scope.launch { Covers.deleteChosen(context, book.id); dao.setCoverSource(book.id, Book.COVER_AUTO) }
                    }
                    CoverChoice("사진에서", book.coverSource == Book.COVER_PHOTO, c, plus = true) { photo.launch("image/*") }
                }
                Text(
                    "파일은 고치지 않고 서재 정보만 바꿔요. '원래대로'로 언제든 되돌릴 수 있어요.",
                    fontSize = 12.sp, lineHeight = 19.sp, color = c.sub, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                )
            }
            if (BookMeta.netManualTarget(book)) Box(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).padding(bottom = 10.dp).fillMaxWidth().heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(12.dp)).background(c.button).clickable(role = Role.Button) { search() },
                contentAlignment = Alignment.Center,
            ) { Text("다른 정보 찾기", color = c.onButton, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (askNet) AlertDialog(
        onDismissRequest = { askNet = false },
        title = { Text("인터넷에서 찾을까요?") },
        text = { Text("표지·지은이·출판사를 국립중앙도서관·카카오·구글 북스에서 찾아요. 정리한 책 제목만 보내고, 책 내용·폴더 이름은 보내지 않아요. 설정 › 책 정보에서 끌 수 있어요.") },
        confirmButton = { TextButton(onClick = { askNet = false; onSettings { it.copy(metaNet = true, metaAsked = true) }; searching = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("켜기") } },
        dismissButton = { TextButton(onClick = { askNet = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
    askSeries?.let { mates ->
        AlertDialog(
            onDismissRequest = { askSeries = null },
            text = { Text("나머지 권(${mates.size}권)도 같은 책으로 맞출까요? 지은이·출판사를 같게 해요(제목·표지는 권마다 그대로).") },
            confirmButton = {
                TextButton(onClick = {
                    askSeries = null
                    scope.launch { dao.get(book.id)?.let { BookMeta.matchSeries(dao, it, mates) } }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("맞추기") }
            },
            dismissButton = { TextButton(onClick = { askSeries = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이 권만") } },
        )
    }
    note?.let { AlertDialog(onDismissRequest = { note = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { note = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } }) }
}

@Composable
private fun InfoSquare(c: InfoColors, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "뒤로" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Back, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun Field(k: String, v: String, src: String, c: InfoColors, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = c.sub, modifier = Modifier.width(64.dp))
        Text(v, fontSize = 14.5.sp, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (src.isNotEmpty()) Text(src, fontSize = 11.sp, color = if (src == "인터넷") c.blue else c.sub, modifier = Modifier.padding(start = 8.dp))
    }
    if (!last) HorizontalDivider(color = c.line)
}

/** 표지 고르기 칸 하나(고른 것은 파란 테두리). */
@Composable
private fun CoverChoice(label: String, on: Boolean, c: InfoColors, plus: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = on; contentDescription = "표지: $label" }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp, 94.dp).then(if (on) Modifier.border(2.dp, Color(0xFF0A84FF), RoundedCornerShape(4.dp)).padding(4.dp) else Modifier.padding(4.dp))
                .clip(RoundedCornerShape(2.dp)).background(if (plus) c.card else Color(0xFFD9D9DE)),
            contentAlignment = Alignment.Center,
        ) { if (plus) Text("+", fontSize = 22.sp, color = c.sub) }
        Text(label, fontSize = 11.sp, color = c.sub, modifier = Modifier.padding(top = 7.dp))
    }
}

/** ② 다른 정보 찾기: 검색어를 고쳐 다시 찾고, 결과(일치도 순)를 누르면 적용. */
@Composable
private fun SearchMore(book: Book, c: InfoColors, onBack: () -> Unit, onPick: (MetaRules.Candidate, Int) -> Unit) {
    val context = LocalContext.current
    // 파일 속 제목이 있으면 그것으로, 없으면 파일 이름을 정리해서
    val first = remember(book.id) { MetaRules.query(book.infoTitle.ifBlank { book.title }).let { q -> if (q.volume > 0) "${q.title} ${q.volume}" else q.title } }
    var text by rememberSaveable(book.id) { mutableStateOf(first) }
    var asked by remember { mutableStateOf(first) }
    val results by produceState<List<Pair<MetaRules.Candidate, MetaRules.Match>>?>(null, asked) {
        value = null
        val q = MetaRules.query(asked)
        value = runCatching { MetaRules.rank(q, BookInfoNet.search(context, q)) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoSquare(c, onBack)
            Text("다른 정보 찾기", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(end = 48.dp).semantics { heading() })
        }
        TextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Search, contentDescription = null, tint = c.sub, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (text != asked) TextButton(onClick = { asked = text.trim() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("찾기") }
                else if (text == first) Text(if (book.infoTitle.isBlank()) "파일 이름에서" else "파일 속 제목에서", fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(end = 12.dp))
            },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { asked = text.trim() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.card, unfocusedContainerColor = c.card, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text("검색어를 고쳐 다시 찾을 수 있어요 · 국립중앙도서관 · 카카오 · 구글 북스", fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val list = results
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("찾은 책이 없어요. 검색어를 줄이거나 바꿔 보세요.", color = c.sub, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                else -> Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.card)) {
                    list.take(20).forEachIndexed { i, (cand, m) ->
                        ResultRow(cand, m, i == 0, c) { onPick(cand, m.score) }
                        if (i < minOf(list.size, 20) - 1) HorizontalDivider(color = c.line)
                    }
                }
            }
            Text(
                "고르면 표지·지은이·출판사를 바꿔요. 파일은 고치지 않고 서재 정보만 바꿔요. '원래대로'로 언제든 되돌릴 수 있어요.",
                fontSize = 12.sp, lineHeight = 19.sp, color = c.sub, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(cand: MetaRules.Candidate, m: MetaRules.Match, top: Boolean, c: InfoColors, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, cand.coverUrl) {
        if (cand.coverUrl.isNotEmpty()) value = BookInfoNet.download(cand.coverUrl)?.let { b ->
            withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp, 68.dp).clip(RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp, topEnd = 2.dp, bottomEnd = 2.dp)).background(Color(0xFFD9D9DE))) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(cand.title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (cand.author.isNotBlank()) Text(cand.author, fontSize = 12.5.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(listOf(cand.publisher, cand.year, cand.source).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.5.sp, color = c.sub, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Text(if (top && m.auto) "자동 매칭 ${m.score}%" else "${m.score}%", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (top) c.blue else c.sub)
    }
}

/** 처음 서재를 열 때 한 번 묻기(작업 06 C): 기본 꺼짐. */
@Composable
fun MetaAskDialog(onOn: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("표지·지은이를 인터넷에서 찾을까요?") },
        text = { Text("책 제목만 보내요. 책 내용·폴더 이름은 보내지 않아요. 파일 안에 정확한 정보가 있는 책은 그대로 두고, 와이파이에서만 찾아요. 설정 › 책 정보에서 바꿀 수 있어요.") },
        confirmButton = { TextButton(onClick = onOn, modifier = Modifier.heightIn(min = 48.dp)) { Text("켜기") } },
        dismissButton = { TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("나중에") } },
    )
}
