// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.Links
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.progress
import kr.vici.ashreader.doc.Covers

private enum class Shelf(val label: String) { RECENT("최근"), ALL("전체"), FORMAT("형식별"), FOLDER("폴더별"), SERIES("시리즈") }

/** S-10 서재 홈. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onAdd: () -> Unit,
    onSeries: (String) -> Unit,
    onRemove: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var shelf by rememberSaveable { mutableStateOf(Shelf.RECENT) }
    var status by rememberSaveable { mutableStateOf(-1) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var storeMissing by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Book?>(null) }
    var listMode by rememberSaveable { mutableStateOf(false) }

    val cont = books.firstOrNull { it.status == Book.READING && it.lastOpenedAt > 0 }
    val filtered = books
        .filter { status < 0 || it.status == status }
        .filter { q -> query.isNullOrBlank() || q.title.contains(query!!, ignoreCase = true) }
        .let { l -> if (shelf == Shelf.ALL) l.sortedBy { it.title } else l }

    LazyVerticalGrid(
        columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("서재", fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { query = if (query == null) "" else null }, modifier = Modifier.size(48.dp)) { Icon(Icons.Search, contentDescription = "검색") }
                    IconButton(onClick = { listMode = !listMode }, modifier = Modifier.size(48.dp)) { Icon(Icons.Grid, contentDescription = "보기 전환") }
                }
                if (query != null) {
                    OutlinedTextField(value = query!!, onValueChange = { query = it }, singleLine = true, placeholder = { Text("제목으로 찾기") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shelf.entries.forEach { s -> Pill(s.label, s == shelf) { shelf = s } }
                }
                Text("내 서점 앱", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    StoreChip("예스24", dashed = false) { if (!Links.launch(context, Links.YES24)) storeMissing = true }
                    StoreChip("+ 책 추가", dashed = true, onClick = onAdd)
                }
                if (cont != null) ContinueCard(cont) { onOpen(cont) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    listOf(Book.READING to "읽는 중", Book.TO_READ to "읽을 책", Book.DONE to "다 읽음").forEach { (st, label) ->
                        val n = books.count { it.status == st }
                        val on = status == st
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { status = if (on) -1 else st }
                                .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("$label $n", fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) }
                    }
                }
                if (books.isEmpty()) {
                    Text("아직 책이 없어요. '+ 책 추가'를 눌러 기기에 있는 파일을 서재에 넣으세요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
        }
        when (shelf) {
            Shelf.RECENT, Shelf.ALL -> items(filtered, key = { it.id }) { b -> BookTile(b, listMode, { onOpen(b) }, { menuFor = b }) }
            Shelf.FORMAT, Shelf.FOLDER -> {
                val groups = filtered.groupBy { if (shelf == Shelf.FORMAT) DocFormat.of(it.format).group.title else it.folder.ifBlank { "가져온 파일" } }
                groups.toSortedMap().forEach { (g, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-$g") { SectionTitle("$g · ${list.size}") }
                    items(list, key = { it.id }) { b -> BookTile(b, listMode, { onOpen(b) }, { menuFor = b }) }
                }
            }
            Shelf.SERIES -> {
                val series = filtered.filter { it.seriesKey.isNotBlank() }.groupBy { it.seriesKey }
                if (series.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("제목 끝에 권·화 번호가 있는 책을 시리즈로 묶어 보여 줘요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                series.forEach { (key, vols) ->
                    item(key = "s-$key") {
                        val first = vols.minBy { it.volume }
                        Column(Modifier.clickable { onSeries(key) }.semantics { contentDescription = "$key 시리즈, ${vols.size}권" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Cover(first, Modifier.fillMaxWidth())
                            Text(key, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${vols.size}권 · ${vols.count { it.status == Book.DONE }}권 읽음", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (storeMissing) {
        AlertDialog(
            onDismissRequest = { storeMissing = false },
            text = { Text("예스24 eBook 앱이 설치되어 있지 않아요. Play 스토어에서 볼까요?") },
            confirmButton = { TextButton(onClick = { storeMissing = false; Links.openStore(context, Links.YES24) }) { Text("Play 스토어 열기") } },
            dismissButton = { TextButton(onClick = { storeMissing = false }) { Text("닫기") } },
        )
    }
    menuFor?.let { b ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("${DocFormat.of(b.format).badge} · ${b.sizeBytes / 1024}KB · ${b.folder}") },
            confirmButton = { TextButton(onClick = { menuFor = null; onOpen(b) }) { Text("열기") } },
            dismissButton = { TextButton(onClick = { menuFor = null; onRemove(b) }) { Text("서재에서 빼기") } },
        )
    }
}

@Composable
private fun SectionTitle(t: String) = Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .then(if (selected) Modifier.background(cs.primary) else Modifier.border(BorderStroke(1.dp, cs.outline), RoundedCornerShape(20.dp)))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = if (selected) cs.onPrimary else cs.onSurface) }
}

@Composable
private fun StoreChip(label: String, dashed: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .then(if (dashed) Modifier.border(1.dp, cs.outline, RoundedCornerShape(12.dp)) else Modifier.background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // TODO(D-009): 서점 앱 아이콘은 설치된 앱 아이콘을 쓰거나 ChatGPT 제작 그림으로 교체
        if (!dashed) Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(cs.outlineVariant))
        Text(label, fontSize = 14.sp, color = if (dashed) cs.onSurfaceVariant else cs.onSurface)
    }
}

@Composable
private fun ContinueCard(b: Book, onClick: () -> Unit) {
    val f = DocFormat.of(b.format)
    Row(
        Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ash.Ink).clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val img = rememberCover(b)
        Box(Modifier.size(56.dp, 78.dp).clip(RoundedCornerShape(4.dp)).background(Ash.Cover), contentAlignment = Alignment.BottomStart) {
            if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (img == null) Text(f.badge, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4A3A2C), modifier = Modifier.padding(4.dp))
            else Badge(f, Modifier.padding(3.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("이어 읽기", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            LinearProgressIndicator(progress = { b.progress() }, color = Ash.Gold, trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().height(4.dp), drawStopIndicator = {})
            Text("${(b.progress() * 100).toInt()}% · ${b.lastPage + 1} / ${b.pageCount}쪽", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 책 표지 썸네일(백그라운드에서 뽑아 캐시). 없거나 아직이면 null. */
