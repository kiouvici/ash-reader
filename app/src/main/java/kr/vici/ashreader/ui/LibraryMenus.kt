// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.CollectionBook
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.ShelfCollection

// 작업 07 서재 메뉴(LibraryMenus·MoreMenu 목업): 떠 있는 유리 메뉴. 감수 3 — 흐림은 API 31 이상만(뒤 화면을 흐림), 그 아래는 불투명 92%.

/** 서재 화면 공용 상태: 열린 유리 메뉴 수(뒤 화면 흐리기). */
class ShelfUi { var menus by mutableIntStateOf(0) }

val LocalShelfUi = compositionLocalOf { ShelfUi() }

/** 뒤 화면을 흐릴 수 있나(API 31+, 감수 3). */
val canBlur: Boolean get() = Build.VERSION.SDK_INT >= 31

/**
 * 모든 떠 있는 메뉴·시트·단추의 바탕 불투명도(대표님 2026-10-25 00:02: 모두 같게, 지금보다 조금 더 투명하게).
 * 서재 ⋯·≡ 메뉴, 선택 막대, 읽기 화면 바깥 틀 단추·떠 있는 메뉴·테마 및 설정·사용자화가 함께 쓴다.
 */
const val GLASS_ALPHA = 0.83f // 대표님 2026-10-25 00:19: 83%

/** 유리 메뉴 바탕: 밝게 흰, 어둡게 #2C2C2E — 불투명도 [GLASS_ALPHA]. */
@Composable
fun glassBg(): Color = if (LocalAshDark.current) Color(0xFF2C2C2E).copy(alpha = GLASS_ALPHA) else Color.White.copy(alpha = GLASS_ALPHA)

/**
 * 투명 메뉴가 뜨면 뒤 화면을 살짝 흐리고 아주 옅게 어둡게(대표님 2026-10-25 01:00: 투명 메뉴 모두).
 * 메뉴가 떠 있는 창(Popup·바텀시트) 안에서 부른다 — 시스템 창 흐림이라 쪽 넘김(SurfaceView)·위아래 막대까지 함께 흐려진다.
 * 흐림은 API 31 이상(기기에서 창 흐림을 꺼 두면 어둡게만), 그 아래는 어둡게만. [dim] 0 = 어둡게 하지 않음(바텀시트는 자체 가림막이 있음).
 */
@Composable
fun BlurBehind(radius: Dp = 40.dp, dim: Float = 0.10f) { // 대표님 2026-10-25: 더 뭉개지게(24 → 40dp)
    val view = androidx.compose.ui.platform.LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    // 시스템은 어둡게 깃발이 켜진 창 아래에만 흐림을 그린다 — 흐림 세기도 그 진하기를 따르므로 흐릴 때는 모든 메뉴·시트를 같은 10%로(대표님 01:35 확인: 블러 통일)
    val dimNow = if (canBlur) maxOf(dim, 0.10f) else dim
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        if (window != null) {
            // 바텀시트 창 아래 내비게이션 줄이 흰 띠로 남지 않게(가림막·흐림이 화면 끝까지)
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            // 바텀시트(대화 창)는 흐림 깃발을 붙여도 이 기기들에서 흐림이 그려지지 않는다(01:40 태블릿 확인).
            // 메뉴와 똑같이 보이도록, 앱 화면에 딸린 투명 창(누름은 받지 않음)을 깔아 그 창이 흐림·어둡게를 맡고 시트를 닫으면 걷는다
            val act = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            val token = act?.window?.decorView?.windowToken
            if (act != null && token != null && dimNow > 0f) {
                val veil = android.view.View(act).apply { setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0)) } // 아무것도 안 그리면 창이 보이지 않은 것으로 쳐져 흐림이 안 걸린다
                val lp = android.view.WindowManager.LayoutParams(
                    1, 1, // 흐림·어둡게는 창 크기와 상관없이 화면 전체에 걸린다 — 화면 전체 크기의 투명 창은 흐림이 그려지지 않아(01:50 확인) 1점짜리로
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND or (if (canBlur) android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    this.token = token
                    dimAmount = dimNow
                    if (canBlur) blurBehindRadius = px
                    if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                    if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                val ok = runCatching { act.windowManager.addView(veil, lp) }.isSuccess
                return@DisposableEffect onDispose { if (ok) runCatching { act.windowManager.removeViewImmediate(veil) } }
            }
        } else {
            // Popup: 창에 붙은 맨 위 뷰의 창 속성을 바꾼다(Popup 이 다시 맞출 때도 같은 객체라 유지됨)
            val root = view.rootView
            val lp = root.layoutParams as? android.view.WindowManager.LayoutParams
            val wm = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (lp != null && wm != null) {
                if (dimNow > 0f) { lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND; lp.dimAmount = dimNow }
                if (canBlur) { lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND; lp.blurBehindRadius = px }
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
        onDispose {}
    }
}

/** 메뉴 칸 구분선 색. */
@Composable
fun glassSep(): Color = if (LocalAshDark.current) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)

