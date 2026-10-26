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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.launch
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.CollectionBook
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.ShelfCollection
import kr.vici.ashreader.data.progress
import kr.vici.ashreader.doc.Covers

// 작업 05: 서재 '형식·폴더·시리즈' — 애플 북스 방식 묶음 카드 → 눌러 들어가기(대표님 목업 통과 2026-10-24 17:08).
// 작업 07: 전체 격자·≡ 모음·⋯ 보기·정렬·목록 보기·서재 검색·묶음 머리·책 ⋯ 메뉴·어둡게(목업 6개 통과 21:44).

/** 서재 색(목업 값). 어둡게 = 애플 북스 다크(바탕 #000, 띠 #161618, 카드 #2C2C2E·#1C1C1E, 보조 #98989F). */
internal class ShelfColors(
    val pageTop: Color, val pageBottom: Color, val card: Color, val detail: Color,
    val ink: Color, val sub: Color, val sub2: Color, val shelfBottom: Color,
    val bar: Color, val barLine: Color, val sel: Color, val blue: Color,
    val pillBg: Color, val pillFg: Color, val btn: Color, val dark: Boolean,
)

@Composable
internal fun shelfColors(): ShelfColors {
    val dark = LocalAshDark.current || MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) ShelfColors(
        pageTop = Color.Black, pageBottom = Color(0xFF121214), card = Color(0xFF2C2C2E), detail = Color.Black,
        ink = Color.White, sub = Color(0xFF98989F), sub2 = Color(0xFF98989F), shelfBottom = Color(0xFF161618),
        // bar: 아래 내비게이션 막대 바탕 — 메뉴와 같은 투명도(GLASS_ALPHA 83%, 대표님 00:24·00:25), 지나가는 책이 비쳐 보이게
        bar = Color(0xFF1C1C1E).copy(alpha = GLASS_ALPHA), barLine = Color(0x14FFFFFF), sel = Color(0xFF3A3A3C), blue = Color(0xFF409CFF),
        pillBg = Color(0xFF0A3D7A), pillFg = Color(0xFF9FD0FF), btn = Color(0xFF1C1C1E), dark = true,
    ) else ShelfColors(
        pageTop = Color.White, pageBottom = Color(0xFFF1F1F3), card = Color.White, detail = Color.White,
        ink = Color(0xFF111316), sub = Color(0xFF6C6C70), sub2 = Color(0xFF6C6C70), shelfBottom = Color(0xFFF0F0F2),
        // sel: 고른 단추 바탕 — 대표님 2026-10-25 00:21 '조금 더 옅은 회색'(8% → 5%)
        bar = Color.White.copy(alpha = GLASS_ALPHA), barLine = Color(0x0F000000), sel = Color(0x0D000000), blue = Color(0xFF0A84FF),
        pillBg = Color(0xFF1E5FB4), pillFg = Color.White, btn = Color(0xF2FFFFFF), dark = false,
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

/**
 * 아래 내비게이션(서재·독서 기록·설정 | 모음·검색): 위 탭과 같은 모양, 아이콘 + 이름.
 * [actions] = 탭 사이에 붙는 단추(모음·검색, 대표님 2026-10-25 00:27) — 누르면 [onAction](번호, 단추 자리).
 * [actionsAt] = 단추가 들어갈 자리(탭 번호 앞). 대표님 2026-10-26: 설정을 맨 끝으로 → 서재·독서 기록 | 모음·검색 | 설정.
 * 단추가 빠지고 붙을 때 애플처럼 막대가 양쪽에서 줄고 늘며 단추는 흐려지며 사라진다.
 */
@Composable
fun FloatingNavBar(
    items: List<Pair<String, ImageVector>>, selected: Int, onPick: (Int) -> Unit,
    actions: List<Pair<String, ImageVector>> = emptyList(), onAction: (Int, Rect) -> Unit = { _, _ -> },
    actionsAt: Int = items.size,
) {
    val c = shelfColors()
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            // 서재 밖 탭에서는 모음·검색이 빠져 막대가 줄어든다 — 가운데 맞춤이라 양쪽에서 함께 준다(아래 AnimatedVisibility)
            Modifier
                .shadow(18.dp, RoundedCornerShape(13.dp), ambientColor = Color.Black.copy(alpha = if (c.dark) 0.5f else 0.08f), spotColor = Color.Black.copy(alpha = if (c.dark) 0.6f else 0.2f))
                // 흐리지 않고 83% 투명 — 지나가는 책이 그대로 비쳐 보이게(대표님 2026-10-25 00:24, 2026-10-26 다시 확인)
                .clip(RoundedCornerShape(13.dp)).background(c.bar).border(1.dp, c.barLine, RoundedCornerShape(13.dp))
                .padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            @Composable
            fun tab(i: Int) {
                val (label, icon) = items[i]
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
            val at = actionsAt.coerceIn(0, items.size)
            for (i in 0 until at) tab(i)
            // 사라지는 동안에도 단추를 그리도록 마지막 단추 목록을 기억한다
            var shown by remember { mutableStateOf(actions) }
            if (actions.isNotEmpty()) shown = actions
            androidx.compose.animation.AnimatedVisibility(
                visible = actions.isNotEmpty(),
                enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.CenterHorizontally) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.CenterHorizontally) + androidx.compose.animation.fadeOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (at > 0) Box(Modifier.padding(vertical = 10.dp).width(1.dp).height(28.dp).background(c.barLine))
                    shown.forEachIndexed { i, (label, icon) ->
                        val r = remember { RectHolder() }
                        Column(
                            Modifier.heightIn(min = 48.dp).trackRect(r).clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { onAction(i, r.rect) }.padding(horizontal = 14.dp, vertical = 6.dp)
                                .clearAndSetSemantics { contentDescription = label; role = Role.Button },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = c.sub, modifier = Modifier.size(20.dp))
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.sub, maxLines = 1)
                        }
                    }
                    if (at < items.size) Box(Modifier.padding(vertical = 10.dp).width(1.dp).height(28.dp).background(c.barLine))
                }
            }
            for (i in at until items.size) tab(i)
        }
    }
}

