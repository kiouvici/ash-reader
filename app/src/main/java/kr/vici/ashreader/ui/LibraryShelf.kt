// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.progress
import kr.vici.ashreader.doc.Covers

// 작업 05: 서재 '형식·폴더·시리즈' — 애플 북스 방식 묶음 카드 → 눌러 들어가기(대표님 목업 통과 2026-10-24 17:08).

/** 서재 색(목업 값, 어두운 화면은 같은 관계로 맞춤). */
internal class ShelfColors(
    val pageTop: Color, val pageBottom: Color, val card: Color, val detail: Color,
    val ink: Color, val sub: Color, val sub2: Color, val shelfBottom: Color,
    val bar: Color, val barLine: Color, val sel: Color, val blue: Color,
)

@Composable
internal fun shelfColors(): ShelfColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) ShelfColors(
        pageTop = Color(0xFF121417), pageBottom = Color(0xFF1A1C20), card = Color(0xFF24272C), detail = Color(0xFF121417),
        ink = Color(0xFFE6E6E8), sub = Color(0xFFA0A4A8), sub2 = Color(0xFF8E9297), shelfBottom = Color(0xFF1C1E22),
        bar = Color(0xF02A2D32), barLine = Color(0x1FFFFFFF), sel = Color(0x24FFFFFF), blue = Color(0xFF409CFF),
    ) else ShelfColors(
        pageTop = Color.White, pageBottom = Color(0xFFF1F1F3), card = Color.White, detail = Color.White,
        ink = Color(0xFF111316), sub = Color(0xFF6C6C70), sub2 = Color(0xFF8E8E93), shelfBottom = Color(0xFFF0F0F2),
        bar = Color(0xF2FFFFFF), barLine = Color(0x0F000000), sel = Color(0x14000000), blue = Color(0xFF0A84FF),
    )
}

/** 시스템 '애니메이션 끄기'(동작 줄이기)면 화면 전환을 바로 바꾼다. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** 아래 내비게이션(서재·독서 기록·설정): 위 탭과 같은 모양, 아이콘 + 이름. */
@Composable
fun FloatingNavBar(items: List<Pair<String, ImageVector>>, selected: Int, onPick: (Int) -> Unit) {
    val c = shelfColors()
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.shadow(18.dp, RoundedCornerShape(13.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(13.dp)).background(c.bar).border(1.dp, c.barLine, RoundedCornerShape(13.dp))
                .padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Column(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.sel else Color.Transparent)
                        .selectable(on, role = Role.Tab) { onPick(i) }.padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = if (on) c.ink else c.sub, modifier = Modifier.size(20.dp))
                    Text(label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) c.ink else c.sub, maxLines = 1)
                }
            }
        }
    }
}

// ── 표지 ──

private val DOC_GROUPS = setOf(FormatGroup.PDF, FormatGroup.HWP, FormatGroup.WORD, FormatGroup.TEXT)

/** 표지 가로:세로 — 문서(PDF·한글·Word·텍스트) A4, 그 밖(이북·만화) 1 : 1.48. */
fun coverRatio(b: Book): Float = if (DocFormat.of(b.format).group in DOC_GROUPS) 1f / 1.414f else 1f / 1.48f

/** 표지 없는 이북 기본 표지 색(위 밝게 → 아래 진하게). 제목으로 하나를 고정해 고른다. */
private val PLAIN = listOf(
    0xFF1E7BD6 to 0xFF0B2D63, 0xFFB21E1E to 0xFF4A0B0B, 0xFF1F8A3A to 0xFF0B3A1A, 0xFF6B6B6F to 0xFF2A2A2D,
    0xFFB4560F to 0xFF4A1D06, 0xFF2457C6 to 0xFF0A1E4E, 0xFF7A3E9D to 0xFF2E1240, 0xFF0F6E7A to 0xFF05292E,
)

private fun plainOf(title: String): Pair<Color, Color> {
    val (a, b) = PLAIN[Math.floorMod(title.hashCode(), PLAIN.size)]
    return Color(a) to Color(b)
}

