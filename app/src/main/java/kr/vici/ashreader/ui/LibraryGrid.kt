// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.ShelfCollection
import kr.vici.ashreader.data.progress
import kr.vici.ashreader.doc.FirstPara

// 작업 07 A·B·C: 전체 격자(LibraryAll) · 목록 보기(LibraryMenus ③) · 선택 · 직접 정렬 · 묶음 머리(SeriesDetail).

/**
 * 격자 열 수: 책장 줄 표지 폭([shelfCoverW])과 같은 크기가 되게 정한다(대표님 23:40 '모든 책의 크기를 줄여야').
 * 휴대폰 세로 4열, 태블릿은 폭에 맞춰. [pad]·[gap] = 바깥 여백·칸 사이.
 */
private fun gridCols(w: Dp, pad: Dp, gap: Dp): Int =
    ((w - pad * 2 + gap) / (shelfCoverW(w) + gap)).let { kotlin.math.round(it).toInt() }.coerceIn(3, 8)

/** 선택 모드·직접 정렬 모드 상태(화면마다). */
private class ShelfModes {
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(emptySet<String>())
    var reordering by mutableStateOf(false)
}

/**
 * 모음 화면(전체·최근·읽는 중·다 읽음·최근 받은 파일·숨긴 책·사용자 모음): 위 [뒤로] … [≡][⋯], 큰 제목, 격자(또는 목록), 맨 아래 권수.
 * [stackSeries] 가 있으면(전체) 시리즈 권들은 표지 겹쳐 쌓기 하나("N권의 책")로. [fixedSort] = 늘 최근 읽은 순(최근 ›).
 */