// ── 표지 ──

private val DOC_GROUPS = setOf(FormatGroup.PDF, FormatGroup.HWP, FormatGroup.WORD, FormatGroup.TEXT)

/** 문서(PDF·한글·Word·텍스트)인가 — 격자에서 표지 아래 제목 1줄(작업 07 A2). */
fun isDocBook(b: Book): Boolean = DocFormat.of(b.format).group in DOC_GROUPS

/** 표지 가로:세로 — 문서(PDF·한글·Word·텍스트) A4, 그 밖(이북·만화) 1 : 1.48. */
fun coverRatio(b: Book): Float = if (isDocBook(b)) 1f / 1.414f else 1f / 1.48f

/** 표지 없는 이북 기본 표지 색(위 밝게 → 아래 진하게). 제목으로 하나를 고정해 고른다. */
private val PLAIN = listOf(
    0xFF1E7BD6 to 0xFF0B2D63, 0xFFB21E1E to 0xFF4A0B0B, 0xFF1F8A3A to 0xFF0B3A1A, 0xFF6B6B6F to 0xFF2A2A2D,
    0xFFB4560F to 0xFF4A1D06, 0xFF2457C6 to 0xFF0A1E4E, 0xFF7A3E9D to 0xFF2E1240, 0xFF0F6E7A to 0xFF05292E,
)

private fun plainOf(title: String): Pair<Color, Color> {
    val (a, b) = PLAIN[Math.floorMod(title.hashCode(), PLAIN.size)]
    return Color(a) to Color(b)
}

/** 표지 대표색 저장(책 id → 색). 비트맵을 한 점으로 줄여 평균을 낸다. */
private val toneCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

private fun toneOf(id: String, img: ImageBitmap): Color = Color(toneCache.getOrPut(id) {
    runCatching { android.graphics.Bitmap.createScaledBitmap(img.asAndroidBitmap(), 1, 1, true).getPixel(0, 0) }.getOrDefault(0xFF6B6B6F.toInt())
})

/**
 * 표지 대표색(작업 07: 어둡게 표지 은은한 빛·묶음 머리 색). 실제 표지 → 평균색, 기본 이북 표지 → 그 색, 문서(흰 종이) → null.
 */
@Composable
fun coverTone(b: Book): Color? {
    val f = DocFormat.of(b.format)
    val img = if (Covers.supported(f) || b.coverSource != Book.COVER_AUTO) rememberCover(b) else null
    return when {
        img != null -> toneOf(b.id + b.coverSource, img)
        f.group in DOC_GROUPS -> null
        f.group == FormatGroup.COMIC -> Color(0xFF3B3B48)
        else -> plainOf(b.shownTitle).first
    }
}

/**
 * 서재 표지(작업 05·07): 실제 표지(이북 속 표지, 문서·만화 첫 쪽)가 있으면 그것, 없으면 기본 표지.
 * 모든 표지: 모서리 1/3dp(책등 쪽 작게), 왼쪽 5dp 책등 접힘 빛, 아래 그림자 — 그림자는 표지 색으로 은은하게(어둡게에서 빛).
 * [fan] = 묶음 카드의 겹친 표지(바닥에 붙음, 그림자 없음). [bar] = 읽는 중이면 아래 3dp 진행 막대.
 */
@Composable
fun ShelfCover(b: Book, modifier: Modifier = Modifier, fan: Boolean = false, bar: Boolean = false, dim: Boolean = false) {
    val f = DocFormat.of(b.format)
    val img = if (Covers.supported(f) || b.coverSource != Book.COVER_AUTO) rememberCover(b) else null
    val shape = if (fan) RoundedCornerShape(topStart = 1.dp, topEnd = 2.dp)
    else RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp, topEnd = 3.dp, bottomEnd = 3.dp)
    val dark = LocalAshDark.current
    val tone = if (fan || !dark) null else coverTone(b)
    val shadow = if (fan) Modifier else Modifier.bookShadow(shape, dark, tone)
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
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(((b.lastPage + 1f) / b.pageCount).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFF0A84FF)))
            }
        }
        if (b.favorite && !fan) Text("★", fontSize = 13.sp, color = Ash.Gold, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
    }
}

/**
 * 그림자(대표님 2026-10-24 22:50 — 애플 북스 기준).
 * 밝게: 책은 오른쪽·아래로 넓고 자연스럽게 퍼지는 옅은 그림자(가까운 곳만 조금 진하게).
 * 어둡게: 표지 색으로 은은한 빛(DarkMode 목업), 색을 모르면 옅은 흰 빛.
 */
internal fun Modifier.bookShadow(shape: androidx.compose.ui.graphics.Shape, dark: Boolean, tone: Color? = null): Modifier =
    if (dark) dropShadow(shape, Shadow(radius = 18.dp, color = tone ?: Color.White, offset = DpOffset(0.dp, 8.dp), alpha = if (tone != null) 0.45f else 0.12f))
    else this
        .dropShadow(shape, Shadow(radius = 22.dp, color = Color.Black, spread = (-2).dp, offset = DpOffset(6.dp, 9.dp), alpha = 0.17f))
        // 대표님 23:46: 넓은 그러데이션보다 아래·오른쪽 가장자리를 선처럼 더 진하게
        .dropShadow(shape, Shadow(radius = 0.75.dp, color = Color.Black, offset = DpOffset(1.dp, 1.dp), alpha = 0.30f))

