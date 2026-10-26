// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.Links
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.NotesExport
import kr.vici.ashreader.data.StorageAccess
import kr.vici.ashreader.data.StoreApp
import kr.vici.ashreader.data.StoreRepo
import kr.vici.ashreader.data.progress

// ───────────────────────────── S-02 파일 접근 권한 안내 ─────────────────────────────

/**
 * S-02. Android 11+: [허용하러 가기] → 시스템 '모든 파일 접근' 화면 → 돌아오면 결과 확인.
 * Android 8~10: 저장소 읽기 권한 대화상자. 거부·나중에 → [onFolders](S-03 폴더 직접 고르기).
 */
@Composable
fun StorageIntroScreen(onGranted: () -> Unit, onFolders: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (StorageAccess.hasFileAccess(context)) onGranted() else denied = true
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted() else denied = true
    }
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        // TODO(D-009): 폴더 일러스트 IMG-04 는 ChatGPT 제작 그림으로 교체
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Ash.ChipOn), contentAlignment = Alignment.Center) {
            Icon(Icons.Folder, null, Modifier.size(44.dp), tint = cs.primary)
        }
        Text("기기 안의 책과 문서를\n찾으려면 허락이 필요해요", fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp,
            color = cs.primary, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp))
        listOf(
            "고른 폴더(서재 폴더)의 책을 서재에 모아요. 다음 화면에서 폴더를 골라요.",
            "파일은 읽기만 하고 고치지 않아요.",
            "책 파일·내용은 어디로도 보내지 않아요. 인터넷은 '책 정보 찾기'를 켤 때만 써요(정리한 제목만).",
        ).forEach { t ->
            Row(Modifier.padding(bottom = 10.dp)) {
                Text("✓", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                Text(t, fontSize = 16.sp, lineHeight = 23.sp)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Text("다음 화면에서 'ASH Reader'의 [모든 파일 접근 허용]을 켜 주세요.", fontSize = 14.sp, lineHeight = 20.sp,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        if (denied) {
            Text(
                if (Build.VERSION.SDK_INT >= 30) "아직 허용되지 않았어요. 폴더를 직접 골라도 돼요."
                else "허용되지 않았어요. 다시 묻지 않게 했다면 설정 > 앱 > 권한에서 켤 수 있어요.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val ok = StorageAccess.allFilesSettingsIntents(context).any { i ->
                        try { settingsLauncher.launch(i); true } catch (_: ActivityNotFoundException) { false }
                    }
                    if (!ok) denied = true
                } else if (denied && Build.VERSION.SDK_INT < 30) {
                    try { context.startActivity(StorageAccess.appSettingsIntent(context)) } catch (_: ActivityNotFoundException) {}
                    denied = false
                } else permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(if (denied && Build.VERSION.SDK_INT < 30) "설정 열기" else "허용하러 가기", fontSize = 16.sp) }
        TextButton(onClick = onFolders, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)) {
            Text("폴더만 직접 고를게요", fontSize = 15.sp)
        }
    }
}

// ───────────────────────────── F-36 서점 바로가기 줄 / S-15 편집 ─────────────────────────────