@Composable
internal fun FlatShelf(
    h: ShelfHost, key: String, title: String, books: List<Book>, onBack: () -> Unit,
    stackSeries: List<LibGroup>? = null, fixedSort: Boolean = false, hiddenShelf: Boolean = false, collection: ShelfCollection? = null,
) {
    val c = h.c
    val context = LocalContext.current
    val dao = context.ash.db.books()
    val view = h.view(key).let { if (fixedSort) it.copy(sort = GroupSort.RECENT) else it }
    val order by remember(key) { dao.order(key) }.collectAsState(initial = emptyList())
    val sorted = remember(books, view.sort, order) { view.sort.apply(books, order.map { it.bookId }) }
    val items = remember(sorted, stackSeries) { if (stackSeries != null && view.sort != GroupSort.MANUAL) ShelfItems.stacked(sorted, stackSeries) else sorted.map { ShelfItem.One(it) } }
    val modes = remember(key) { ShelfModes() }
    BackHandler(enabled = modes.selecting || modes.reordering) { modes.selecting = false; modes.selected = emptySet(); modes.reordering = false }
    val now = remember { System.currentTimeMillis() }
    val collBtn = remember { RectHolder() }
    val moreBtn = remember { RectHolder() }
    var moreOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val seriesCount = items.count { it is ShelfItem.Stack }
    BoxWithConstraints(h.shared.of("sec-$key").fillMaxSize().background(c.detail)) {
        val wide = maxWidth >= 600.dp
        val pad = if (wide) 40.dp else 20.dp
        val cols = gridCols(maxWidth, pad, if (wide) 36.dp else 16.dp)
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 90 } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (view.list || modes.reordering) 1 else cols),
            modifier = Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = pad, end = pad, bottom = (if (modes.selecting) 120.dp else 36.dp) + barBottom()),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 36.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (view.list) 0.dp else 14.dp),
        ) {
            item(key = "top", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 62.dp + barTop(), bottom = 10.dp)) {
                    Text(
                        if (modes.selecting) "${modes.selected.size}권 선택" else title,
                        fontSize = LARGE_TITLE.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                    )
                    if (modes.reordering) Text("▲▼ 단추로 순서를 바꿔요. 끝나면 [완료].", fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
                    if (hiddenShelf && books.isNotEmpty()) Text("숨긴 책은 서재의 다른 곳에 보이지 않아요. ⋯ 메뉴에서 다시 보이게 할 수 있어요.", fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when {
                        hiddenShelf -> "숨긴 책이 없어요."
                        collection != null -> "아직 책이 없어요. 책의 ⋯ 메뉴 › '모음에 넣기…'나, ⋯ › 선택으로 여러 권을 넣을 수 있어요."
                        else -> "아직 책이 없어요."
                    },
                    color = c.sub, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            if (modes.reordering) {
                items(sorted.size, key = { sorted[it].id }, span = { GridItemSpan(maxLineSpan) }) { i ->
                    ReorderRow(sorted[i], c, i, sorted.size) { from, to ->
                        val ids = sorted.map { it.id }.toMutableList().also { l -> l.add(to, l.removeAt(from)) }
                        context.ash.appScope.launch { dao.clearOrder(key); dao.insertOrder(ids.mapIndexed { n, id -> kr.vici.ashreader.data.BookOrder(key, id, n) }) }
                    }
                }
            } else if (view.list) {
                items(items.size, key = { items[it].key }, span = { GridItemSpan(maxLineSpan) }) { i ->
                    when (val it = items[i]) {
                        is ShelfItem.One -> ListBook(it.book, h, now, modes, collection)
                        is ShelfItem.Stack -> StackListRow(it.group, h)
                    }
                }
            } else {
                items(items.size, key = { items[it].key }) { i ->
                    when (val it = items[i]) {
                        is ShelfItem.One -> GridBook(it.book, h, now, modes, collection)
                        is ShelfItem.Stack -> GridStack(it.group, h)
                    }
                }
            }
            if (books.isNotEmpty()) item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "책 ${books.size}권" + if (seriesCount > 0) ", 시리즈 ${seriesCount}개" else "",
                    fontSize = 13.sp, color = c.sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
            }
        }
        ScrollTopBar(c, if (modes.selecting) "${modes.selected.size}권 선택" else title, scrolled)
        // 위 단추: 왼쪽 뒤로(선택·정렬 중이면 완료), 오른쪽 ≡ 모음 · ⋯ 보기·정렬
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp + barTop()), verticalAlignment = Alignment.CenterVertically) {
            if (modes.selecting || modes.reordering) DoneButton(c) { modes.selecting = false; modes.selected = emptySet(); modes.reordering = false }
            else FloatingButton(c, "뒤로", Icons.Back, onClick = onBack)
            Spacer(Modifier.weight(1f))
            FloatingButton(c, "모음", Icons.Menu, Modifier.trackRect(collBtn)) { h.collMenu(collBtn.rect) }
            FloatingButton(c, "보기·정렬", Icons.Dots, Modifier.padding(start = 6.dp).trackRect(moreBtn)) { moreOpen = true }
        }
        if (modes.selecting) SelectBar(h, books.filter { it.id in modes.selected }, hiddenShelf, collection, Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom())) {
            modes.selecting = false; modes.selected = emptySet()
        }
    }
    if (moreOpen) ViewSortMenu(
        moreBtn.rect, view, onSelect = { modes.selecting = true; modes.reordering = false },
        onView = { v -> if (!fixedSort || v.sort == GroupSort.RECENT) h.setView(key, v) }, onDismiss = { moreOpen = false },
        extra = {
            if (view.sort == GroupSort.MANUAL) MenuRow("순서 바꾸기…", Icons.Up, sepTop = true) { moreOpen = false; modes.reordering = true; modes.selecting = false }
            if (collection != null) {
                MenuRow("모음 이름 바꾸기…", Icons.Pen, sepTop = view.sort != GroupSort.MANUAL) { moreOpen = false; renaming = true }
                MenuRow("모음 지우기…", Icons.Trash, color = Color(0xFFFF3B30)) { moreOpen = false; deleting = true }
            }
        },
    )
    if (renaming && collection != null) CollectionNameDialog("모음 이름 바꾸기", collection.name, taken = { false }, onDone = { n ->
        renaming = false; context.ash.appScope.launch { dao.renameCollection(collection.id, n) }
    }, onDismiss = { renaming = false })
    if (deleting && collection != null) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("‘${collection.name}’ 모음을 지울까요?") },
        text = { Text("모음만 지워요. 책과 파일은 서재에 그대로 있어요.") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                context.ash.appScope.launch { dao.clearCollection(collection.id); dao.deleteCollectionRow(collection.id); dao.clearOrder(ShelfColls.userKey(collection.id)) }
                onBack()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지우기", color = Color(0xFFB3261E)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

@Composable
private fun DoneButton(c: ShelfColors, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(c.ink).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text("완료", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.detail) }
}