/**
 * 묶음 카드(서재 첫 화면·형식·폴더·시리즈 카드) 그림자(대표님 22:50·23:46): 빛이 오른쪽 위에서 비친다 →
 * 왼쪽 아래로 넓게 + 왼쪽·아래 가장자리 2px 정도 더 진하게, 위·오른쪽은 아주 옅게. 어둡게 = 없음(카드 색으로 구분).
 */
internal fun Modifier.cardShadow(shape: androidx.compose.ui.graphics.Shape, dark: Boolean): Modifier =
    if (dark) this
    else this
        // 대표님 2026-10-25 00:08 '회색이 퍼진 느낌, 떠 있지 않다' → 애플식 여러 겹: 멀리 아주 옅게 + 가까이 옅게 + 닿은 곳만 또렷하게
        .dropShadow(shape, Shadow(radius = 32.dp, color = Color.Black, spread = (-6).dp, offset = DpOffset((-6).dp, 16.dp), alpha = 0.10f))
        .dropShadow(shape, Shadow(radius = 10.dp, color = Color.Black, spread = (-2).dp, offset = DpOffset((-2).dp, 5.dp), alpha = 0.10f))
        .dropShadow(shape, Shadow(radius = 1.5.dp, color = Color.Black, offset = DpOffset((-0.5).dp, 1.dp), alpha = 0.16f))
        // 흰 바탕 위에서도 카드 가장자리가 또렷하게 — 아주 가는 테두리
        .border(0.5.dp, Color.Black.copy(alpha = 0.06f), shape)

/** 묶음 카드 모서리(대표님 23:46: 더 각지게). */
internal val CARD_SHAPE = RoundedCornerShape(5.dp)

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

// ── 서재 갈래 ──

internal const val PATH_SEP = "\u001E"
internal val EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 서재 갈래(작업 06·07): 첫 화면 카드·≡ 모음·'최근 ›'·검색에서 들어가는 화면. [key] 는 들어간 길의 첫 칸. */
internal enum class LibSection(val key: String, val title: String) {
    ALL(ShelfColls.ALL, "전체"), FORMAT(ShelfColls.FORMAT, "형식"), FOLDER(ShelfColls.FOLDER, "폴더"), SERIES(ShelfColls.SERIES, "시리즈"),
    READING(ShelfColls.READING, "읽는 중"), DONE(ShelfColls.DONE, "다 읽음"), RECENT_FILES(ShelfColls.RECENT_FILES, "최근 받은 파일"),
    HIDDEN(ShelfColls.HIDDEN, "숨긴 책"), RECENT("recent", "최근"), SEARCH("search", "검색");

    companion object { fun of(k: String) = entries.firstOrNull { it.key == k } }
}

/** 서재 설정 묶음(작업 07): ≡ 모음 순서·숨김, 모음마다 보기·정렬, 처음 보기(격자/목록). */
data class LibPrefs(val colls: String = "", val views: String = "", val defaultList: Boolean = false)

/** 책 하나에 대한 화면 밖 일(책 정보·서재에서 빼기·형광펜 내보내기). */
class BookActions(val onInfo: (Book) -> Unit, val onRemove: (Book) -> Unit, val onExport: (Book) -> Unit)

/** 묶음 화면들이 함께 쓰는 서재 상태·동작. */
internal class ShelfHost(
    val c: ShelfColors,
    val shared: SharedBounds,
    val unavailable: Set<String>,
    val prefs: LibPrefs,
    val onPrefs: (LibPrefs) -> Unit,
    val open: (Book) -> Unit,
    val bookMenu: (Book, Rect, Boolean, String?) -> Unit,
    val collMenu: (Rect) -> Unit,
    val go: (List<String>) -> Unit,
    val pickCollection: (List<String>) -> Unit,
    val removeBooks: (List<Book>) -> Unit,
    val seriesManage: (String) -> Unit,
) {
    fun view(key: String) = ShelfViews.of(prefs.views, key, prefs.defaultList)
    fun setView(key: String, v: ShelfView) = onPrefs(prefs.copy(views = ShelfViews.put(prefs.views, key, v)))
}