/**
 * 떠 있는 유리 메뉴. [anchor] = 누른 곳(창 좌표). [below] = 단추 아래로 펼침(오른쪽 맞춤), 아니면 표지 옆에 겹쳐 펼침.
 * 화면 밖으로 나가지 않게 맞추고, 아래 자리가 모자라면 위로 올린다.
 */
@Composable
fun GlassPopup(anchor: Rect, onDismiss: () -> Unit, width: Dp = 250.dp, below: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalShelfUi.current
    DisposableEffect(Unit) { ui.menus++; onDispose { ui.menus-- } }
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val provider = remember(anchor, below) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val w = popupContentSize.width; val h = popupContentSize.height
                var x = if (below) anchor.right.toInt() - w else (anchor.left + anchor.width * 0.55f).toInt()
                var y = if (below) anchor.bottom.toInt() + margin else (anchor.top + anchor.height * 0.3f).toInt()
                x = x.coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin))
                if (y + h > windowSize.height - margin) y = (if (below) anchor.top.toInt() - h - margin else windowSize.height - h - margin)
                y = y.coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin))
                return IntOffset(x, y)
            }
        }
    }
    val dark = LocalAshDark.current
    val shape = RoundedCornerShape(14.dp)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        BlurBehind()
        Column(
            Modifier.width(width)
                .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = if (dark) 0.6f else 0.1f), spotColor = Color.Black.copy(alpha = if (dark) 0.8f else 0.25f))
                .clip(shape).background(glassBg())
                .border(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f), shape)
                .heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/**
 * 메뉴 한 줄(48dp, 감수 2). [check] = 고른 것(✓, 선택 상태 읽어 주기 — 감수 4). [checkable] 이면 ✓ 자리를 비워 둔다.
 */
@Composable
fun MenuRow(
    label: String, icon: ImageVector? = null, color: Color = Color.Unspecified, check: Boolean = false, checkable: Boolean = false,
    sepTop: Boolean = false, trailing: String? = null, chevron: Boolean = false, onClick: () -> Unit,
) {
    val c = shelfColors()
    val fg = if (color == Color.Unspecified) c.ink else color
    Column {
        if (sepTop) Box(Modifier.fillMaxWidth().height(1.dp).background(glassSep()))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(
                    if (checkable) Modifier.selectable(check, role = Role.RadioButton, onClick = onClick)
                    else Modifier.clickable(role = Role.Button, onClick = onClick),
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (checkable) Text(if (check) "✓" else "", fontSize = 14.sp, color = fg, modifier = Modifier.width(14.dp))
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(19.dp))
            Text(label, fontSize = 15.sp, color = fg, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trailing != null) Text(trailing, fontSize = 13.5.sp, color = smallSub)
            if (chevron) Icon(Icons.Chevron, contentDescription = null, tint = c.sub2, modifier = Modifier.size(14.dp))
        }
    }
}

/** 메뉴 안 작은 회색 머리글("다음으로 정렬…"). */
@Composable
fun MenuLabel(text: String, sepTop: Boolean = true) {
    Column {
        if (sepTop) Box(Modifier.fillMaxWidth().height(1.dp).background(glassSep()))
        Text(text, fontSize = 12.5.sp, color = smallSub, modifier = Modifier.padding(start = 40.dp, top = 10.dp, bottom = 4.dp).semantics { heading() })
    }
}

// ── ① ≡ 모음 ──