/** 선택한 책 일: 모음에 넣기 · 다 읽음 · 숨기기(숨긴 책에서는 다시 보이기) · 이 모음에서 빼기 · 서재에서 빼기. */
@Composable
private fun SelectBar(h: ShelfHost, picked: List<Book>, hiddenShelf: Boolean, collection: ShelfCollection?, modifier: Modifier, onDone: () -> Unit) {
    val c = h.c
    val app = LocalContext.current.ash
    val dao = app.db.books()
    var confirm by remember { mutableStateOf(false) }
    val on = picked.isNotEmpty()
    Row(
        modifier.padding(12.dp).clip(RoundedCornerShape(16.dp)).background(glassBg())
            .border(1.dp, c.barLine, RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarAction(c, "모음에 넣기", Icons.Plus, on) { h.pickCollection(picked.map { it.id }); onDone() }
        BarAction(c, "다 읽음", Icons.Done, on) { app.appScope.launch { picked.forEach { dao.setStatus(it.id, Book.DONE) } }; onDone() }
        BarAction(c, if (hiddenShelf) "보이기" else "숨기기", if (hiddenShelf) Icons.Show else Icons.Hide, on) {
            app.appScope.launch { picked.forEach { dao.setHidden(it.id, if (hiddenShelf) 0 else Book.HIDDEN_USER) } }; onDone()
        }
        if (collection != null) BarAction(c, "모음에서 빼기", Icons.Minus, on) {
            app.appScope.launch { picked.forEach { dao.removeFromCollection(collection.id, it.id) } }; onDone()
        }
        BarAction(c, "빼기", Icons.Trash, on, Color(0xFFFF3B30)) { confirm = true }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("${picked.size}권을 서재에서 뺄까요?") },
        text = { Text("서재 목록에서만 뺍니다. 기기의 파일은 지우지 않아요.") },
        confirmButton = { TextButton(onClick = { confirm = false; h.removeBooks(picked); onDone() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("빼기", color = Color(0xFFB3261E)) } },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

@Composable
private fun BarAction(c: ShelfColors, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val fg = (if (color == Color.Unspecified) c.ink else color).copy(alpha = if (enabled) 1f else 0.35f)
    Column(
        Modifier.heightIn(min = 56.dp).width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 상태 줄: '새 책'·'42%' 는 알약, 그 밖('다 읽음'·'열 수 없음')은 글자. */
@Composable
private fun StatePill(st: BookState, c: ShelfColors, big: Boolean = false) {
    if (st.fresh || st.reading) {
        Box(
            Modifier.clip(RoundedCornerShape(9.dp)).background(c.pillBg).padding(horizontal = if (big) 8.dp else 6.dp, vertical = 1.dp),
        ) { Text(if (st.fresh) "새 책" else st.text, fontSize = if (big) 12.sp else 10.5.sp, fontWeight = FontWeight.Bold, color = c.pillFg, maxLines = 1) }
    } else Text(st.text, fontSize = if (big) 13.sp else 11.5.sp, color = smallSub, maxLines = 1)
}

/** ⋯ 단추(48dp, 감수 2). [label] = "제목 메뉴". */
@Composable
private fun MoreDots(c: ShelfColors, label: String, onClick: (RectHolder) -> Unit) {
    val r = remember { RectHolder() }
    Box(
        Modifier.size(48.dp).trackRect(r).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onClick(r) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Dots, null, tint = smallSub, modifier = Modifier.size(18.dp)) }
}

/**
 * 격자 칸(LibraryAll A1·A2): 표지(바닥선 맞춤, 읽는 중이면 진행 막대) → 한 줄 [새 책 알약 / 진행 % / 다 읽음] + ⋯.
 * 이북·만화는 제목 없이 표지만, 문서(한글·PDF·Word·텍스트)는 아래 제목 1줄. 선택 모드면 눌러서 고르기.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(b: Book, h: ShelfHost, now: Long, modes: ShelfModes, collection: ShelfCollection?) {
    val c = h.c
    val unavailable = b.id in h.unavailable
    val st = bookState(b, unavailable, now)
    val r = remember { RectHolder() }
    val picked = b.id in modes.selected
    val coll = collection?.let { ShelfColls.userKey(it.id) }
    fun tap() { if (modes.selecting) modes.selected = if (picked) modes.selected - b.id else modes.selected + b.id else h.open(b) }
    Column {
        // TalkBack·시험: "제목, 형식, 42% 읽음"(옛 서재 칸과 같은 모양)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / 1.48f).trackRect(r)
                .combinedClickable(onClick = ::tap, onLongClick = { if (!modes.selecting) h.bookMenu(b, r.rect, false, coll) })
                .clearAndSetSemantics { contentDescription = bookLabel(b, unavailable); role = Role.Button; if (modes.selecting) selected = picked },
            contentAlignment = Alignment.BottomCenter,
        ) {
            ShelfCover(b, Modifier.fillMaxWidth().aspectRatio(coverRatio(b)), bar = true, dim = unavailable)
            if (modes.selecting) SelectMark(picked, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            StatePill(st, c)
            Spacer(Modifier.weight(1f))
            if (!modes.selecting) MoreDots(c, "${b.shownTitle} 메뉴") { h.bookMenu(b, it.rect, true, coll) }
            else Spacer(Modifier.height(48.dp))
        }
        if (isDocBook(b)) Text(
            b.shownTitle, fontSize = 12.sp, color = smallSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = (-10).dp),
        )
    }
}

@Composable
private fun SelectMark(on: Boolean, modifier: Modifier) {
    Box(
        modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.85f))
            .border(1.5.dp, if (on) Color(0xFF0A84FF) else Color(0xFF8E8E93), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold) }
}

/** 시리즈 쌓기 칸(LibraryAll A3): 뒤 표지 + 앞 표지 겹침, 아래 "N권의 책" + ⋯(시리즈 관리). 누르면 시리즈 묶음 화면. */
@Composable
private fun GridStack(g: LibGroup, h: ShelfHost) {
    val c = h.c
    val front = g.books.firstOrNull() ?: return
    val back = g.books.getOrNull(1) ?: front
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / 1.48f).then(h.shared.of(g.id))
                .clickable(role = Role.Button) { h.go(listOf(ShelfColls.SERIES, g.id)) }
                .clearAndSetSemantics { contentDescription = "${g.name}, 시리즈, ${g.books.size}권의 책"; role = Role.Button },
        ) {
            ShelfCover(back, Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp).fillMaxWidth(0.82f).aspectRatio(coverRatio(back)))
            ShelfCover(front, Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.82f).aspectRatio(coverRatio(front)), bar = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text("${g.books.size}권의 책", fontSize = 11.5.sp, color = smallSub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            MoreDots(c, "${g.name} 시리즈 관리") { g.seriesKey?.let(h.seriesManage) }
        }
    }
}

/**
 * 목록 보기 한 줄(LibraryMenus ③): 큰 표지 92dp + 제목 17sp + 지은이 + 상태(파란 굵게) + 첫 문단 3줄 + [이어 읽기] + ⋯.
 * 만화는 첫 문단 대신 시리즈 이름·권수. 첫 문단은 책을 한 번 연 뒤부터 보인다(기기 안에 저장한 것).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBook(b: Book, h: ShelfHost, now: Long, modes: ShelfModes, collection: ShelfCollection?) {
    val c = h.c
    val context = LocalContext.current
    val unavailable = b.id in h.unavailable
    val st = bookState(b, unavailable, now)
    val f = DocFormat.of(b.format)
    val picked = b.id in modes.selected
    val coll = collection?.let { ShelfColls.userKey(it.id) }
    val r = remember { RectHolder() }
    fun tap() { if (modes.selecting) modes.selected = if (picked) modes.selected - b.id else modes.selected + b.id else h.open(b) }
    val by = when {
        isDocBook(b) -> f.badge + if (b.pageCount > 0) " · ${b.pageCount}쪽" else ""
        b.author.isNotBlank() -> "${b.author} · ${if (f.group == FormatGroup.COMIC) "만화" else f.badge}"
        else -> if (f.group == FormatGroup.COMIC) "만화" else f.badge
    }
    val state = when {
        unavailable -> "열 수 없음"
        b.status == Book.DONE -> "다 읽음" + ShelfItems.ago(b.lastOpenedAt, now).let { if (it.isNotEmpty()) " · $it" else "" }
        st.reading -> "${(b.progress() * 100).toInt()}% 읽음 · ${ShelfItems.ago(b.lastOpenedAt, now)}"
        else -> st.text
    }
    val first = when {
        f.group == FormatGroup.COMIC -> SeriesGroups.keyOf(b)?.let { SeriesGroups.nameOf(it) }?.let { n -> if (b.volume > 0) "$n · ${b.volume}권" else n }
        else -> remember(b.id) { FirstPara.load(context, b.id) }
    }
    Row(
        Modifier.fillMaxWidth().trackRect(r)
            .combinedClickable(onClick = ::tap, onLongClick = { if (!modes.selecting) h.bookMenu(b, r.rect, false, coll) })
            .semantics(mergeDescendants = true) { contentDescription = bookLabel(b, unavailable); if (modes.selecting) selected = picked }
            .padding(vertical = 22.dp),
    ) {
        Box {
            ShelfCover(b, Modifier.width(92.dp).aspectRatio(coverRatio(b)), dim = unavailable)
            if (modes.selecting) SelectMark(picked, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(b.shownTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(by, fontSize = 13.5.sp, color = if (c.dark) Color(0xFFC7C7CC) else Color(0xFF3A3A3C), modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, fontSize = 13.sp, color = if (st.reading) c.blue else smallSub, fontWeight = if (st.reading) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
            if (!first.isNullOrBlank()) Text(first, fontSize = 12.5.sp, lineHeight = 19.sp, color = smallSub, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (!modes.selecting) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { h.open(b) }.semantics { contentDescription = "${b.shownTitle} ${if (st.reading) "이어 읽기" else "읽기"}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink).padding(horizontal = 18.dp, vertical = 6.dp)) {
                        Text(if (st.reading) "이어 읽기" else "읽기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.detail)
                    }
                }
                Spacer(Modifier.width(4.dp))
                MoreDots(c, "${b.shownTitle} 메뉴") { h.bookMenu(b, it.rect, true, coll) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (c.dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)))
}

@Composable
private fun StackListRow(g: LibGroup, h: ShelfHost) {
    val c = h.c
    val front = g.books.firstOrNull() ?: return
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { h.go(listOf(ShelfColls.SERIES, g.id)) }
            .clearAndSetSemantics { contentDescription = "${g.name}, 시리즈, ${g.books.size}권의 책"; role = Role.Button }
            .padding(vertical = 22.dp),
    ) {
        ShelfCover(front, Modifier.width(92.dp).aspectRatio(coverRatio(front)))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("시리즈 · ${g.books.size}권의 책", fontSize = 13.5.sp, color = smallSub, modifier = Modifier.padding(top = 3.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (c.dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)))
}

/** 직접 정렬: 한 줄에 작은 표지·제목 + ▲▼(48dp). */
@Composable
private fun ReorderRow(b: Book, c: ShelfColors, i: Int, n: Int, onMove: (Int, Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        ShelfCover(b, Modifier.width(36.dp).aspectRatio(coverRatio(b)))
        Text(b.shownTitle, fontSize = 15.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
        listOf(Triple(Icons.Up, "위로", i - 1), Triple(Icons.Down, "아래로", i + 1)).forEach { (icon, label, to) ->
            val ok = to in 0 until n
            Box(
                Modifier.size(48.dp).clickable(enabled = ok, role = Role.Button) { onMove(i, to) }.semantics { contentDescription = "${b.shownTitle} $label" },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (ok) c.ink else c.sub.copy(alpha = 0.3f), modifier = Modifier.size(18.dp)) }
        }
    }
}

// ── 묶음 안 화면(SeriesDetail) ──

/**
 * 묶음 안 화면(작업 07 C): 위 색 머리(표지 대표색 그러데이션) + 펼친 표지 5장 + 이름·지은이(폴더는 경로)·"시리즈 · 24권 · 만화" +
 * [이어 읽기 / 책 이름 · %]. 그 아래 '이어 읽기' 책장 줄 · 하위 폴더 책장 줄 · '모든 책'.
 * 모든 책 격자는 이어 읽기 줄과 같은 표지 크기로, 줄마다 아래 옅은 회색 띠를 깔아 줄을 나눈다(대표님 2026-10-24 22:58, 애플 북스).
 * ⋯ 에서 목록 ↔ 격자·정렬·선택. 시리즈·폴더·형식 모두 같은 머리.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HeroDetail(h: ShelfHost, g: LibGroup, sec: LibSection, folderPath: List<String>, onBack: () -> Unit, onEnter: (LibGroup) -> Unit) {
    val c = h.c
    val context = LocalContext.current
    val dao = context.ash.db.books()
    val key = "${sec.key}:${g.id}"
    // 묶음 안 기본 보기는 격자(책장 줄) — 목록은 ⋯ 에서
    val view = ShelfViews.parse(h.prefs.views)[key] ?: ShelfView(GroupSort.RECENT, false)
    val order by remember(key) { dao.order(key) }.collectAsState(initial = emptyList())
    val sorted = remember(g, view.sort, order) { view.sort.apply(g.books, order.map { it.bookId }) }
    val modes = remember(key) { ShelfModes() }
    BackHandler(enabled = modes.selecting || modes.reordering) { modes.selecting = false; modes.selected = emptySet(); modes.reordering = false }
    val now = remember { System.currentTimeMillis() }
    val reading = remember(g) { g.books.filter { it.status == Book.READING && it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt } }
    val cta = reading.firstOrNull() ?: sorted.firstOrNull { it.status != Book.DONE } ?: sorted.firstOrNull()
    val moreBtn = remember { RectHolder() }
    var moreOpen by remember { mutableStateOf(false) }
    val fmt = g.books.groupBy { DocFormat.of(it.format).group }.maxByOrNull { it.value.size }?.key
    val meta = when (sec) {
        LibSection.SERIES -> "시리즈 · ${g.books.size}권" + (fmt?.let { " · " + if (it == FormatGroup.COMIC) "만화" else it.title } ?: "")
        LibSection.FOLDER -> "폴더" + (if (g.kids.isNotEmpty()) " · 하위 폴더 ${g.kids.size}개" else "") + " · ${g.books.size}권"
        else -> "형식 · ${g.books.size}권"
    }
    val by = when (sec) {
        LibSection.SERIES -> g.books.map { it.author }.firstOrNull { it.isNotBlank() }.orEmpty()
        LibSection.FOLDER -> folderPath.joinToString(" › ")
        else -> ""
    }
    // 머리 색: 대표 표지 색(없으면 문서 = 남색) → 어둡게
    val tone = cta?.let { coverTone(it) } ?: Color(0xFF3E6E9A)
    val heroTop = mixC(tone, Color.Black, if (c.dark) 0.25f else 0.1f)
    val heroBottom = mixC(tone, Color.Black, if (c.dark) 0.7f else 0.55f)
    BoxWithConstraints(h.shared.of(g.id).fillMaxSize().background(c.detail)) {
        val wide = maxWidth >= 600.dp
        val s = (maxWidth / 390.dp).coerceIn(1f, 1.6f)
        val pad = if (wide) 40.dp else 20.dp
        // 이어 읽기 줄과 모든 책 격자의 표지 폭을 같게 — 첫 화면 책장 줄과도 같은 값
        val coverW = shelfCoverW(maxWidth)
        val gap = 16.dp
        val cols = ((maxWidth - pad * 2 + gap) / (coverW + gap)).toInt().coerceAtLeast(2)
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        // 색 머리를 지나 스크롤하면 위 유리 막대 + 작은 제목, 단추도 흰 글자 → 보통 단추로(흰 바탕 위에서 안 보이지 않게)
        val pastHero by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 } }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = (if (modes.selecting) 120.dp else 36.dp) + barBottom())) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(heroTop, heroBottom)))) {
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.25f))))
                    // 대표님 23:37: 색 머리 높이를 지금의 약 2/3로, 펼친 책도 더 작게
                    // 대표님 23:46: 책 크기는 그대로, 위·아래 여백만 더 줄여 제목·이어 읽기가 위로
                    Column(Modifier.padding(top = 40.dp + barTop())) {
                        HeroFan(g.books, s, Modifier.fillMaxWidth().height(100.dp * s))
                        Column(Modifier.padding(start = pad, end = pad, top = 2.dp, bottom = 10.dp)) {
                            Text(
                                if (modes.selecting) "${modes.selected.size}권 선택" else g.name, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em,
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                            )
                            if (by.isNotBlank()) Text(by, fontSize = 14.sp, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                            Text(meta, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 1.dp))
                            if (cta != null && !modes.selecting) Column(
                                Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.2f)).border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(13.dp))
                                    .clickable(role = Role.Button) { h.open(cta) }
                                    .clearAndSetSemantics { contentDescription = "${if (cta.lastOpenedAt > 0) "이어 읽기" else "읽기 시작"}, ${cta.shownTitle}"; role = Role.Button }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text(if (cta.lastOpenedAt > 0) "이어 읽기" else "읽기 시작", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    cta.shownTitle + if (cta.lastOpenedAt > 0) " · ${(cta.progress() * 100).toInt()}%" else "",
                                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.88f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (!modes.selecting && !modes.reordering) {
                if (reading.size > 1) item(key = "reading") {
                    ShelfRow("이어 읽기", null, reading, h, Modifier, null, coverW = coverW, sidePad = pad, bleedPad = 0.dp)
                }
                g.kids.forEach { k ->
                    item(key = "k-${k.id}") {
                        ShelfRow(k.name, "${k.books.size}권", GroupSort.RECENT.apply(k.books), h, h.shared.of(k.id), { onEnter(k) }, coverW = coverW, sidePad = pad, bleedPad = 0.dp)
                    }
                }
            }
            item(key = "all") {
                Row(Modifier.padding(start = pad, end = pad, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(if (g.kids.isEmpty() && sec == LibSection.SERIES) "책" else "모든 책", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text("${g.books.size}권", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.sub2, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
            }
            when {
                modes.reordering -> items(sorted, key = { it.id }) { b ->
                    val i = sorted.indexOf(b)
                    Box(Modifier.padding(horizontal = pad)) {
                        ReorderRow(b, c, i, sorted.size) { from, to ->
                            val ids = sorted.map { it.id }.toMutableList().also { l -> l.add(to, l.removeAt(from)) }
                            context.ash.appScope.launch { dao.clearOrder(key); dao.insertOrder(ids.mapIndexed { n, id -> kr.vici.ashreader.data.BookOrder(key, id, n) }) }
                        }
                    }
                }
                view.list -> items(sorted, key = { it.id }) { b -> Box(Modifier.padding(horizontal = pad)) { ListBook(b, h, now, modes, null) } }
                else -> items(sorted.chunked(cols), key = { "row-" + it.first().id }) { row ->
                    // 책장 줄처럼: 표지 아래끝 맞춤 + 줄 아래 옅은 회색 띠
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.detail, c.shelfBottom))).padding(top = 10.dp, bottom = 4.dp)) {
                        Row(Modifier.padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
                            row.forEach { b -> Box(Modifier.width(coverW)) { ShelfGridCell(b, h, now, modes, coverW) } }
                        }
                    }
                }
            }
        }
        ScrollTopBar(c, if (modes.selecting) "${modes.selected.size}권 선택" else g.name, pastHero)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp + barTop()), verticalAlignment = Alignment.CenterVertically) {
            if (modes.selecting || modes.reordering) DoneButton(c) { modes.selecting = false; modes.selected = emptySet(); modes.reordering = false }
            else FloatingButton(c, "뒤로", Icons.Back, onHero = !pastHero, onClick = onBack)
            Spacer(Modifier.weight(1f))
            FloatingButton(c, "보기·정렬", Icons.Dots, Modifier.trackRect(moreBtn), onHero = !pastHero) { moreOpen = true }
        }
        if (modes.selecting) SelectBar(h, g.books.filter { it.id in modes.selected }, false, null, Modifier.align(Alignment.BottomCenter).padding(bottom = barBottom())) {
            modes.selecting = false; modes.selected = emptySet()
        }
    }
    if (moreOpen) ViewSortMenu(
        moreBtn.rect, view, onSelect = { modes.selecting = true; modes.reordering = false },
        onView = { v -> h.onPrefs(h.prefs.copy(views = ShelfViews.put(h.prefs.views, key, v))) }, onDismiss = { moreOpen = false },
        extra = {
            if (view.sort == GroupSort.MANUAL) MenuRow("순서 바꾸기…", Icons.Up, sepTop = true) { moreOpen = false; modes.reordering = true; modes.selecting = false }
            if (sec == LibSection.SERIES) g.seriesKey?.let { k -> MenuRow("시리즈 관리(이름·묶기 풀기)…", Icons.Group, sepTop = true) { moreOpen = false; h.seriesManage(k) } }
        },
    )
}

/** 묶음 안 격자 칸: 표지(아래끝 맞춤) + [상태 알약] ⋯, 문서는 제목 1줄. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfGridCell(b: Book, h: ShelfHost, now: Long, modes: ShelfModes, coverW: Dp) {
    val c = h.c
    val unavailable = b.id in h.unavailable
    val st = bookState(b, unavailable, now)
    val r = remember { RectHolder() }
    val picked = b.id in modes.selected
    fun tap() { if (modes.selecting) modes.selected = if (picked) modes.selected - b.id else modes.selected + b.id else h.open(b) }
    Column {
        Box(
            Modifier.width(coverW).height(coverW * 1.48f).trackRect(r)
                .combinedClickable(onClick = ::tap, onLongClick = { if (!modes.selecting) h.bookMenu(b, r.rect, false, null) })
                .clearAndSetSemantics { contentDescription = bookLabel(b, unavailable); role = Role.Button; if (modes.selecting) selected = picked },
            contentAlignment = Alignment.BottomCenter,
        ) {
            ShelfCover(b, Modifier.fillMaxWidth().aspectRatio(coverRatio(b)), bar = true, dim = unavailable)
            if (modes.selecting) SelectMark(picked, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(st, c)
            Spacer(Modifier.weight(1f))
            if (!modes.selecting) MoreDots(c, "${b.shownTitle} 메뉴") { h.bookMenu(b, it.rect, true, null) } else Spacer(Modifier.height(48.dp))
        }
        if (isDocBook(b)) Text(b.shownTitle, fontSize = 12.sp, color = smallSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(y = (-10).dp))
    }
}

/** 묶음 머리의 펼친 표지 5장(가운데 크게, 양옆 작게 겹침 — 목업 좌표를 폭 비율로). */
@Composable
private fun HeroFan(books: List<Book>, s: Float, modifier: Modifier) {
    val pick = books.take(5)
    if (pick.isEmpty()) return
    // 목업: 가운데(큰 것)·왼쪽 둘·오른쪽 둘 — 놓는 차례는 바깥 → 가운데(가운데가 맨 위)
    val xs = listOf(18, 52, 112, 214, 262)
    val ws = listOf(96, 96, 120, 96, 96)
    val hs = listOf(128, 148, 178, 148, 128)
    val slot = when (pick.size) { 1 -> listOf(2); 2 -> listOf(2, 3); 3 -> listOf(2, 1, 3); 4 -> listOf(2, 1, 3, 0); else -> listOf(2, 1, 3, 0, 4) }
    BoxWithConstraints(modifier) {
        // 자리 높이(목업 196)에 맞춰 줄이고 가운데 놓는다(대표님 23:37: 머리를 낮추며 책도 작게)
        val k = maxHeight / 196.dp
        val left = (maxWidth - 340.dp * k) / 2 - 18.dp * k
        // 바깥 → 안쪽 → 가운데 순으로 그려 가운데가 맨 위
        val drawOrder = slot.indices.sortedBy { listOf(1, 2, 5, 2, 1)[slot[it]] }
        drawOrder.forEach { i ->
            val p = slot[i]; val b = pick[i]
            ShelfCover(
                b,
                Modifier.align(Alignment.BottomStart).offset(x = left + (xs[p] * k).dp).width((ws[p] * k).dp).height((hs[p] * k).dp),
            )
        }
    }
}

private fun mixC(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
)