/**
 * 서재(작업 05·06·07): 첫 화면 = 큰 제목 '서재' + ≡ 모음·검색 + 전체·형식·폴더·시리즈 카드 + '최근 ›' 책장.
 * 카드·모음을 누르면 그 자리에서 커지며 들어간다(0.45초). 뒤로 = 거꾸로 줄어듦. '동작 줄이기'면 바로 바뀐다.
 * [homeKey] 가 바뀌면 첫 화면으로. [navBar] = 가로 화면 제목 줄의 내비게이션. [extras] = 첫 화면 맨 아래(서점 바로가기 줄 등).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryPane(
    books: List<Book>,
    folderRoots: List<String>,
    unavailable: Set<String>,
    onOpen: (Book) -> Unit,
    onSeriesManage: (String) -> Unit,
    actions: BookActions,
    prefs: LibPrefs,
    onPrefs: (LibPrefs) -> Unit,
    modifier: Modifier = Modifier,
    scanning: Boolean = false,
    homeKey: Int = 0,
    navBar: (@Composable () -> Unit)? = null,
    extras: @Composable () -> Unit = {},
    seriesAuto: Boolean = true,
    /** 아래 내비게이션 '모음'을 누른 자리(열 때 한 번) — 열고 나면 [onCollShown]. */
    collAt: Rect? = null,
    onCollShown: () -> Unit = {},
    /** 아래 내비게이션 '검색'을 누를 때마다 1씩 오른다. */
    searchKey: Int = 0,
) {
    val context = LocalContext.current
    val app = context.ash
    val dao = app.db.books()
    val hidden by remember { dao.hiddenByUser() }.collectAsState(initial = emptyList())
    val colls by remember { dao.collections() }.collectAsState(initial = emptyList())
    val members by remember { dao.collectionBooks() }.collectAsState(initial = emptyList())
    var pathStr by rememberSaveable { mutableStateOf("") }
    var seenHome by rememberSaveable { mutableStateOf(homeKey) }
    if (homeKey != seenHome) { seenHome = homeKey; pathStr = "" }
    val formats = remember(books) { LibGroups.formats(books) }
    val folders = remember(books, folderRoots) { LibGroups.folders(books, folderRoots) }
    val series = remember(books, seriesAuto) { LibGroups.series(books) }
    val recent = remember(books) { books.sortedByDescending { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.addedAt } }
    val now = remember(books) { System.currentTimeMillis() }
    val byId = remember(books) { books.associateBy { it.id } }
    val userGroups = remember(colls, members, byId) {
        val m = members.groupBy { it.collectionId }
        colls.map { c -> c to m[c.id].orEmpty().sortedBy { it.ord }.mapNotNull { byId[it.bookId] } }
    }
    fun groupsOf(s: LibSection) = when (s) { LibSection.FORMAT -> formats; LibSection.FOLDER -> folders; LibSection.SERIES -> series; else -> emptyList() }
    val path = if (pathStr.isEmpty()) emptyList() else pathStr.split(PATH_SEP)
    // 갈래·묶음이 없어졌으면(책을 지움·폴더를 뺌·모음을 지움) 있는 데까지만
    val first = path.firstOrNull()
    val section = first?.let { LibSection.of(it) }
    val userColl = first?.takeIf { ShelfColls.isUser(it) }?.let { k -> userGroups.firstOrNull { ShelfColls.userKey(it.first.id) == k } }
    val live = when {
        first == null -> emptyList()
        section != null -> listOf(section.key) + path.drop(1).takeWhile { LibGroups.find(groupsOf(section), it) != null }
        userColl != null -> listOf(first)
        else -> emptyList()
    }
    fun go(p: List<String>) { pathStr = p.joinToString(PATH_SEP) }
    BackHandler(enabled = live.isNotEmpty()) { go(live.dropLast(1)) }
    val reduce = rememberReduceMotion()
    val c = shelfColors()
    val ui = remember { ShelfUi() }

    // 떠 있는 메뉴·창
    var bookMenu by remember { mutableStateOf<BookMenuAt?>(null) }
    var collMenu by remember { mutableStateOf<Rect?>(null) }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var nameFor by remember { mutableStateOf<List<String>?>(null) }
    // 아래 내비게이션의 모음·검색(대표님 2026-10-25 00:27)
    androidx.compose.runtime.LaunchedEffect(collAt) { collAt?.let { collMenu = it; onCollShown() } }
    var seenSearch by rememberSaveable { mutableStateOf(searchKey) }
    androidx.compose.runtime.LaunchedEffect(searchKey) { if (searchKey != seenSearch) { seenSearch = searchKey; go(listOf(LibSection.SEARCH.key)) } }

    // ≡ 모음 목록(기본 8개 + 사용자 모음) — 순서·숨김은 설정
    val arrangement = ShelfColls.arrange(prefs.colls, colls.map { ShelfColls.userKey(it.id) })
    val collItems = remember(books, hidden, userGroups, formats, folders, series, now) {
        listOf(
            CollItem(ShelfColls.ALL, "전체", books.size, Icons.Books),
            CollItem(ShelfColls.FORMAT, "형식", formats.size, Icons.FileDoc),
            CollItem(ShelfColls.FOLDER, "폴더", folders.size, Icons.FolderOpen),
            CollItem(ShelfColls.SERIES, "시리즈", series.size, Icons.Shelf),
            CollItem(ShelfColls.READING, "읽는 중", ShelfItems.builtIn(ShelfColls.READING, books, now).size, Icons.Bookmark),
            CollItem(ShelfColls.DONE, "다 읽음", ShelfItems.builtIn(ShelfColls.DONE, books, now).size, Icons.Done),
            CollItem(ShelfColls.RECENT_FILES, "최근 받은 파일", ShelfItems.builtIn(ShelfColls.RECENT_FILES, books, now).size, Icons.Clock),
            CollItem(ShelfColls.HIDDEN, "숨긴 책", hidden.size, Icons.Hide),
        ) + userGroups.map { (cc, l) -> CollItem(ShelfColls.userKey(cc.id), cc.name, l.size, Icons.Books) }
    }

    val host = ShelfHost(
        c, SharedBounds(null, null, true), unavailable, prefs, onPrefs, onOpen,
        bookMenu = { b, r, below, coll -> bookMenu = BookMenuAt(b, r, below, coll) },
        collMenu = { collMenu = it },
        go = ::go,
        pickCollection = { pickFor = it },
        removeBooks = { l -> l.forEach(actions.onRemove) },
        seriesManage = onSeriesManage,
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalShelfUi provides ui) {
        // 메뉴 뒤 흐림은 메뉴가 자기 자리만 흐린다(backdropBlur, 대표님 2026-10-26 애플 북스 방식)
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
                val h = ShelfHost(
                    host.c, SharedBounds(this@SharedTransitionLayout, this, reduce), host.unavailable, host.prefs, host.onPrefs, host.open,
                    host.bookMenu, host.collMenu, host.go, host.pickCollection, host.removeBooks, host.seriesManage,
                )
                val sec = p.firstOrNull()?.let { LibSection.of(it) }
                val back = { go(p.dropLast(1)) }
                val key0 = p.firstOrNull()
                when {
                    key0 == null -> LibraryHome(
                        books, formats, folders, series, recent, h, scanning, navBar, extras,
                        onSection = { go(listOf(it.key)) },
                        // 대표님 2026-10-24 23:10: 첫 화면 아래 빈칸 → '최근' 아래 책장 줄(≡ 모음 순서·숨김을 따름, 책이 있는 줄만)
                        shelves = arrangement.filter { it.second }.mapNotNull { (k, _) ->
                            when {
                                k == ShelfColls.READING || k == ShelfColls.RECENT_FILES || k == ShelfColls.DONE ->
                                    Triple(k, ShelfColls.TITLES[k].orEmpty(), ShelfItems.builtIn(k, books, now).let { l -> if (k == ShelfColls.READING) l.sortedByDescending { it.lastOpenedAt } else l })
                                ShelfColls.isUser(k) -> userGroups.firstOrNull { ShelfColls.userKey(it.first.id) == k }?.let { (cc, l) -> Triple(k, cc.name, l) }
                                else -> null
                            }
                        }.filter { it.third.isNotEmpty() },
                        onShelf = { k -> go(listOf(k)) },
                    )
                    sec == LibSection.ALL -> FlatShelf(h, ShelfColls.ALL, "전체", books, onBack = back, stackSeries = series)
                    sec == LibSection.RECENT -> FlatShelf(h, "recent", "최근", recent, onBack = back, fixedSort = true)
                    sec == LibSection.READING || sec == LibSection.DONE || sec == LibSection.RECENT_FILES ->
                        FlatShelf(h, sec.key, sec.title, ShelfItems.builtIn(sec.key, books, now), onBack = back)
                    sec == LibSection.HIDDEN -> FlatShelf(h, sec.key, sec.title, hidden, onBack = back, hiddenShelf = true)
                    sec == LibSection.SEARCH -> LibrarySearch(h, books, formats, folders, series, userGroups, onBack = back)
                    sec != null -> {
                        val cur = p.drop(1).lastOrNull()?.let { LibGroups.find(groupsOf(sec), it) }
                        if (cur != null) HeroDetail(h, cur, sec, folderPath = p.drop(1).mapNotNull { id -> LibGroups.find(groupsOf(sec), id)?.name }, onBack = back, onEnter = { k -> go(p + k.id) })
                        else GroupList(
                            sec, groupsOf(sec), h, onBack = back, onEnter = { go(p + it.id) },
                            onLong = if (sec == LibSection.SERIES) { g -> g.seriesKey?.let(onSeriesManage) } else null,
                        )
                    }
                    else -> userGroups.firstOrNull { ShelfColls.userKey(it.first.id) == key0 }?.let { (cc, l) ->
                        FlatShelf(h, key0, cc.name, l, onBack = back, collection = cc)
                    }
                }
            }
        }
    }

    bookMenu?.let { m ->
        val coll = m.collection?.let { k -> colls.firstOrNull { ShelfColls.userKey(it.id) == k } }
        BookMoreMenu(
            m.rect, byId[m.book.id] ?: hidden.firstOrNull { it.id == m.book.id } ?: m.book, m.below, onDismiss = { bookMenu = null },
            onInfo = { actions.onInfo(m.book) }, onOpen = { onOpen(m.book) }, onRemove = { actions.onRemove(m.book) }, onExport = { actions.onExport(m.book) },
            onAddToCollection = { pickFor = listOf(m.book.id) },
            onRemoveFromCollection = coll?.let { cc -> { app.appScope.launch { dao.removeFromCollection(cc.id, m.book.id) } } },
        )
    }
    collMenu?.let { r ->
        CollectionsMenu(
            r, collItems, arrangement,
            onPick = { k -> collMenu = null; go(listOf(k)) },
            onNew = { collMenu = null; nameFor = emptyList() },
            onArrange = { onPrefs(prefs.copy(colls = ShelfColls.format(it))) },
            onDismiss = { collMenu = null },
        )
    }
    pickFor?.let { ids ->
        PickCollectionDialog(colls, onPick = { cc -> addToCollection(context, cc, ids); pickFor = null }, onNew = { pickFor = null; nameFor = ids }, onDismiss = { pickFor = null })
    }
    nameFor?.let { ids ->
        CollectionNameDialog("새 모음", "", taken = { n -> colls.any { it.name == n } || n in ShelfColls.TITLES.values }, onDone = { n ->
            nameFor = null
            newCollection(context, n, ids)
        }, onDismiss = { nameFor = null })
    }
}