/** 모음 한 칸(≡ 목록·첫 화면에 쓰는 정보). */
data class CollItem(val key: String, val title: String, val count: Int, val icon: ImageVector)

/**
 * ≡ 모음 메뉴: 머리 '모음' + [편집], 모음 목록(아이콘·이름·권수·›), 아래 [새 모음…].
 * [editing] 이면 숨기기·순서 바꾸기(▲▼) — 바꾼 순서는 [onArrange] 로 돌려준다.
 */
@Composable
fun CollectionsMenu(
    anchor: Rect, items: List<CollItem>, arrangement: List<Pair<String, Boolean>>,
    onPick: (String) -> Unit, onNew: () -> Unit, onArrange: (List<Pair<String, Boolean>>) -> Unit, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val byKey = items.associateBy { it.key }
    GlassPopup(anchor, onDismiss, width = 300.dp) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
            Text("모음", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = shelfColors().ink, modifier = Modifier.semantics { heading() })
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(width = 64.dp, height = 48.dp).clickable(role = Role.Button) { editing = !editing }
                    .semantics { contentDescription = if (editing) "편집 끝내기" else "모음 편집(순서 바꾸기·숨기기)" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (LocalAshDark.current) Color(0xFF3A3A3C) else Color(0xFFEFEFF2)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(if (editing) "완료" else "편집", fontSize = 13.sp, color = shelfColors().ink)
                }
            }
        }
        val box = Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp)).background(if (LocalAshDark.current) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f))
        Column(box) {
            if (!editing) {
                arrangement.filter { it.second }.mapNotNull { byKey[it.first] }.forEachIndexed { i, it ->
                    MenuRow(it.title, it.icon, trailing = it.count.toString(), chevron = true, sepTop = i > 0) { onPick(it.key) }
                }
            } else {
                arrangement.forEachIndexed { i, (k, on) ->
                    val it = byKey[k] ?: return@forEachIndexed
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clickable(role = Role.Switch) {
                                onArrange(arrangement.toMutableList().also { l -> l[i] = k to !on })
                            }.semantics { contentDescription = "${it.title} ${if (on) "숨기기" else "보이기"}"; stateDescription = if (on) "보임" else "숨김" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(if (on) Icons.Show else Icons.Hide, null, tint = if (on) shelfColors().blue else smallSub, modifier = Modifier.size(19.dp)) }
                        Text(it.title, fontSize = 15.sp, color = if (on) shelfColors().ink else smallSub, modifier = Modifier.weight(1f))
                        MoveButton(Icons.Up, "${it.title} 위로", i > 0) { onArrange(arrangement.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }
                        MoveButton(Icons.Down, "${it.title} 아래로", i < arrangement.size - 1) { onArrange(arrangement.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }
                    }
                }
            }
        }
        Box(Modifier.padding(10.dp).then(box.padding(0.dp))) {
            MenuRow("새 모음…", Icons.Plus) { onNew() }
        }
    }
}

@Composable
private fun MoveButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) shelfColors().ink else smallSub.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) }
}

/** 새 모음 이름 넣기(또는 이름 바꾸기). */
@Composable
fun CollectionNameDialog(title: String, initial: String, taken: (String) -> Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val n = name.trim()
    val bad = n.isNotEmpty() && n != initial && taken(n)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, placeholder = { Text("모음 이름") }, isError = bad, modifier = Modifier.fillMaxWidth())
                if (bad) Text("같은 이름의 모음이 있어요.", fontSize = 12.sp, color = Color(0xFFB3261E), modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onDone(n) }, enabled = n.isNotEmpty() && !bad, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