/**
 * 서재 표지(작업 05): 실제 표지(이북 속 표지, 문서·만화 첫 쪽)가 있으면 그것, 없으면 기본 표지.
 * 모든 표지: 모서리 1/3dp(책등 쪽 작게), 왼쪽 5dp 책등 접힘 빛, 아래 그림자. [fan] = 묶음 카드의 겹친 표지(바닥에 붙음, 그림자 없음).
 * [bar] = 읽는 중이면 아래 3dp 진행 막대(책장 줄).
 */
@Composable
fun ShelfCover(b: Book, modifier: Modifier = Modifier, fan: Boolean = false, bar: Boolean = false, dim: Boolean = false) {
    val f = DocFormat.of(b.format)
    val img = if (Covers.supported(f) || b.coverSource != Book.COVER_AUTO) rememberCover(b) else null
    val shape = if (fan) RoundedCornerShape(topStart = 1.dp, topEnd = 2.dp)
    else RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp, topEnd = 3.dp, bottomEnd = 3.dp)
    val shadow = if (fan) Modifier else Modifier.shadow(7.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.45f))
    BoxWithConstraints(modifier.then(shadow).clip(shape).alpha(if (dim) 0.45f else 1f)) {
        val w = maxWidth
        when {
            img != null -> Image(img, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            f.group in DOC_GROUPS -> DocFace(b, f, w)
            else -> PlainFace(b, f, w)
        }
        // 책등 접힘 빛(흰 → 어두움)
        Box(
            Modifier.fillMaxHeight().width(5.dp).background(
                Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.35f), 0.6f to Color.White.copy(alpha = 0.05f), 1f to Color.Black.copy(alpha = 0.18f)),
            ),
        )
        if (bar && b.status != Book.DONE && b.lastOpenedAt > 0 && b.pageCount > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(((b.lastPage + 1f) / b.pageCount).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFF0A84FF)))
            }
        }
        if (b.favorite && !fan) Text("★", fontSize = 13.sp, color = Ash.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
    }
}

/** 표지 폭에 비례한 글자 크기(글자 크기 설정과 상관없이 표지 안에 맞게 — 넘치면 말줄임). */
@Composable
private fun coverSp(w: Dp, ratio: Float) = with(LocalDensity.current) { (w * ratio).toSp() }

/** 기본 표지: 위 밝고 아래 진한 단색 그러데이션, 안쪽 가는 테두리, 제목 위 가운데(이북 3줄·만화 2줄), 형식 아래 가운데. */
@Composable
private fun PlainFace(b: Book, f: DocFormat, w: Dp) {
    val (top, bottom) = plainOf(b.shownTitle)
    val s = w / 98.dp
    val comic = f.group == FormatGroup.COMIC
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Box(Modifier.fillMaxSize().padding(start = 9.dp * s, top = 5.dp * s, end = 5.dp * s, bottom = 5.dp * s).border(1.dp, Color.White.copy(alpha = 0.16f)))
        val ts = coverSp(w, if (b.shownTitle.length > 6) 0.112f else 0.128f)
        Text(
            b.shownTitle, color = Color.White, fontSize = ts, lineHeight = ts * 1.25f, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            maxLines = if (comic) 2 else 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp * s, end = 9.dp * s, top = 20.dp * s),
        )
        // 지은이가 있으면 지은이(애플 북스 기본 표지), 없으면 형식
        Text(
            b.author.ifBlank { f.badge }, color = Color.White.copy(alpha = 0.75f), fontSize = coverSp(w, 0.087f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp * s, start = 10.dp * s, end = 7.dp * s),
        )
    }
}

/** 문서 기본 표지(첫 쪽을 못 얻었을 때): 흰 종이 + 형식 색 띠 + 제목(3줄) + 옅은 줄. */
@Composable
private fun DocFace(b: Book, f: DocFormat, w: Dp) {
    val s = w / 98.dp
    val band = f.badgeColor()
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.padding(start = 12.dp * s, end = 9.dp * s, top = 10.dp * s).fillMaxWidth().height(3.dp * s).background(band))
        val ts = coverSp(w, 0.08f)
        Text(
            b.shownTitle, color = Color(0xFF26282B), fontSize = ts, lineHeight = ts * 1.25f, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp * s, end = 9.dp * s, top = 19.dp * s),
        )
        Box(
            Modifier.padding(start = 12.dp * s, end = 14.dp * s, top = 50.dp * s).fillMaxWidth().height(30.dp * s).drawBehind {
                val gap = 5.dp.toPx() * s
                var y = 0f
                while (y < size.height) { drawRect(Color(0xFFDCDCE0), Offset(0f, y), Size(size.width, 1f)); y += gap }
            },
        )
    }
}