/** 책 ⋯ 메뉴를 띄울 자리. [collection] = 사용자 모음 안에서 열었으면 그 열쇠('이 모음에서 빼기'). */
internal data class BookMenuAt(val book: Book, val rect: Rect, val below: Boolean, val collection: String?)

/** 카드 ↔ 들어간 화면 확대 전환(같은 열쇠의 경계를 이어 줌). '동작 줄이기'·전환 밖이면 아무것도 하지 않는다. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class SharedBounds(val scope: SharedTransitionScope?, val av: AnimatedVisibilityScope?, val off: Boolean) {
    @Composable
    fun of(id: String): Modifier {
        if (off || scope == null || av == null) return Modifier
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
    val bookW: Dp, val pad: Dp, val gap: Dp, val rowGap: Dp,
)

private fun homeSpec(w: Dp, h: Dp): HomeSpec {
    val land = w > h
    val tablet = minOf(w, h) >= 600.dp
    // 대표님 2026-10-24 23:00·23:05: 카드 옆 간격은 넓게, 카드 높이는 줄여 표지 윗선이 제목보다 살짝 아래(제목·권수가 책보다 조금 높게),
    // 카드 줄 사이 간격은 옆 간격보다 조금 좁게. 카드 높이 = 가장 높은 표지 + 위 여유.
    return when {
        // 대표님 23:33: 카드 제목 글씨가 카드에 비해 크다 → 줄임, 카드 속 표지도 약 10% 작게(카드 높이도 따라 낮아짐)
        land && tablet -> HomeSpec(4, 130.dp + 28.dp, 86.dp, listOf(115.dp, 130.dp, 122.dp), 47.dp, 14f, LARGE_TITLE, 152.dp, 40.dp, 32.dp, 24.dp)
        land -> HomeSpec(4, 66.dp + 18.dp, 47.dp, listOf(58.dp, 66.dp, 61.dp), 25.dp, 12f, 24f, 80.dp, 24.dp, 20.dp, 14.dp)
        else -> {
            val s = (w / 390.dp).coerceIn(1f, 1.6f)
            // 대표님 23:26 '세로 줄을 조금씩 다 줄여': 카드 높이·책장 표지·줄 사이를 조금씩 줄여 첫 화면에 줄이 더 들어오게
            HomeSpec(2, (78.dp + 18.dp) * s, 52.dp * s, listOf(68.dp * s, 78.dp * s), 40.dp * s, 12f * s, LARGE_TITLE, shelfCoverW(w), 20.dp * s, 20.dp * s, 12.dp * s)
        }
    }
}

/**
 * 책장 줄 표지 폭(첫 화면 '최근'·책장 줄, 묶음 안 '이어 읽기'·하위 폴더 줄·'모든 책' 격자 모두 같은 값 — 대표님 22:58·23:26).
 * 화면 폭 [w] 기준: 휴대폰 세로 390dp 에서 78dp(대표님 23:40 '살짝 더 작게'), 넓으면 비례해 크게(최대 1.6배).
 * '전체' 등 모음 격자도 이 크기에 맞춰 열 수를 정한다(모든 책 크기를 같게).
 */