/** 모음 고르기(책을 모음에 넣기). 맨 위 '새 모음…'. */
@Composable
fun PickCollectionDialog(colls: List<ShelfCollection>, onPick: (ShelfCollection) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모음에 넣기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onNew), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Plus, null, modifier = Modifier.size(20.dp)); Text("새 모음…", fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
                colls.forEach { c ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(c) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Books, null, modifier = Modifier.size(20.dp)); Text(c.name, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
}

/** 새 모음 만들기(+ 처음 넣을 책들). 이름이 겹치지 않게 id 는 시각. */
fun newCollection(context: android.content.Context, name: String, books: List<String>, onMade: (ShelfCollection) -> Unit = {}) {
    val app = context.ash
    app.appScope.launch {
        val dao = app.db.books()
        val c = ShelfCollection(id = System.currentTimeMillis().toString(36), name = name, ord = dao.allCollections().size)
        dao.upsertCollection(c)
        if (books.isNotEmpty()) dao.addToCollection(books.mapIndexed { i, b -> CollectionBook(c.id, b, i) })
        onMade(c)
    }
}

/** 모음에 책 넣기(이미 든 책은 그대로). */
fun addToCollection(context: android.content.Context, c: ShelfCollection, books: List<String>) {
    val app = context.ash
    app.appScope.launch {
        val dao = app.db.books()
        val start = dao.allCollectionBooks().count { it.collectionId == c.id }
        dao.addToCollection(books.mapIndexed { i, b -> CollectionBook(c.id, b, start + i) })
    }
}

// ── ② ⋯ 보기·정렬 ──

/** ⋯ 보기·정렬: 선택 / 격자·목록 / 다음으로 정렬…(최근 읽은 순·제목·지은이·추가한 날·직접 정렬). [extra] = 모음마다 더하는 줄(순서 바꾸기·이름 바꾸기 등). */
@Composable
fun ViewSortMenu(
    anchor: Rect, view: ShelfView, onSelect: () -> Unit, onView: (ShelfView) -> Unit, onDismiss: () -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GlassPopup(anchor, onDismiss, width = 236.dp) {
        Spacer(Modifier.height(6.dp))
        MenuRow("선택", Icons.Select, checkable = true) { onDismiss(); onSelect() }
        MenuRow("격자", Icons.Grid, checkable = true, check = !view.list, sepTop = true) { onView(view.copy(list = false)); onDismiss() }
        MenuRow("목록", Icons.ListView, checkable = true, check = view.list) { onView(view.copy(list = true)); onDismiss() }
        MenuLabel("다음으로 정렬…")
        GroupSort.MENU.forEach { s ->
            MenuRow(s.label, checkable = true, check = view.sort == s) { onView(view.copy(sort = s)); onDismiss() }
        }
        extra?.invoke(this)
        Spacer(Modifier.height(6.dp))
    }
}

// ── 책 ⋯ 메뉴(MoreMenu) ──

/**
 * 책 ⋯ 메뉴(⋯ 누름 또는 표지 길게 누름): 위 아이콘 3개[공유·책 정보·파일 위치] / 시리즈로 묶기·다 읽음으로 표시·표지 바꾸기·처음부터 다시 읽기 /
 * 구분선 / 서재에서 숨기기·서재에서 빼기…(빨강, 확인 창 — 파일은 지우지 않음).
 * 예전 문서 메뉴에 있던 것(즐겨찾기·모음에 넣기·형광펜·메모 내보내기·시리즈에서 빼기)도 빠짐없이 이 안에 둔다.
 */
@Composable
fun BookMoreMenu(
    anchor: Rect, b: Book, below: Boolean, onDismiss: () -> Unit,
    onInfo: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit, onExport: () -> Unit,
    onAddToCollection: () -> Unit, onRemoveFromCollection: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.ash
    var where by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val red = Color(0xFFFF3B30)
    val shown = !where && !grouping && !confirmRemove
    if (shown) GlassPopup(anchor, onDismiss, width = 260.dp, below = below) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
            TopIcon(Icons.Share, "공유") { share(context, b); onDismiss() }
            TopIcon(Icons.Info, "책 정보") { onDismiss(); onInfo() }
            TopIcon(Icons.FolderOpen, "파일 위치") { where = true }
        }
        val inSeries = SeriesGroups.keyOf(b) != null && b.seriesKey.isNotBlank() && b.seriesManual != Book.SERIES_EXCLUDED
        if (inSeries && b.seriesManual == Book.SERIES_MANUAL) MenuRow("시리즈에서 빼기", Icons.Group, sepTop = true) {
            app.appScope.launch { app.db.books().setSeries(b.id, b.seriesKey, Book.SERIES_EXCLUDED) }; onDismiss()
        } else MenuRow("시리즈로 묶기", Icons.Group, sepTop = true) { grouping = true }
        val done = b.status == Book.DONE
        MenuRow(if (done) "다 읽음 표시 빼기" else "다 읽음으로 표시", Icons.Done) {
            app.appScope.launch { app.db.books().setStatus(b.id, if (done) (if (b.lastOpenedAt > 0) Book.READING else Book.TO_READ) else Book.DONE) }; onDismiss()
        }
        MenuRow("표지 바꾸기", Icons.Picture) { onDismiss(); onInfo() }
        MenuRow("처음부터 다시 읽기", Icons.Restart) { app.appScope.launch { app.db.books().restart(b.id) }; onDismiss(); onOpen() }
        MenuRow(if (b.favorite) "즐겨찾기 빼기" else "즐겨찾기", Icons.Star) { app.appScope.launch { app.db.books().setFavorite(b.id, !b.favorite) }; onDismiss() }
        MenuRow("모음에 넣기…", Icons.Plus) { onDismiss(); onAddToCollection() }
        if (onRemoveFromCollection != null) MenuRow("이 모음에서 빼기", Icons.Minus) { onDismiss(); onRemoveFromCollection() }
        MenuRow("형광펜·메모 내보내기", Icons.Save) { onDismiss(); onExport() }
        val hidden = b.hidden == Book.HIDDEN_USER
        MenuRow(if (hidden) "서재에 다시 보이기" else "서재에서 숨기기", if (hidden) Icons.Show else Icons.Hide, sepTop = true) {
            app.appScope.launch { app.db.books().setHidden(b.id, if (hidden) 0 else Book.HIDDEN_USER) }; onDismiss()
        }
        MenuRow("서재에서 빼기…", Icons.Trash, color = red) { confirmRemove = true }
    }
    if (where) BookWhereDialog(b) { where = false; onDismiss() }
    if (grouping) GroupIntoSeries(b) { grouping = false; onDismiss() }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false; onDismiss() },
        title = { Text("서재에서 뺄까요?") },
        text = { Text("‘${b.shownTitle}’을(를) 서재 목록에서만 뺍니다. 기기의 파일은 지우지 않아요.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onDismiss(); onRemove() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("빼기", color = Color(0xFFB3261E)) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 70.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = shelfColors().ink, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.5.sp, color = shelfColors().ink, maxLines = 1)
    }
}