// ── 묶음 화면 ──

private const val PATH_SEP = "\u001E"
private val EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 서재 갈래(작업 06): 첫 화면 카드·'최근 ›'·검색에서 들어가는 화면. [key] 는 들어간 길의 첫 칸. */
private enum class LibSection(val key: String, val title: String) {
    ALL("all", "전체"), FORMAT("format", "형식"), FOLDER("folder", "폴더"), SERIES("series", "시리즈"), RECENT("recent", "최근"), SEARCH("search", "전체");

    companion object { fun of(k: String) = entries.firstOrNull { it.key == k } }
}

/**
 * 서재(작업 05·06): 첫 화면 = 큰 제목 '서재' + 검색 + 전체·형식·폴더·시리즈 카드(세로 2×2, 가로 한 줄) + '최근 ›' 책장.
 * 카드를 누르면 그 자리에서 커지며 들어간다(0.45초, 뒤 목록 0.94배·흐려짐). 형식·폴더·시리즈 = 묶음 카드 목록 → 묶음 화면 → 하위 폴더.
 * 뒤로 = 거꾸로 줄어듦. '동작 줄이기'면 바로 바뀐다. 들어간 길은 회전해도 남는다. [homeKey] 가 바뀌면 첫 화면으로(아래 '서재' 다시 누름).
 * [navBar] = 가로 화면에서 제목 줄 오른쪽에 둘 내비게이션(세로 공간 확보). [extras] = 첫 화면 맨 아래(서점 바로가기 줄 등).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryPane(
    books: List<Book>,
    folderRoots: List<String>,
    unavailable: Set<String>,
    onOpen: (Book) -> Unit,
    onLongBook: (Book) -> Unit,
    onSeriesManage: (String) -> Unit,
    modifier: Modifier = Modifier,
    scanning: Boolean = false,
    homeKey: Int = 0,
    navBar: (@Composable () -> Unit)? = null,
    extras: @Composable () -> Unit = {},
) {
    var pathStr by rememberSaveable { mutableStateOf("") }
    var seenHome by rememberSaveable { mutableStateOf(homeKey) }
    if (homeKey != seenHome) { seenHome = homeKey; pathStr = "" }
    val formats = remember(books) { LibGroups.formats(books) }
    val folders = remember(books, folderRoots) { LibGroups.folders(books, folderRoots) }
    val series = remember(books) { LibGroups.series(books) }
    val recent = remember(books) { books.sortedByDescending { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.addedAt } }
    fun groupsOf(s: LibSection) = when (s) { LibSection.FORMAT -> formats; LibSection.FOLDER -> folders; LibSection.SERIES -> series; else -> emptyList() }
    val path = if (pathStr.isEmpty()) emptyList() else pathStr.split(PATH_SEP)
    // 갈래·묶음이 없어졌으면(책을 지움·폴더를 뺌) 있는 데까지만
    val section = path.firstOrNull()?.let { LibSection.of(it) }
    val live = if (section == null) emptyList() else listOf(section.key) + path.drop(1).takeWhile { LibGroups.find(groupsOf(section), it) != null }
    fun go(p: List<String>) { pathStr = p.joinToString(PATH_SEP) }
    BackHandler(enabled = live.isNotEmpty()) { go(live.dropLast(1)) }
    val reduce = rememberReduceMotion()
    val c = shelfColors()
    SharedTransitionLayout(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = live,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else if (targetState.size > initialState.size) {
                    fadeIn(tween(200)) togetherWith (scaleOut(tween(450, easing = EASE), targetScale = 0.94f) + fadeOut(tween(450), targetAlpha = 0.4f))
                } else {
                    (scaleIn(tween(450, easing = EASE), initialScale = 0.94f) + fadeIn(tween(450), initialAlpha = 0.4f)) togetherWith fadeOut(tween(250))
                }.apply { targetContentZIndex = targetState.size.toFloat() }
            },
            label = "서재",
        ) { p ->
            val shared = SharedBounds(this@SharedTransitionLayout, this, reduce)
            val sec = p.firstOrNull()?.let { LibSection.of(it) }
            val back = { go(p.dropLast(1)) }
            val detail: @Composable (LibGroup, Boolean, Boolean) -> Unit = { g, filters, search ->
                GroupDetail(g, c, shared, unavailable, onOpen, onLongBook, onBack = back, onEnter = { k -> go(p + k.id) }, filters = filters, startSearch = search)
            }
            when (sec) {
                null -> LibraryHome(
                    books, formats, folders, series, recent, c, shared, scanning, navBar, extras,
                    onSection = { go(listOf(it.key)) }, onOpen = onOpen, onLong = onLongBook,
                )
                LibSection.ALL -> detail(LibGroup("sec-all", "전체", books), true, false)
                LibSection.SEARCH -> detail(LibGroup("sec-search", "전체", books), true, true)
                LibSection.RECENT -> detail(LibGroup("sec-recent", "최근", recent), false, false)
                else -> {
                    val cur = p.drop(1).lastOrNull()?.let { LibGroups.find(groupsOf(sec), it) }
                    if (cur != null) detail(cur, false, false)
                    else GroupList(
                        sec, groupsOf(sec), c, shared, onBack = back, onEnter = { go(p + it.id) },
                        onLong = if (sec == LibSection.SERIES) { g -> g.seriesKey?.let(onSeriesManage) } else null,
                    )
                }
            }
        }
    }
}

/** 카드 ↔ 들어간 화면 확대 전환(같은 열쇠의 경계를 이어 줌). '동작 줄이기'면 아무것도 하지 않는다. */
@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedBounds(val scope: SharedTransitionScope, val av: AnimatedVisibilityScope, val off: Boolean) {
    @Composable
    fun of(id: String): Modifier {
        if (off) return Modifier
        return with(scope) {
            Modifier.sharedBounds(
                rememberSharedContentState("g-$id"), av,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(220)),
                boundsTransform = BoundsTransform { _, _ -> tween(450, easing = EASE) },
                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** 첫 화면 크기: 세로(휴대폰 기준 390dp, 태블릿은 비례해 크게) / 가로 휴대폰 / 가로 태블릿(목업 LibraryHome·LibraryHomeLand). */
private class HomeSpec(
    val cols: Int, val cardH: Dp, val coverW: Dp, val heights: List<Dp>, val step: Dp, val name: Float, val title: Float,
    val bookW: Dp, val pad: Dp, val gap: Dp,
)

private fun homeSpec(w: Dp, h: Dp): HomeSpec {
    val land = w > h
    val tablet = minOf(w, h) >= 600.dp
    return when {
        land && tablet -> HomeSpec(4, 210.dp, 96.dp, listOf(128.dp, 146.dp, 136.dp), 52.dp, 22f, 34f, 152.dp, 40.dp, 20.dp)
        land -> HomeSpec(4, 96.dp, 52.dp, listOf(64.dp, 74.dp, 68.dp), 28.dp, 16f, 24f, 80.dp, 24.dp, 12.dp)
        else -> {
            val s = (w / 390.dp).coerceIn(1f, 1.6f)
            HomeSpec(2, 150.dp * s, 58.dp * s, listOf(76.dp * s, 86.dp * s), 44.dp * s, 18f, 32f, 100.dp * s, 16.dp, 12.dp)
        }
    }
}

@Composable
private fun LibraryHome(
    books: List<Book>, formats: List<LibGroup>, folders: List<LibGroup>, series: List<LibGroup>, recent: List<Book>,
    c: ShelfColors, shared: SharedBounds, scanning: Boolean, navBar: (@Composable () -> Unit)?, extras: @Composable () -> Unit,
    onSection: (LibSection) -> Unit, onOpen: (Book) -> Unit, onLong: (Book) -> Unit,
) {
    // 카드 표지: 묶음마다 앞의 책(형식·폴더·시리즈는 서로 다른 묶음에서 하나씩 — 무엇이 들었는지 보이게)
    fun spread(gs: List<LibGroup>) = gs.mapNotNull { it.books.firstOrNull() }.ifEmpty { gs.flatMap { it.books } }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.detail)) {
        val sp = homeSpec(maxWidth, maxHeight)
        val cards = listOf(
            Triple(LibSection.ALL, "${books.size}권", recent),
            Triple(LibSection.FORMAT, "${formats.size}가지", spread(formats)),
            Triple(LibSection.FOLDER, "${folders.size}개", spread(folders)),
            Triple(LibSection.SERIES, "${series.size}개", spread(series)),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Row(Modifier.padding(start = sp.pad + 4.dp, end = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("서재", fontSize = sp.title.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    navBar?.invoke()
                    FloatingButton(c, "검색", Modifier.padding(start = 8.dp)) { onSection(LibSection.SEARCH) }
                }
                if (scanning) androidx.compose.material3.LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = sp.pad).semantics { contentDescription = "폴더에서 책을 모으는 중" },
                )
            }
            item(key = "cards") {
                Column(Modifier.padding(horizontal = sp.pad).padding(top = 12.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(sp.gap)) {
                    cards.chunked(sp.cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.gap)) {
                            row.forEach { (sec, sub, fan) ->
                                HomeCard(sec.title, sub, fan, sp, c, Modifier.weight(1f).then(shared.of("sec-${sec.key}"))) { onSection(sec) }
                            }
                        }
                    }
                }
            }
            if (books.isEmpty()) item(key = "empty") {
                Text(
                    "아직 책이 없어요. 설정 › 서재 폴더에서 책이 있는 폴더를 고르거나, 다른 앱에서 파일을 'ASH Reader로 열기' 해 보세요.",
                    color = c.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = sp.pad + 4.dp),
                )
            } else item(key = "recent") {
                ShelfRow(
                    "최근", null, recent.take(30), c, shared.of("sec-recent"), { onSection(LibSection.RECENT) }, onOpen, onLong,
                    coverW = sp.bookW, sidePad = sp.pad + 4.dp, bleedPad = 0.dp, titleLabel = "최근 읽은 책 모두 보기",
                )
            }
            item(key = "extras") { extras() }
        }
    }
}