internal fun shelfCoverW(w: Dp): Dp = 78.dp * (w / 390.dp).coerceIn(1f, 1.6f)

/**
 * 서재가 위·아래 막대 밑까지 그릴 때 화면마다 둘 여백(상태 표시줄 높이·아래 내비게이션 높이). 다른 곳에서는 0.
 * (대표님 2026-10-25 00:24: 막대를 75% 투명하게 해서 지나가는 책이 보이게)
 */
val LocalBarInsets = androidx.compose.runtime.staticCompositionLocalOf { PaddingValues(0.dp) }

@Composable
internal fun barTop(): Dp = LocalBarInsets.current.calculateTopPadding()

@Composable
internal fun barBottom(): Dp = LocalBarInsets.current.calculateBottomPadding()

/** 창 좌표 저장(⋯·길게 누름 메뉴 자리). 다시 그리지 않도록 상태가 아닌 칸에 둔다. */
internal class RectHolder { var rect: Rect = Rect.Zero }

internal fun Modifier.trackRect(h: RectHolder) = onGloballyPositioned { h.rect = it.boundsInWindow() }

@Composable
private fun LibraryHome(
    books: List<Book>, formats: List<LibGroup>, folders: List<LibGroup>, series: List<LibGroup>, recent: List<Book>,
    h: ShelfHost, scanning: Boolean, navBar: (@Composable () -> Unit)?, extras: @Composable () -> Unit,
    onSection: (LibSection) -> Unit,
    /** '최근' 아래 책장 줄들(열쇠, 이름, 책). */
    shelves: List<Triple<String, String, List<Book>>> = emptyList(),
    onShelf: (String) -> Unit = {},
) {
    val c = h.c
    // 카드 표지: 묶음마다 앞의 책(형식·폴더·시리즈는 서로 다른 묶음에서 하나씩 — 무엇이 들었는지 보이게)
    fun spread(gs: List<LibGroup>) = gs.mapNotNull { it.books.firstOrNull() }.ifEmpty { gs.flatMap { it.books } }
    val collBtn = remember { RectHolder() }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.detail)) {
        val sp = homeSpec(maxWidth, maxHeight)
        val cards = listOf(
            Triple(LibSection.ALL, "${books.size}권", recent),
            Triple(LibSection.FORMAT, "${formats.size}가지", spread(formats)),
            Triple(LibSection.FOLDER, "${folders.size}개", spread(folders)),
            Triple(LibSection.SERIES, "${series.size}개", spread(series)),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = barTop(), bottom = 24.dp + barBottom())) {
            item(key = "head") {
                Row(Modifier.padding(start = sp.pad + 4.dp, end = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("서재", fontSize = sp.title.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    // 모음(≡)·검색은 아래 내비게이션으로 옮김(대표님 2026-10-25 00:27). 가로 화면은 제목 줄 오른쪽 내비게이션에 함께
                    navBar?.invoke()
                }
                if (scanning) androidx.compose.material3.LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = sp.pad).semantics { contentDescription = "폴더에서 책을 모으는 중" },
                )
            }
            item(key = "cards") {
                // 카드 2×2(가로 화면은 한 줄). 옆으로 넘기는 한 줄은 대표님이 보시고 되돌림(2026-10-25 00:40). 어둡게: 카드 묶음 뒤 띠 #161618
                Column(
                    Modifier.background(if (c.dark) c.shelfBottom else Color.Transparent).padding(horizontal = sp.pad).padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(sp.rowGap),
                ) {
                    cards.chunked(sp.cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.gap)) {
                            row.forEach { (sec, sub, fan) ->
                                HomeCard(sec.title, sub, fan, sp, c, Modifier.weight(1f).then(h.shared.of("sec-${sec.key}"))) { onSection(sec) }
                            }
                        }
                    }
                }
            }
            if (books.isEmpty()) item(key = "empty") {
                Text(
                    "아직 책이 없어요. 설정 › 서재 폴더에서 책이 있는 폴더를 고르거나, 다른 앱에서 파일을 'ASH Reader로 열기' 해 보세요.",
                    color = c.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = sp.pad + 4.dp, vertical = 12.dp),
                )
            } else item(key = "recent") {
                ShelfRow(
                    "최근", null, recent.take(30), h, h.shared.of("sec-recent"), { onSection(LibSection.RECENT) },
                    coverW = sp.bookW, sidePad = sp.pad + 4.dp, bleedPad = 0.dp, titleLabel = "최근 읽은 책 모두 보기",
                )
            }
            shelves.forEach { (k, title, l) ->
                item(key = "shelf-$k") {
                    ShelfRow(
                        title, "${l.size}권", l.take(30), h, h.shared.of("sec-$k"), { onShelf(k) },
                        coverW = sp.bookW, sidePad = sp.pad + 4.dp, bleedPad = 0.dp, titleLabel = "$title, ${l.size}권, 모두 보기",
                    )
                }
            }
            item(key = "extras") { extras() }
        }
    }
}