private val SHARE_MIME = mapOf(
    "epub" to "application/epub+zip", "pdf" to "application/pdf", "txt" to "text/plain", "hwp" to "application/x-hwp",
    "hwpx" to "application/hwp+zip", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "doc" to "application/msword", "cbz" to "application/vnd.comicbook+zip", "cbr" to "application/vnd.comicbook-rar",
    "zip" to "application/zip", "djvu" to "image/vnd.djvu", "mobi" to "application/x-mobipocket-ebook", "fb2" to "application/x-fictionbook+xml",
)

/**
 * 책 파일 공유: FileProvider 가 내보내는 곳은 캐시 share/ 하나뿐(F-59)이라, 읽을 수 있는 파일을 그곳에 복사해 넘긴다.
 * 10분 지난 공유 사본은 지운다. 큰 파일이면 복사하는 동안 잠깐 걸린다.
 */
private fun share(context: android.content.Context, b: Book) {
    val app = context.ash
    app.appScope.launch {
        val uri = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val src = java.io.File(app.library.localPath(b))
                // 지난 공유 사본은 10분이 지난 것만 지운다(받는 앱이 아직 읽고 있을 수 있음 — 그림 공유와 같은 규칙)
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000L) it.delete() } }
                val ext = src.extension.ifBlank { kr.vici.ashreader.data.DocFormat.of(b.format).badge.lowercase() }
                val name = b.shownTitle.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "book" } + "." + ext
                val out = java.io.File(dir, name)
                src.copyTo(out, overwrite = true)
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", out) to ext
            }
        }.getOrNull() ?: return@launch
        // 확장자 → MIME(네트워크 패키지는 쓰지 않는다 — checkNetworkConfined)
        val mime = SHARE_MIME[uri.second.lowercase()] ?: "application/octet-stream"
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri.first)
            .putExtra(Intent.EXTRA_SUBJECT, b.shownTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(Intent.createChooser(i, "책 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }
}