/** 첫 화면 카드: 이름(굵게) + 개수, 오른쪽 아래 표지가 바닥에 붙어 겹침. TalkBack "전체, 312권". */
@Composable
private fun HomeCard(name: String, sub: String, books: List<Book>, sp: HomeSpec, c: ShelfColors, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(sp.cardH)
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape).background(c.card)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$name, $sub"; role = Role.Button },
    ) {
        CoverFan(books, sp.heights, sp.coverW, sp.step, Modifier.align(Alignment.BottomEnd).offset(x = 10.dp))
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp)) {
            Text(name, fontSize = sp.name.sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1)
            Text(sub, fontSize = 12.5.sp, color = c.sub, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 표지 [heights].size 장이 바닥에 붙어 서서 겹친다(왼쪽 그림자). 권 수가 모자라면 오른쪽에 붙인다. */
@Composable
private fun CoverFan(books: List<Book>, heights: List<Dp>, coverW: Dp, step: Dp, modifier: Modifier) {
    val n = heights.size
    Box(modifier.size(width = step * (n - 1) + coverW, height = heights.max())) {
        val fan = books.take(n)
        fan.forEachIndexed { i, b ->
            val k = i + n - fan.size
            ShelfCover(
                b,
                Modifier.align(Alignment.BottomStart).offset(x = step * k).size(coverW, heights[k]).drawBehind {
                    val sw = 12.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)), startX = -sw, endX = 0f),
                        topLeft = Offset(-sw, 8.dp.toPx()), size = Size(sw, size.height - 8.dp.toPx()),
                    )
                },
                fan = true,
            )
        }
    }
}