/** 첫 화면 카드: 이름(굵게) + 개수, 오른쪽 아래 표지가 바닥에 붙어 겹침. TalkBack "전체, 312권". */
@Composable
private fun HomeCard(name: String, sub: String, books: List<Book>, sp: HomeSpec, c: ShelfColors, modifier: Modifier, onClick: () -> Unit) {
    val shape = CARD_SHAPE
    Box(
        modifier.height(sp.cardH)
            .cardShadow(shape, c.dark)
            .clip(shape).background(c.card)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$name, $sub"; role = Role.Button },
    ) {
        CoverFan(books, sp.heights, sp.coverW, sp.step, Modifier.align(Alignment.BottomEnd).offset(x = 10.dp))
        // 대표님 23:46: 제목은 예전 권수 크기(12sp)로 굵게, 권수는 그에 맞춰 더 작게
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp)) {
            Text(name, fontSize = sp.name.sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1)
            Text(sub, fontSize = (sp.name * 0.83f).sp, color = smallSub, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** 표지 [heights].size 장이 바닥에 붙어 서서 겹친다(왼쪽 그림자). 권 수가 모자라면 오른쪽에 붙인다. */
@Composable
internal fun CoverFan(books: List<Book>, heights: List<Dp>, coverW: Dp, step: Dp, modifier: Modifier) {
    val n = heights.size
    Box(modifier.size(width = step * (n - 1) + coverW, height = heights.max())) {
        val fan = books.take(n)
        fan.forEachIndexed { i, b ->
            val k = i + n - fan.size
            // 카드 속 표지: 그림자는 아주 흐리게 왼쪽과 위만(위는 테두리처럼 옅은 회색 그러데이션) — 애플 북스
            ShelfCover(
                b,
                Modifier.align(Alignment.BottomStart).offset(x = step * k).size(coverW, heights[k]).drawBehind {
                    val sw = 8.dp.toPx(); val top = 3.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.09f)), startX = -sw, endX = 0f),
                        topLeft = Offset(-sw, 0f), size = Size(sw, size.height),
                    )
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f)), startY = -top, endY = 0f),
                        topLeft = Offset(0f, -top), size = Size(size.width, top),
                    )
                },
                fan = true,
            )
        }
    }
}

/**
 * 스크롤하면 위에 나타나는 유리 막대 + 가운데 작은 제목(애플식, 대표님 01:10). 큰 제목이 위 단추 밑으로 지나가며 겹치지 않게 한다.
 * 위 단추 줄(8dp + 48dp)과 같은 높이에 제목을 둔다.
 */
@Composable
internal fun ScrollTopBar(c: ShelfColors, title: String, shown: Boolean) {
    val a by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(180), label = "topbar")
    if (a <= 0f) return
    val top = barTop()
    Box(
        Modifier.fillMaxWidth().height(top + 64.dp).graphicsLayer { alpha = a }.background(c.bar)
            .drawBehind { drawRect(c.barLine, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) },
    ) {
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = top + 20.dp, start = 120.dp, end = 120.dp),
        )
    }
}

/** 떠 있는 둥근 단추(뒤로·모음·검색·⋯): 44dp 모서리 12dp, 누름 48dp. [onHero] = 색 머리 위(흰 18%). */
@Composable
internal fun FloatingButton(c: ShelfColors, label: String, icon: ImageVector, modifier: Modifier = Modifier, onHero: Boolean = false, onClick: () -> Unit) {
    val bg = if (onHero) Color.White.copy(alpha = 0.18f) else c.btn
    val line = if (onHero) Color.White.copy(alpha = 0.2f) else c.barLine
    val tint = if (onHero) Color.White else c.ink
    Box(
        modifier.size(48.dp).padding(2.dp)
            .shadow(if (onHero || c.dark) 0.dp else 10.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (icon == Icons.Back) 20.dp else 19.dp)) }
}