@Composable
fun rememberCover(b: Book): ImageBitmap? {
    val context = LocalContext.current
    val img by produceState(Covers.peek(b)?.asImageBitmap(), b.id, b.path) {
        Covers.load(context, b)?.let { bmp -> value = bmp.asImageBitmap() }
    }
    return img
}

@Composable
fun Cover(b: Book, modifier: Modifier = Modifier) {
    val f = DocFormat.of(b.format)
    val img = if (Covers.supported(f)) rememberCover(b) else null
    Box(modifier.aspectRatio(3f / 4.2f).clip(RoundedCornerShape(6.dp)).background(f.coverColor())) {
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                b.title, fontFamily = Myeongjo, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                color = if (f.group == kr.vici.ashreader.data.FormatGroup.COMIC) Color.White else Ash.Text,
                modifier = Modifier.padding(8.dp),
            )
        }
        Badge(f, Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

@Composable
fun Badge(f: DocFormat, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(f.badgeColor()).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(f.badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(b: Book, list: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mod = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong).semantics { contentDescription = "${b.title}, ${DocFormat.of(b.format).badge}, ${(b.progress() * 100).toInt()}% 읽음" }
    if (list) {
        Row(mod.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(DocFormat.of(b.format))
            Column(Modifier.weight(1f)) {
                Text(b.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(b.progress() * 100).toInt()}%", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        return
    }
    Column(mod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Cover(b, Modifier.fillMaxWidth())
        Text(b.title, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(34.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(cs.outlineVariant)) {
            Box(Modifier.fillMaxWidth(b.progress()).height(3.dp).background(cs.primary))
        }
    }
}