/** 떠 있는 둥근 단추(뒤로·검색): 44dp 모서리 12dp 반투명, 누름 48dp. */
@Composable
private fun FloatingButton(c: ShelfColors, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).padding(2.dp)
            .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(RoundedCornerShape(12.dp)).background(c.bar).border(1.dp, c.barLine, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(if (label == "뒤로") Icons.Back else Icons.Search, contentDescription = null, tint = c.ink, modifier = Modifier.size(if (label == "뒤로") 20.dp else 18.dp)) }
}

/** 가로 화면 제목 줄의 내비게이션 막대(서재·독서 기록·설정 글자만, 위 탭 모양). */
@Composable
fun HeaderNav(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val c = shelfColors()
    Row(
        Modifier.shadow(10.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(RoundedCornerShape(11.dp)).background(c.bar).border(1.dp, c.barLine, RoundedCornerShape(11.dp)).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.heightIn(min = 42.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.sel else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onPick(i) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) c.ink else c.sub, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupList(
    sec: LibSection, groups: List<LibGroup>, c: ShelfColors, shared: SharedBounds,
    onBack: () -> Unit, onEnter: (LibGroup) -> Unit, onLong: ((LibGroup) -> Unit)?,
) {
    // 넓은 화면(태블릿)은 카드를 여러 열로(한 장 최소 340dp) — 화면 폭 가득 늘어나지 않게
    BoxWithConstraints(shared.of("sec-${sec.key}").fillMaxSize().background(Brush.verticalGradient(listOf(c.pageTop, c.pageBottom)))) {
        val cols = ((maxWidth - 32.dp) / 340.dp).toInt().coerceIn(1, 3)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "heading") {
                Text(
                    sec.title, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 66.dp, bottom = 14.dp).semantics { heading() },
                )
            }
            if (groups.isEmpty()) item(key = "empty") {
                Text(
                    if (sec == LibSection.SERIES) "같은 폴더에서 제목 끝 권·화 번호만 다른 책을 시리즈로 묶어 보여 줘요. 책을 길게 눌러 직접 묶을 수도 있어요." else "아직 책이 없어요.",
                    color = c.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(groups.chunked(cols), key = { it.first().id }) { row ->
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { g ->
                        GroupCard(g, c, Modifier.weight(1f).then(shared.of(g.id)), onClick = { onEnter(g) }, onLong = onLong?.let { l -> { l(g) } })
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        FloatingButton(c, "뒤로", Modifier.padding(start = 10.dp, top = 8.dp), onBack)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(g: LibGroup, c: ShelfColors, modifier: Modifier, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().heightIn(min = 116.dp)
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(shape).background(c.card)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .clearAndSetSemantics { contentDescription = "${g.name}, ${g.books.size}권"; role = Role.Button },
    ) {
        // 오른쪽: 이 묶음 책 표지 3장이 카드 바닥에 붙어 서서 겹친다(높이 조금씩 다르게, 오른쪽 끝은 카드 밖으로 잘림)
        CoverFan(g.books, listOf(86.dp, 96.dp, 90.dp), 70.dp, 54.dp, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp))
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.46f).padding(start = 20.dp, top = 12.dp, bottom = 12.dp)) {
            Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LibGroups.cardSub(g), fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 묶음 안 화면(애플 북스 카테고리 화면): 이어 읽기·하위 폴더 책장 줄 + 모든 책 격자, 정렬·검색. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupDetail(
    g: LibGroup, c: ShelfColors, shared: SharedBounds, unavailable: Set<String>,
    onOpen: (Book) -> Unit, onLong: (Book) -> Unit, onBack: () -> Unit, onEnter: (LibGroup) -> Unit,
    filters: Boolean = false, startSearch: Boolean = false,
) {
    var sort by rememberSaveable(g.id) { mutableStateOf(GroupSort.RECENT) }
    var query by rememberSaveable(g.id) { mutableStateOf(if (startSearch) "" else null) }
    var sortOpen by remember { mutableStateOf(false) }
    // '전체'의 상태 칸(F-71): -1 = 모두, 0~2 = 상태, FAVORITES = 즐겨찾기
    var status by rememberSaveable(g.id) { mutableStateOf(-1) }
    val all = remember(g, sort) { sort.apply(g.books) }
    val q = query?.trim().orEmpty()
    val shown = all.filter { b ->
        (status < 0 || (status == FAVORITES && b.favorite) || b.status == status) &&
            (q.isEmpty() || b.title.contains(q, ignoreCase = true) || b.shownTitle.contains(q, ignoreCase = true) || b.author.contains(q, ignoreCase = true))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (startSearch) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val reading = remember(g) { g.books.filter { it.status == Book.READING && it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt } }
    val now = remember { System.currentTimeMillis() }
    BoxWithConstraints(shared.of(g.id).fillMaxSize().background(c.detail)) {
        val cols = when {
            maxWidth < 600.dp -> 3
            maxWidth < 840.dp -> 4
            else -> (maxWidth / 180.dp).toInt().coerceIn(5, 6)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item(key = "top", span = { GridItemSpan(maxLineSpan) }) {
                // 검색(첫 화면 '검색'): 큰 제목·상태 칸 없이 찾기 칸만 — 키보드가 올라와도 결과가 바로 아래 보이게
                if (startSearch) OutlinedTextField(
                    value = query.orEmpty(), onValueChange = { query = it }, singleLine = true, placeholder = { Text("모든 책에서 제목으로 찾기") },
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp).focusRequester(focus),
                ) else Column(Modifier.padding(top = 66.dp)) {
                    Text(g.name, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(LibGroups.detailSub(g), fontSize = 14.sp, color = c.sub, modifier = Modifier.padding(top = 2.dp))
                    if (query != null) OutlinedTextField(
                        value = query!!, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(if (startSearch) "제목으로 찾기" else "이 묶음에서 제목으로 찾기") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                    )
                    if (filters) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Book.READING to "읽는 중", Book.TO_READ to "읽을 책", Book.DONE to "다 읽음", FAVORITES to "★ 즐겨찾기").forEach { (st, label) ->
                            val n = g.books.count { if (st == FAVORITES) it.favorite else it.status == st }
                            val on = status == st
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) c.sel else Color.Transparent).border(1.dp, c.barLine, RoundedCornerShape(8.dp))
                                    .selectable(on, role = Role.Checkbox) { status = if (on) -1 else st }.heightIn(min = 44.dp).padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("$label $n", fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = c.ink) }
                        }
                    }
                }
            }
            if (q.isEmpty() && status < 0) {
                if (reading.isNotEmpty()) item(key = "reading", span = { GridItemSpan(maxLineSpan) }) {
                    ShelfRow("이어 읽기", null, reading, c, Modifier, null, onOpen, onLong)
                }
                g.kids.forEach { k ->
                    item(key = "k-${k.id}", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfRow(k.name, "${k.books.size}권", sort.apply(k.books), c, shared.of(k.id), { onEnter(k) }, onOpen, onLong)
                    }
                }
            }
            item(key = "all", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(if (q.isEmpty()) "모든 책" else "찾은 책", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
                    Text("${shown.size}권", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.sub2, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
            }
            items(shown.size, key = { shown[it].id }) { i ->
                val b = shown[i]
                GridBook(b, c, b.id in unavailable, now, onOpen, onLong)
            }
        }
        // 위: 왼쪽 뒤로, 오른쪽 [검색 | 정렬]
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingButton(c, "뒤로", onClick = onBack)
            Row(
                Modifier.shadow(10.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.16f))
                    .clip(RoundedCornerShape(11.dp)).background(c.bar).border(1.dp, c.barLine, RoundedCornerShape(11.dp)).padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(if (query != null) c.sel else Color.Transparent)
                        .clickable(role = Role.Button) { query = if (query == null) "" else null }.semantics { contentDescription = "검색" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Search, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp)) }
                Box {
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { sortOpen = true }
                            .semantics { contentDescription = "정렬: ${sort.label}" }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(sort.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1) }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        GroupSort.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.label, fontWeight = if (o == sort) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { sort = o; sortOpen = false }, modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 격자 칸 밖으로 [d] 만큼 좌우로 넓힌다(책장 줄 띠를 화면 끝까지). */
private fun Modifier.bleed(d: Dp) = layout { m, cons ->
    val extra = d.roundToPx() * 2
    val p = m.measure(cons.copy(minWidth = cons.maxWidth + extra, maxWidth = cons.maxWidth + extra))
    layout(cons.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** 책장 줄: 흰색 → 옅은 회색 띠, 줄 제목(하위 폴더면 '›' 를 눌러 그 안으로), 표지를 옆으로 넘김. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(
    title: String, count: String?, books: List<Book>, c: ShelfColors, modifier: Modifier, onTitle: (() -> Unit)?,
    onOpen: (Book) -> Unit, onLong: (Book) -> Unit,
    coverW: Dp = 98.dp, sidePad: Dp = 20.dp, bleedPad: Dp = 20.dp, titleLabel: String? = null,
) {
    Column(modifier.bleed(bleedPad).background(Brush.verticalGradient(listOf(c.detail, c.shelfBottom))).padding(top = 14.dp, bottom = 22.dp)) {
        Row(
            Modifier.padding(start = sidePad).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .then(if (onTitle != null) Modifier.clickable(role = Role.Button, onClick = onTitle).clearAndSetSemantics {
                    contentDescription = titleLabel ?: "$title, $count, 폴더 열기"; role = Role.Button
                } else Modifier.semantics { contentDescription = title })
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) Text(count, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.sub2, modifier = Modifier.padding(start = 6.dp))
            if (onTitle != null) Icon(Icons.Chevron, contentDescription = null, tint = c.sub2, modifier = Modifier.padding(start = 2.dp).size(16.dp))
        }
        LazyRow(
            Modifier.padding(top = 8.dp).height(coverW * 1.48f + 10.dp),
            contentPadding = PaddingValues(horizontal = sidePad, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(books.take(60), key = { it.id }) { b ->
                ShelfCover(
                    b,
                    Modifier.width(coverW).aspectRatio(coverRatio(b))
                        .combinedClickable(onClick = { onOpen(b) }, onLongClick = { onLong(b) })
                        .semantics { contentDescription = bookLabel(b, false) },
                    bar = true,
                )
            }
        }
    }
}

/** 모든 책 격자 칸: 표지(아래끝 맞춤) → 제목 1줄 굵게 → 형식 회색 → 상태 칸. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(b: Book, c: ShelfColors, unavailable: Boolean, now: Long, onOpen: (Book) -> Unit, onLong: (Book) -> Unit) {
    val st = bookState(b, unavailable, now)
    // TalkBack·시험: "제목, 형식, 42% 읽음"(옛 서재 칸과 같은 모양)
    Column(Modifier.combinedClickable(onClick = { onOpen(b) }, onLongClick = { onLong(b) }).clearAndSetSemantics { contentDescription = bookLabel(b, unavailable) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.48f), contentAlignment = Alignment.BottomCenter) {
            ShelfCover(b, Modifier.fillMaxWidth().aspectRatio(coverRatio(b)), dim = unavailable)
        }
        Text(b.shownTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        // 지은이 · 형식(작업 06), 자동으로 못 맞춘 책은 '확인 필요'
        Text(
            listOfNotNull(b.author.ifBlank { null }, DocFormat.of(b.format).badge, if (b.needsReview) "확인 필요" else null).joinToString(" · "),
            fontSize = 11.5.sp, color = c.sub2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
        )
        val col = if (st.reading) c.blue else c.ink.copy(alpha = 0.8f)
        Box(
            Modifier.padding(top = 5.dp).border(1.5.dp, col, RoundedCornerShape(6.dp))
                .background(if (st.reading) c.blue.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(6.dp))
                .heightIn(min = 20.dp).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) { Text(st.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = col, maxLines = 1) }
        Spacer(Modifier.height(2.dp))
    }
}

/** 책 한 권의 TalkBack·시험 이름: "제목, 형식, 42% 읽음" / "…, 열 수 없음". */
private fun bookLabel(b: Book, unavailable: Boolean) =
    "${b.shownTitle}, ${DocFormat.of(b.format).badge}, " + if (unavailable) "열 수 없음" else "${(b.progress() * 100).toInt()}% 읽음"

/** 상태 칸 '즐겨찾기' 값(F-71). */
private const val FAVORITES = 3
