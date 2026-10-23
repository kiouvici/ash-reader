// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Backup
import kr.vici.ashreader.data.BackupFolder
import kr.vici.ashreader.data.BackupItem

/** 백업 파일 만들기(SAF): 처음 위치를 기억한 백업 폴더로, 확장자 .ashbak 를 지키도록 일반 이진 MIME. */
private class CreateBackup(private val initial: () -> Uri?) : ActivityResultContracts.CreateDocument(Backup.FILE_MIME) {
    override fun createIntent(context: android.content.Context, input: String): Intent =
        super.createIntent(context, input).also { i -> initial()?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}

/**
 * 백업 내보내기(S-53 '파일로 내보내기'): 이름은 M-11 `ASH백업_YYYY-MM-DD.ashbak`, 같은 날 기억한 폴더에 이미 있으면 `_2`.
 * 돌려주는 함수를 부르면 저장 위치 고르기가 열린다. [onResult] = 결과 문구.
 */
@Composable
fun rememberBackupExporter(onBusy: (Boolean) -> Unit, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = remember { BackupFolder(context.applicationContext) }
    val backup = remember { context.ash.let { Backup(context.applicationContext, it.db, it.prefs) } }
    val launcher = rememberLauncherForActivityResult(CreateBackup { folder.initialUri() }) { uri ->
        if (uri != null) scope.launch {
            onBusy(true)
            onResult(runCatching { backup.export(uri) }.fold(
                { r -> "내보냈어요. 책 ${r.books}권 · 책갈피 ${r.bookmarks}개 · 메모 ${r.notes}개 · 형광펜 ${r.highlights}개 · 독서 기록 ${r.sessions}개와 설정을 담았어요. (책 파일은 들어가지 않아요)" },
                { e -> "내보내지 못했어요. ${e.message.orEmpty()}" },
            ))
            onBusy(false)
        }
    }
    return {
        scope.launch {
            val names = folder.folder?.let { folder.names(it) }.orEmpty()
            runCatching { launcher.launch(Backup.fileName(names)) }.onFailure { onResult("저장 위치 고르기를 열 수 없어요.") }
        }
    }
}

private val DATE = java.text.SimpleDateFormat("yyyy년 M월 d일 a h:mm", java.util.Locale.KOREA)
private val DAY = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.KOREA)

private fun dateOf(it: BackupItem): String {
    val at = it.header?.exportedAt?.takeIf { t -> t > 0 }
    if (at != null) return DATE.format(java.util.Date(at))
    val byName = kr.vici.ashreader.data.BackupNames.timeOf(it.name)
    return if (byName != null) DAY.format(java.util.Date(byName)) else DATE.format(java.util.Date(it.modified))
}

private fun sizeOf(b: Long): String = when {
    b >= 1L shl 20 -> "%.1fMB".format(java.util.Locale.ROOT, b / 1048576.0)
    b >= 1024 -> "${b / 1024}KB"
    else -> "${b}B"
}

private fun whatOf(it: BackupItem): String = when (it.status) {
    BackupItem.Status.NOT_BACKUP -> "ASH Reader 백업 파일이 아니에요."
    BackupItem.Status.TOO_NEW -> "더 새 판에서 만든 백업이에요. 앱을 업데이트한 뒤 가져와 주세요."
    BackupItem.Status.OK -> it.header?.takeIf { h -> h.hasSummary }?.let { h -> "책 ${h.books}권 · 책갈피 ${h.bookmarks} · 메모 ${h.memos}" } ?: "내용 요약 없음(예전 백업)"
}

/**
 * S-53 백업 가져오기('백업 파일 고르기'를 바꿈): 폴더를 고르면(마지막 폴더 기억, 기본 권장 Download/ASH Reader 백업)
 * 그 폴더의 백업 파일만 최신 순으로 날짜·내용 요약(책·책갈피·메모)·파일 이름·크기와 함께 보여 주고,
 * 가장 최근 것에 '가장 최근 백업' 표시. 가져오기 방식은 합치기(기본) / 모두 바꾸기(빨간 확인).
 */
