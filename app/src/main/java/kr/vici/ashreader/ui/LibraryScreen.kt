// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.StoreApp
import kr.vici.ashreader.doc.Covers

/**
 * S-10 서재 홈(작업 06 B안): 큰 제목 '서재' + 검색 + 전체·형식·폴더·시리즈 카드 + '최근 ›' 책장 → 들어가기([LibraryPane]).
 * 책을 길게 누르면 문서 메뉴. 맨 아래 '내 서점 앱' 줄(F-36, 설정에서 끌 수 있음).
 */
@Composable
fun LibraryScreen(
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onAdd: () -> Unit,
    onSeries: (String) -> Unit,
    onRemove: (Book) -> Unit,
    modifier: Modifier = Modifier,
    stores: List<StoreApp> = emptyList(),
    scanning: Boolean = false,
    onStoreEdit: () -> Unit = {},
    onExport: (Book) -> Unit = {},
    /** F-36 '내 서점 앱' 줄 보이기(설정에서 끌 수 있음). */
    showStores: Boolean = true,
    /** 찾을 수 없는 서재 폴더 안 책 id(S-17) — '열 수 없음'으로 보인다(지우지 않음). */
    unavailable: Set<String> = emptySet(),
    /** 서재 폴더 이름들(폴더 묶음의 맨 위, 작업 05). */
    folderRoots: List<String> = emptyList(),
    /** 바뀌면 첫 화면으로(아래 '서재'를 다시 누름). */
    homeKey: Int = 0,
    /** 가로 화면: 제목 줄 오른쪽 내비게이션(작업 06). */
    navBar: (@Composable () -> Unit)? = null,
    /** 책 정보(작업 06): 문서 메뉴 → '책 정보·표지 바꾸기'. */
    onBookInfo: ((Book) -> Unit)? = null,
) {
    var menuFor by remember { mutableStateOf<Book?>(null) }
    LibraryPane(
        books = books, folderRoots = folderRoots, unavailable = unavailable,
        onOpen = onOpen, onLongBook = { menuFor = it },
        // 시리즈 카드를 길게 누르면 묶음 관리(이름 바꾸기·묶기 풀기·권 더하기, F-62)
        onSeriesManage = onSeries,
        modifier = modifier, scanning = scanning, homeKey = homeKey, navBar = navBar,
        extras = { if (showStores) Box(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp)) { StoreRow(stores, onEdit = onStoreEdit, onAddBook = onAdd) } },
    )

    menuFor?.let { b ->
        BookMenuSheet(
            b, onDismiss = { menuFor = null },
            onOpen = { menuFor = null; onOpen(b) },
            onRemove = { menuFor = null; onRemove(b) },
            onExport = { menuFor = null; onExport(b) },
            onInfo = onBookInfo?.let { f -> { menuFor = null; f(b) } },
        )
    }
}

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

/** 책 표지 썸네일(백그라운드에서 뽑아 캐시). 없거나 아직이면 null. */
@Composable
fun rememberCover(b: Book): ImageBitmap? {
    val context = LocalContext.current
    // 작업 06: 고른 표지가 바뀌면(Covers.rev) 다시 읽는다
    val rev by Covers.rev.collectAsState()
    val img by produceState(Covers.peek(b)?.asImageBitmap(), b.id, b.path, b.coverSource, rev) {
        value = Covers.load(context, b)?.asImageBitmap()
    }
    return img
}

@Composable
fun Cover(b: Book, modifier: Modifier = Modifier) {
    val f = DocFormat.of(b.format)
    val img = if (Covers.supported(f) || b.coverSource != Book.COVER_AUTO) rememberCover(b) else null
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.aspectRatio(3f / 4.2f).clip(RoundedCornerShape(6.dp)).background(f.coverColor())) {
        // 7G: 작은 표지(문서 메뉴 48dp 등)에서는 형식 표시를 작게 — 'DO / C' 처럼 두 줄로 꺾이지 않게
        val small = maxWidth < 80.dp
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                b.title, fontFamily = Myeongjo, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                color = if (f.group == kr.vici.ashreader.data.FormatGroup.COMIC) Color.White else Ash.Text,
                modifier = Modifier.padding(8.dp),
            )
        }
        Badge(f, Modifier.align(Alignment.BottomStart).padding(if (small) 3.dp else 6.dp), small = small)
        if (b.favorite) Text("★", fontSize = 16.sp, color = Ash.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).semantics { contentDescription = "즐겨찾기" })
    }
}

@Composable
fun Badge(f: DocFormat, modifier: Modifier = Modifier, small: Boolean = false) {
    // 7G: 늘 한 줄(꺾지 않음). 자리가 모자라면 잘리되 줄이 늘지 않는다
    Box(modifier.clip(RoundedCornerShape(if (small) 3.dp else 4.dp)).background(f.badgeColor()).padding(horizontal = if (small) 3.dp else 6.dp, vertical = if (small) 1.dp else 2.dp)) {
        Text(
            f.badge, color = Color.White, fontSize = if (small) 9.sp else 12.sp, lineHeight = if (small) 11.sp else 16.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
    }
}
