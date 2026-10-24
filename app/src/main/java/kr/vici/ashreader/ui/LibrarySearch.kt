// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.ShelfCollection

/**
 * 서재 검색(작업 07 LibraryMenus ④): 찾기 칸 + '제목 N권'(가로 표지 줄) + 이름이 맞는 폴더·형식·시리즈·모음.
 * 책 본문을 미리 색인해 둔 것이 없어 본문 찾기는 하지 않는다(지시: 색인이 없으면 제목만). 지은이도 함께 찾는다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySearch(
    h: ShelfHost, books: List<Book>, formats: List<LibGroup>, folders: List<LibGroup>, series: List<LibGroup>,
    user: List<Pair<ShelfCollection, List<Book>>>, onBack: () -> Unit,
) {
    val c = h.c
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim()
    val hits = remember(q, books) {
        if (q.isEmpty()) emptyList() else books.filter { it.shownTitle.contains(q, true) || it.title.contains(q, true) || it.author.contains(q, true) }
    }
    // 이름이 맞는 묶음(폴더는 하위 폴더까지)
    val groups = remember(q, formats, folders, series, user) {
        if (q.isEmpty()) emptyList() else {
            fun all(l: List<LibGroup>): List<LibGroup> = l.flatMap { listOf(it) + all(it.kids) }
            all(folders).filter { it.name.contains(q, true) }.map { Triple("폴더", it, listOf(ShelfColls.FOLDER) + folderPath(folders, it.id)) } +
                formats.filter { it.name.contains(q, true) }.map { Triple("형식", it, listOf(ShelfColls.FORMAT, it.id)) } +
                series.filter { it.name.contains(q, true) }.map { Triple("시리즈", it, listOf(ShelfColls.SERIES, it.id)) } +
                user.filter { it.first.name.contains(q, true) }.map { (cc, l) -> Triple("모음", LibGroup("c-" + cc.id, cc.name, l), listOf(ShelfColls.userKey(cc.id))) }
        }
    }
    Box(h.shared.of("sec-search").fillMaxSize().background(c.detail)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = barTop(), bottom = 36.dp + barBottom())) {
            item(key = "field") {
                Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FloatingButton(c, "뒤로", Icons.Back, onClick = onBack)
                    // 대표님 2026-10-25 00:12: 뒤로 단추와 같은 둥근 모서리(12)·높이(44), 글씨는 작고 안내는 흐리게
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.weight(1f).padding(start = 6.dp).height(44.dp).clip(shape).background(c.btn).border(1.dp, c.barLine, shape)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Search, null, tint = smallSub, modifier = Modifier.size(16.dp))
                        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("제목·지은이·폴더 이름으로 찾기", fontSize = 13.sp, color = smallSub.copy(alpha = 0.7f), maxLines = 1)
                            androidx.compose.foundation.text.BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = c.ink),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "서재 검색" },
                            )
                        }
                        if (query.isNotEmpty()) Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { query = "" }.semantics { contentDescription = "지우기" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Close, null, tint = smallSub, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
            if (q.isNotEmpty()) {
                item(key = "titles") {
                    Column(Modifier.fillMaxWidth().background(if (c.dark) c.shelfBottom else Color0F2).padding(top = 16.dp, bottom = 18.dp)) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                            Text("제목", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink, modifier = Modifier.semantics { heading() })
                            Text("${hits.size}권", fontSize = 14.sp, color = c.sub, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                        }
                        if (hits.isEmpty()) Text("제목이나 지은이가 맞는 책이 없어요.", fontSize = 14.sp, color = c.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                        else LazyRow(
                            Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom,
                        ) {
                            items(hits.take(60), key = { it.id }) { b ->
                                val r = remember { RectHolder() }
                                Column(
                                    Modifier.width(86.dp).trackRect(r)
                                        .combinedClickable(onClick = { h.open(b) }, onLongClick = { h.bookMenu(b, r.rect, false, null) })
                                        .clearAndSetSemantics { contentDescription = bookLabel(b, b.id in h.unavailable); role = Role.Button },
                                ) {
                                    Box(Modifier.width(86.dp).height(86.dp * 1.48f), contentAlignment = Alignment.BottomCenter) {
                                        ShelfCover(b, Modifier.width(86.dp).aspectRatio(coverRatio(b)), bar = true)
                                    }
                                    Text(b.shownTitle, fontSize = 12.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    item(key = "gh") {
                        Text("폴더·형식·시리즈·모음", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp).semantics { heading() })
                    }
                    items(groups, key = { it.third.joinToString("/") }) { (kind, g, path) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { h.go(path) }
                                .clearAndSetSemantics { contentDescription = "$kind ${g.name}, ${g.books.size}권"; role = Role.Button }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (kind) { "폴더" -> Icons.FolderOpen; "형식" -> Icons.FileDoc; "시리즈" -> Icons.Shelf; else -> Icons.Books },
                                null, tint = c.ink, modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(g.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$kind · ${g.books.size}권", fontSize = 12.sp, color = smallSub)
                            }
                            Icon(Icons.Chevron, null, tint = c.sub, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            item(key = "note") {
                Text("제목·지은이와 함께 폴더 · 형식 · 시리즈 · 모음 이름도 찾아요.", fontSize = 12.sp, color = smallSub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

private val Color0F2 = androidx.compose.ui.graphics.Color(0xFFF2F2F4)

/** 폴더 묶음 [id] 까지의 길(맨 위 서재 폴더 → … → 그 폴더). */
private fun folderPath(roots: List<LibGroup>, id: String): List<String> {
    fun walk(l: List<LibGroup>, acc: List<String>): List<String>? {
        for (g in l) {
            val p = acc + g.id
            if (g.id == id) return p
            walk(g.kids, p)?.let { return it }
        }
        return null
    }
    return walk(roots, emptyList()).orEmpty()
}