@Composable
fun BackupImportScreen(onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.ash
    val scope = rememberCoroutineScope()
    val store = remember { BackupFolder(context.applicationContext) }
    val backup = remember { Backup(context.applicationContext, app.db, app.prefs) }
    var folder by remember { mutableStateOf(store.current()) }
    var tick by remember { mutableIntStateOf(0) }
    // null = 읽는 중, Unreadable = 폴더를 읽을 수 없음(권한 풀림·지워짐)
    val listing by produceState<Listing?>(null, folder, tick) {
        value = null
        val f = folder
        value = if (f == null) Listing.Ok(emptyList()) else store.list(f)?.let { Listing.Ok(it) } ?: Listing.Unreadable
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = (listing as? Listing.Ok)?.items
    val firstOk = list?.firstOrNull { it.status == BackupItem.Status.OK }
    val chosen = list?.firstOrNull { it.location == selected && it.status == BackupItem.Status.OK } ?: firstOk

    val pickFolder = rememberLauncherForActivityResult(PickTree()) { u ->
        if (u != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val old = store.folder
            store.folder = u.toString()
            folder = u.toString(); selected = null; tick++
            // 예전 백업 폴더 권한은 서재 폴더로 쓰지 않으면 놓는다
            if (old != null && old != u.toString() && !old.startsWith("/")) scope.launch {
                if (app.db.books().scanFolder(old) == null) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }
    fun changeFolder() { runCatching { pickFolder.launch(store.initialUri()) } }

    fun runImport() {
        val c = chosen ?: return
        busy = true
        scope.launch {
            runCatching { backup.import(Uri.parse(c.location), replace) }.fold(
                { r ->
                    busy = false
                    onDone(buildString {
                        append(if (replace) "백업 내용으로 바꿨어요. " else "가져와서 합쳤어요. ")
                        append("책갈피 ${r.bookmarks}개 · 메모 ${r.notes}개 · 형광펜 ${r.highlights}개 · 독서 기록 ${r.sessions}개, 읽은 위치 ${r.booksUpdated}권")
                        if (r.settings) append(", 설정")
                        append(".")
                        if (r.booksMissing > 0) append(" 이 기기에 파일이 없는 책 ${r.booksMissing}권은 서재에 넣지 않았어요. 같은 파일을 서재에 넣으면 책갈피·메모·형광펜이 다시 붙어요.")
                    })
                },
                { e -> busy = false; error = "가져오지 못했어요. ${e.message.orEmpty()}" },
            )
        }
    }

    val cs = MaterialTheme.colorScheme
    val content: LazyListScope.() -> Unit = {
        item { ScreenTitle("백업 가져오기", onBack = onBack) }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer)
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Folder, null, Modifier.size(22.dp), tint = cs.primary)
                Column(Modifier.weight(1f)) {
                    Text("찾는 폴더", fontSize = 14.sp, color = cs.onSurfaceVariant)
                    MiddleEllipsisText(folder?.let { store.labelOf(it) } ?: "Download/${BackupFolder.DEFAULT_NAME} (아직 고르지 않음)", 15.sp, cs.onSurface, Modifier.fillMaxWidth())
                }
                TextButton(onClick = ::changeFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (folder == null) "폴더 고르기" else "폴더 바꾸기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (listing == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        when {
            listing == Listing.Unreadable -> item { EmptyNote("폴더를 읽을 수 없어요. 폴더를 다시 골라 주세요.", "폴더 바꾸기", ::changeFolder) }
            folder == null -> item {
                EmptyNote("백업 파일이 든 폴더를 골라 주세요. 내보낼 때 'Download/${BackupFolder.DEFAULT_NAME}' 폴더를 쓰면 찾기 쉬워요.", "폴더 고르기", ::changeFolder)
            }
            list != null && list.isEmpty() -> item { EmptyNote("이 폴더에는 백업 파일이 없어요", "폴더 바꾸기", ::changeFolder) }
            list != null -> {
                item {
                    Text("이 폴더의 백업 파일 ${list.size}개 · 최신 순", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
                }
                items(list, key = { it.location }) { b ->
                    FileCard(b, selected = chosen?.location == b.location, newest = b.location == firstOk?.location) { selected = b.location }
                }
                item {
                    Text("백업 파일(.ashbak)만 보여요. 다른 파일은 숨겨요.", fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        }
    }
    val bottom: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxWidth().background(cs.surfaceContainer).padding(16.dp)) {
            HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                WayCard("합치기", "지금 기록은 두고 더하기", !replace, Modifier.weight(1f)) { replace = false }
                WayCard("모두 바꾸기", "지금 기록을 백업으로 바꾸기", replace, Modifier.weight(1f)) { replace = true }
            }
            Button(
                onClick = { if (replace) confirm = true else runImport() }, enabled = chosen != null && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (busy) "가져오는 중…" else "가져오기", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (twoPane(maxWidth)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1.3f).fillMaxSize()) { content(); item { Spacer(Modifier.height(24.dp)) } }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 72.dp, end = 16.dp)) { bottom(Modifier.clip(RoundedCornerShape(16.dp))) }
            }
        } else Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) { content() }
            bottom(Modifier)
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text("지금 기록이 모두 사라지고 백업 내용으로 바뀌어요. 바꿀까요?", fontSize = 16.sp, lineHeight = 24.sp) },
        confirmButton = {
            Button(
                onClick = { confirm = false; runImport() }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
            ) { Text("모두 바꾸기") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
            confirmButton = { TextButton(onClick = { error = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } })
    }
}

private sealed interface Listing {
    data object Unreadable : Listing
    data class Ok(val items: List<BackupItem>) : Listing
}

@Composable
private fun EmptyNote(text: String, action: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 16.sp, lineHeight = 24.sp, color = cs.onSurface, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, fontSize = 15.sp) }
    }
}

@Composable
private fun FileCard(b: BackupItem, selected: Boolean, newest: Boolean, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ok = b.status == BackupItem.Status.OK
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(shape).background(cs.surfaceContainer)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .selectable(selected = selected, enabled = ok, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 10.dp).alpha(if (ok) 1f else 0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = ok, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dateOf(b), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (newest) Box(Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primary).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("가장 최근 백업", fontSize = 12.sp, color = cs.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Text(whatOf(b), fontSize = 14.sp, lineHeight = 20.sp, color = if (ok) cs.onSurface else cs.error, maxLines = 2)
            Text("${b.name} · ${sizeOf(b.size)}", fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WayCard(name: String, sub: String, on: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(if (on) cs.secondaryContainer else cs.surfaceContainer)
            .border(if (on) 2.dp else 1.dp, if (on) cs.primary else cs.outlineVariant, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 14.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, maxLines = 2)
    }
}