/** 가로 화면 제목 줄의 내비게이션 막대(서재·독서 기록·설정 글자만, 위 탭 모양). */
@Composable
fun HeaderNav(
    labels: List<String>, selected: Int, onPick: (Int) -> Unit,
    actions: List<Pair<String, ImageVector>> = emptyList(), onAction: (Int, Rect) -> Unit = { _, _ -> },
) {
    val c = shelfColors()
    Row(
        Modifier.shadow(if (c.dark) 0.dp else 10.dp, RoundedCornerShape(11.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.16f))
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
        // 모음·검색(아래 내비게이션과 같은 단추)
        actions.forEachIndexed { i, (label, icon) ->
            val r = remember { RectHolder() }
            Box(
                Modifier.size(width = 48.dp, height = 42.dp).trackRect(r).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onAction(i, r.rect) }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = c.ink, modifier = Modifier.size(19.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupList(
    sec: LibSection, groups: List<LibGroup>, h: ShelfHost,
    onBack: () -> Unit, onEnter: (LibGroup) -> Unit, onLong: ((LibGroup) -> Unit)?,
) {
    val c = h.c
    val collBtn = remember { RectHolder() }
    // 넓은 화면(태블릿)은 카드를 여러 열로(한 장 최소 340dp) — 화면 폭 가득 늘어나지 않게
    BoxWithConstraints(h.shared.of("sec-${sec.key}").fillMaxSize().background(Brush.verticalGradient(listOf(c.pageTop, c.pageBottom)))) {
        val cols = ((maxWidth - 32.dp) / 340.dp).toInt().coerceIn(1, 3)
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 90 } }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 28.dp + barBottom())) {
            item(key = "heading") {
                Text(
                    sec.title, fontSize = LARGE_TITLE.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 66.dp + barTop(), bottom = 14.dp).semantics { heading() },
                )
            }
            if (groups.isEmpty()) item(key = "empty") {
                Text(
                    if (sec == LibSection.SERIES) "같은 폴더에서 제목 끝 권·화 번호만 다른 책을 시리즈로 묶어 보여 줘요. 책의 ⋯ 메뉴에서 직접 묶을 수도 있어요." else "아직 책이 없어요.",
                    color = c.sub, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(groups.chunked(cols), key = { it.first().id }) { row ->
                Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { g ->
                        GroupCard(g, c, Modifier.weight(1f).then(h.shared.of(g.id)), onClick = { onEnter(g) }, onLong = onLong?.let { l -> { l(g) } })
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        ScrollTopBar(c, sec.title, scrolled)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp + barTop()), horizontalArrangement = Arrangement.SpaceBetween) {
            FloatingButton(c, "뒤로", Icons.Back, onClick = onBack)
            FloatingButton(c, "모음", Icons.Menu, Modifier.trackRect(collBtn)) { h.collMenu(collBtn.rect) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(g: LibGroup, c: ShelfColors, modifier: Modifier, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val shape = CARD_SHAPE
    Box(
        modifier.fillMaxWidth().heightIn(min = 104.dp)
            .cardShadow(shape, c.dark)
            .clip(shape).background(c.card)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .clearAndSetSemantics { contentDescription = "${g.name}, ${g.books.size}권"; role = Role.Button },
    ) {
        // 오른쪽: 이 묶음 책 표지 3장이 카드 바닥에 붙어 서서 겹친다(높이 조금씩 다르게, 오른쪽 끝은 카드 밖으로 잘림)
        CoverFan(g.books, listOf(78.dp, 87.dp, 82.dp), 63.dp, 49.dp, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp))
        // 제목·권수는 위쪽(표지 윗선보다 살짝 높게 — 대표님 23:05), 글씨는 카드에 맞게 작게(23:33)
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.46f).padding(start = 18.dp, top = 12.dp, bottom = 12.dp)) {
            Text(g.name, fontSize = 13.sp, fontWeight = FontWeight.Black, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LibGroups.cardSub(g), fontSize = 11.sp, color = smallSub, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** 격자 칸 밖으로 [d] 만큼 좌우로 넓힌다(책장 줄 띠를 화면 끝까지). */
internal fun Modifier.bleed(d: Dp) = layout { m, cons ->
    val extra = d.roundToPx() * 2
    val p = m.measure(cons.copy(minWidth = cons.maxWidth + extra, maxWidth = cons.maxWidth + extra))
    layout(cons.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** 책장 줄: 바탕 → 옅은 띠(어둡게 #161618), 줄 제목(하위 폴더면 '›' 를 눌러 그 안으로), 표지를 옆으로 넘김. 표지 길게 누름 = 책 ⋯ 메뉴. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfRow(
    title: String, count: String?, books: List<Book>, h: ShelfHost, modifier: Modifier, onTitle: (() -> Unit)?,
    coverW: Dp = 98.dp, sidePad: Dp = 20.dp, bleedPad: Dp = 20.dp, titleLabel: String? = null,
) {
    val c = h.c
    Column(modifier.bleed(bleedPad).background(Brush.verticalGradient(listOf(c.detail, c.shelfBottom))).padding(top = 8.dp, bottom = 14.dp)) {
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
            Modifier.padding(top = 2.dp).height(coverW * 1.48f + 10.dp),
            contentPadding = PaddingValues(horizontal = sidePad, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(books.take(60), key = { it.id }) { b ->
                val r = remember { RectHolder() }
                ShelfCover(
                    b,
                    Modifier.width(coverW).aspectRatio(coverRatio(b)).trackRect(r)
                        .combinedClickable(onClick = { h.open(b) }, onLongClick = { h.bookMenu(b, r.rect, false, null) })
                        .semantics { contentDescription = bookLabel(b, false) },
                    bar = true,
                )
            }
        }
    }
}

/** 책 한 권의 TalkBack·시험 이름: "제목, 형식, 42% 읽음" / "…, 열 수 없음". */
internal fun bookLabel(b: Book, unavailable: Boolean) =
    "${b.shownTitle}, ${DocFormat.of(b.format).badge}, " + if (unavailable) "열 수 없음" else "${(b.progress() * 100).toInt()}% 읽음"