/** 서재 '내 서점 앱' 줄. 앱 아이콘 + 이름, 설치 안 된 앱은 흐리게. */
@Composable
fun StoreRow(apps: List<StoreApp>, onEdit: () -> Unit, onAddBook: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var missing by remember { mutableStateOf<StoreApp?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("내 서점 앱", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("편집", fontSize = 13.sp) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.forEach { a ->
            StoreChip(a, onClick = { if (!Links.launch(context, a.pkg)) missing = a })
        }
        if (apps.isEmpty()) DashedChip("+ 서점 앱", onEdit)
        DashedChip("+ 책 추가", onAddBook)
    }
    missing?.let { a ->
        val hasStore = remember { Links.hasStore(context) }
        AlertDialog(
            onDismissRequest = { missing = null },
            text = { Text(if (hasStore) "${a.label} 앱이 설치되어 있지 않아요. Play 스토어에서 볼까요?" else "${a.label} 앱이 설치되어 있지 않아요.") },
            confirmButton = {
                if (hasStore) TextButton(onClick = { missing = null; Links.openStore(context, a.pkg) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Play 스토어 열기") }
                else TextButton(onClick = { missing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
            },
            dismissButton = { if (hasStore) TextButton(onClick = { missing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
        )
    }
}

@Composable
private fun AppIcon(pkg: String, size: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    val bmp by produceState(StoreRepo.icon(context, pkg, px)?.asImageBitmap(), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { StoreRepo.icon(context, pkg, px)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = modifier.size(size.dp))
    else Box(modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun StoreChip(a: StoreApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val installed = remember(a.pkg) { Links.isInstalled(context, a.pkg) }
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 10.dp)
            .semantics { contentDescription = a.label + if (installed) "" else ", 설치 안 됨" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(a.pkg, 28, Modifier.alpha(if (installed) 1f else 0.4f))
        Text(a.label, fontSize = 14.sp, maxLines = 1, color = if (installed) cs.onSurface else cs.onSurfaceVariant)
    }
}

@Composable
private fun DashedChip(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, cs.outline), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, color = cs.onSurfaceVariant) }
}

/** S-15 서점 앱 바로가기 편집: 순서 바꾸기(▲▼)·빼기·기기의 런처 앱에서 더하기(이름 검색). */
@Composable
fun StoreEditScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.ash
    val scope = rememberCoroutineScope()
    val apps by remember { app.db.books().storeApps() }.collectAsState(initial = emptyList())
    var picking by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("서점 바로가기", onBack = onBack)
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.pkg }) { a ->
                val installed = remember(a.pkg) { Links.isInstalled(context, a.pkg) }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.pkg, 40, Modifier.alpha(if (installed) 1f else 0.4f))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(a.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (installed) a.pkg else "설치 안 됨", fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val i = apps.indexOf(a)
                    IconButton(onClick = { scope.launch { app.stores.move(a.pkg, -1) } }, enabled = i > 0, modifier = Modifier.size(48.dp)) { Icon(Icons.Up, "위로") }
                    IconButton(onClick = { scope.launch { app.stores.move(a.pkg, 1) } }, enabled = i < apps.size - 1, modifier = Modifier.size(48.dp)) { Icon(Icons.Down, "아래로") }
                    TextButton(onClick = { scope.launch { app.stores.remove(a.pkg) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("빼기") }
                }
                HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            item {
                Button(onClick = { picking = true }, modifier = Modifier.padding(20.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("+ 앱 추가") }
                Text("구매한 책은 각 서점 앱에서 읽어요. ASH Reader는 서점 앱의 책을 열지 않습니다.", fontSize = 13.sp, lineHeight = 19.sp,
                    color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
    if (picking) AppPickerDialog(exclude = apps.map { it.pkg }.toSet(), onPick = { p -> picking = false; scope.launch { app.stores.add(p.pkg, p.label) } }, onDismiss = { picking = false })
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onPick: (StoreRepo.LauncherApp) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all by produceState<List<StoreRepo.LauncherApp>?>(null) { value = context.ash.stores.launcherApps() }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("서점 앱 고르기") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("앱 이름으로 찾기") }, modifier = Modifier.fillMaxWidth())
                val list = all
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                else LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(list.filter { it.pkg !in exclude && (q.isBlank() || it.label.contains(q.trim(), ignoreCase = true)) }, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(a) }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.pkg, 36)
                            Text(a.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
}

// ───────────────────────────── S-14 문서 메뉴 ─────────────────────────────

/** S-14 문서 메뉴(길게 누르기): 상태(F-71)·즐겨찾기·처음부터·형광펜 내보내기·문서 정보·서재에서 빼기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookMenuSheet(b: Book, onDismiss: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit, onExport: () -> Unit, onInfo: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.ash
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val f = DocFormat.of(b.format)
    var info by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    // 시트 안에서 바꾼 값을 바로 보이게(DB 흐름이 늦게 와도)
    var status by remember(b.id) { mutableStateOf(b.status) }
    var fav by remember(b.id) { mutableStateOf(b.favorite) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BlurBehind(dim = 0f)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(b, Modifier.width(48.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(b.shownTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${f.badge} · ${sizeLabel(b.sizeBytes)} · ${(b.progress() * 100).toInt()}%", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))
            Text("상태", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Book.TO_READ to "읽을 책", Book.READING to "읽는 중", Book.DONE to "다 읽음").forEach { (st, label) ->
                    Pill(label, status == st) { status = st; scope.launch { app.db.books().setStatus(b.id, st) } }
                }
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { fav = !fav; scope.launch { app.db.books().setFavorite(b.id, fav) } }
                        .semantics { contentDescription = if (fav) "즐겨찾기 빼기" else "즐겨찾기" },
                    contentAlignment = Alignment.Center,
                ) { Text(if (fav) "★" else "☆", fontSize = 24.sp, color = if (fav) Ash.Gold else cs.onSurfaceVariant) }
            }
            MenuLine("열기", onClick = onOpen)
            // 작업 06: 책 정보(지은이·출판사·표지 찾기·고치기)
            if (onInfo != null) MenuLine("책 정보·표지 바꾸기", onClick = onInfo)
            MenuLine("처음부터 읽기") { scope.launch { app.db.books().restart(b.id); onOpen() } }
            // F-62 시리즈 직접 묶기·빼기
            if (SeriesGroups.keyOf(b) != null && b.seriesKey.isNotBlank()) MenuLine("시리즈에서 빼기") {
                app.appScope.launch { app.db.books().setSeries(b.id, b.seriesKey, Book.SERIES_EXCLUDED) }; onDismiss()
            }
            MenuLine("시리즈로 묶기…") { grouping = true }
            MenuLine("형광펜·메모 내보내기", onClick = onExport)
            MenuLine("문서 정보") { info = true }
            MenuLine("서재에서 빼기", color = MaterialTheme.colorScheme.error) { confirmRemove = true }
        }
    }
    if (grouping) GroupIntoSeries(b) { grouping = false; onDismiss() }
    if (info) BookWhereDialog(b) { info = false }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        text = { Text("서재에서만 뺍니다. 기기의 파일은 지우지 않아요.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("빼기") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

/** 시리즈로 묶기(F-62): 묶음 이름 고르기·넣기 → 같은 이름의 자동 묶음 권들도 직접 묶음으로. [onDone] = 닫힘(묶었든 취소했든). */
@Composable
fun GroupIntoSeries(b: Book, onDone: () -> Unit) {
    val app = LocalContext.current.ash
    val names by produceState(emptyList<String>(), b.id) {
        value = withContext(Dispatchers.IO) { SeriesGroups.groups(app.db.books().visibleBooks()).keys.map { SeriesGroups.nameOf(it) }.distinct().sorted() }
    }
    GroupIntoSeriesDialog(b, names, onDismiss = onDone) { name ->
        app.appScope.launch {
            // 같은 이름의 자동 묶음이 있으면 그 권들도 직접 묶음으로 옮겨 한 묶음이 되게
            val all = app.db.books().visibleBooks()
            all.filter { it.id != b.id && it.seriesManual == Book.SERIES_AUTO && it.seriesKey == name && SeriesGroups.keyOf(it)?.let { k -> SeriesGroups.groups(all).containsKey(k) } == true }
                .forEach { app.db.books().setSeries(it.id, name, Book.SERIES_MANUAL) }
            app.db.books().setSeries(b.id, name, Book.SERIES_MANUAL)
        }
        onDone()
    }
}

/** 파일 위치·문서 정보(위치·크기·실제 형식·쪽 수·추가한 날·읽은 시간·형광펜). */
@Composable
fun BookWhereDialog(b: Book, onDismiss: () -> Unit) {
    val app = LocalContext.current.ash
    val f = DocFormat.of(b.format)
    val time by produceState<Long?>(null, b.id) { value = withContext(Dispatchers.IO) { app.db.books().allSessions().filter { it.bookId == b.id }.sumOf { it.durationMs } } }
    val hl by produceState<Int?>(null, b.id) { value = withContext(Dispatchers.IO) { app.db.books().allHighlights().count { it.bookId == b.id } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("파일 위치·문서 정보") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("위치", when (b.origin) {
                    Book.ORIGIN_FILE -> b.path
                    Book.ORIGIN_SAF -> b.folder.ifEmpty { "직접 고른 폴더" }
                    else -> "앱 안 사본 (외부에서 가져옴)"
                })
                InfoRow("크기", sizeLabel(b.sizeBytes))
                InfoRow("실제 형식", f.badge)
                InfoRow("쪽 수", if (b.pageCount > 0) "${b.pageCount}쪽 (보기 설정에 따라 달라짐)" else "아직 모름")
                InfoRow("추가한 날", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date(b.addedAt)))
                InfoRow("읽은 시간", time?.let { formatDuration(it) } ?: "…")
                InfoRow("형광펜", hl?.let { "${it}개" } ?: "…")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
}

@Composable
private fun InfoRow(k: String, v: String) = Row {
    Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
    Text(v, fontSize = 14.sp)
}

@Composable
private fun MenuLine(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = 16.sp, color = color)
    }
}

fun sizeLabel(b: Long): String = when {
    b >= (1L shl 30) -> "%.1fGB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1fMB".format(b / 1048576.0)
    else -> "${(b / 1024).coerceAtLeast(1)}KB"
}

// ───────────────────────────── S-54 형광펜·메모 내보내기 ─────────────────────────────

/**
 * S-54: 형식 (.txt)(.md) / 범위 (이 책)(모든 책) / [파일로 저장](SAF) [공유하기](글 그대로 ACTION_SEND).
 * [bookId] 가 null 이면 '모든 책'만.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(bookId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.ash
    var fmt by remember { mutableStateOf(NotesExport.Fmt.MD) }
    var all by remember { mutableStateOf(bookId == null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val data by produceState<Pair<String?, List<NotesExport.BookNotes>>?>(null, all) {
        value = withContext(Dispatchers.IO) {
            val dao = app.db.books()
            val books = dao.allBooks().filter { it.hidden == 0 || it.id == bookId }.associateBy { it.id }
            val hl = dao.allHighlights().groupBy { it.bookId }
            val nt = dao.allNotes().groupBy { it.bookId }
            val ids = if (all || bookId == null) (hl.keys + nt.keys).filter { it in books } else listOf(bookId)
            val list = ids.mapNotNull { id ->
                val b = books[id] ?: return@mapNotNull null
                NotesExport.BookNotes(b.title, DocFormat.of(b.format).badge, hl[id].orEmpty(), nt[id].orEmpty())
            }.sortedBy { it.title }
            (if (all || bookId == null) null else books[bookId]?.title) to list
        }
    }
    fun text(): String? = data?.let { NotesExport.render(it.second, fmt) }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NotesExport.Fmt.TXT.mime)) { u -> u?.let { save(context, it, text()) { m -> msg = m } } }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NotesExport.Fmt.MD.mime)) { u -> u?.let { save(context, it, text()) { m -> msg = m } } }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BlurBehind(dim = 0f)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("형광펜·메모 내보내기", fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = cs.primary)
            Text("형식", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesExport.Fmt.entries.forEach { f -> Pill(f.label, fmt == f) { fmt = f } }
            }
            if (bookId != null) {
                Text("범위", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("이 책", !all) { all = false }
                    Pill("모든 책", all) { all = true }
                }
            }
            val d = data
            Text(
                if (d == null) "모으는 중…" else "책 ${d.second.count { it.highlights.isNotEmpty() || it.notes.isNotEmpty() }}권 · 형광펜·메모 ${NotesExport.count(d.second)}개",
                fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            val empty = d != null && NotesExport.count(d.second) == 0
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { val name = NotesExport.fileName(d?.first, fmt); if (fmt == NotesExport.Fmt.TXT) saveTxt.launch(name) else saveMd.launch(name) },
                    enabled = d != null && !empty, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("파일로 저장") }
                OutlinedButton(
                    onClick = {
                        val t = text() ?: return@OutlinedButton
                        if (t.length > 200_000) { msg = "내용이 너무 길어 공유할 수 없어요. 파일로 저장해 주세요."; return@OutlinedButton }
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                            .putExtra(Intent.EXTRA_SUBJECT, NotesExport.fileName(d?.first, fmt).substringBeforeLast('.'))
                        try { context.startActivity(Intent.createChooser(i, "형광펜·메모 공유")) } catch (_: ActivityNotFoundException) { msg = "공유할 앱이 없어요." }
                    },
                    enabled = d != null && !empty, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("공유하기") }
            }
            if (empty) Text("내보낼 형광펜·메모가 아직 없어요.", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
    msg?.let { AlertDialog(onDismissRequest = { msg = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { msg = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } }) }
}

private fun save(context: android.content.Context, uri: Uri, text: String?, done: (String) -> Unit) {
    if (text == null) return
    val ok = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("열 수 없음")
    }.isSuccess
    done(if (ok) "저장했어요." else "저장하지 못했어요.")
}

// ───────────────────────────── S-46 압축 파일 안 고르기 ─────────────────────────────

/** 바텀시트: "압축 파일 안에 읽을 수 있는 파일이 N개 있어요" + 목록(배지 + 이름) → 누르면 열기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipPickSheet(title: String, entries: List<kr.vici.ashreader.data.LibraryRepo.ZipItem>, onPick: (kr.vici.ashreader.data.LibraryRepo.ZipItem) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BlurBehind(dim = 0f)
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(title, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Text("압축 파일 안에 읽을 수 있는 파일이 ${entries.size}개 있어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(entries, key = { it.name }) { it2 ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(it2) }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Badge(it2.format)
                        Column(Modifier.weight(1f)) {
                            Text(it2.name.substringAfterLast('/'), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val folder = it2.name.substringBeforeLast('/', "")
                            if (folder.isNotEmpty()) Text(folder, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(sizeLabel(it2.size), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

