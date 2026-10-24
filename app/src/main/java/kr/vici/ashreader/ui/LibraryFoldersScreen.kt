// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.FolderRules
import kr.vici.ashreader.data.ScanFolder
import kr.vici.ashreader.data.StorageAccess

/** 태블릿 가로 두 칸(S-51 과 같은 기준): 폭 840dp 이상이고 가로. */
@Composable
internal fun twoPane(maxWidth: Dp): Boolean = maxWidth >= 840.dp &&
    LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/**
 * 한 줄 글을 폭에 맞게 '가운데'를 줄여 보인다(폴더 경로 — 앞의 볼륨과 끝의 폴더 이름이 보이게, 360dp 휴대폰).
 * 폭 안에 들면 그대로.
 */
@Composable
internal fun MiddleEllipsisText(text: String, fontSize: TextUnit, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = fontSize, color = color)
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(text, maxPx, fontSize) {
            if (maxPx <= 0 || measurer.measure(text, style).size.width <= maxPx) text else {
                var lo = 5; var hi = text.length; var best = FolderRules.middleEllipsis(text, 5)
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val t = FolderRules.middleEllipsis(text, mid)
                    if (measurer.measure(t, style).size.width <= maxPx) { best = t; lo = mid + 1 } else hi = mid - 1
                }
                best
            }
        }
        Text(shown, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.semantics { contentDescription = text })
    }
}

/** 점선 테두리 버튼(+ 폴더 추가). */
@Composable
internal fun DashedButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val r = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer)
            .drawBehind {
                drawRoundRect(cs.primary, cornerRadius = CornerRadius(r), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = cs.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** SAF 폴더 고르기: 처음 위치를 줄 수 있게(EXTRA_INITIAL_URI). 읽기 권한을 오래 쥘 수 있게 요청한다. */
internal class PickTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

/** 한 줄 표시용(계산 결과). */
private class FolderRow(val f: ScanFolder, val name: String, val path: String, val count: Int, val coveredBy: String?)

/**
 * S-17 서재 폴더(F-01): 서재에 모을 폴더를 여러 개 고른다(SAF 폴더 고르기 + 오래 쥐는 읽기 권한).
 * - 폴더마다 '하위 폴더도 포함'(48dp 줄 전체를 눌러 켜고 끔).
 * - [빼기] → 스낵바 "서재에서만 뺐어요. 기기의 파일은 그대로예요. [되돌리기]". 되돌릴 시간이 지나면 권한을 놓는다.
 *   책의 읽은 위치·책갈피는 문서 식별(F-63)로 남아 있어 다시 더하면 이어진다.
 * - 찾을 수 없는 폴더(SD 카드 뺌·권한 풀림·지워짐)는 회색 줄 + [다시 고르기]. 그 안 책은 지우지 않고 '열 수 없음'.
 * - 이미 다른 폴더('하위 폴더도 포함')에 든 폴더는 더하지 않고 알린다(두 번 세지 않기).
 * [intro] = 처음 실행(S-02 다음)에서 옴: Download 바로 추가 + 아래 [서재로 가기].
 */
@Composable
fun LibraryFoldersScreen(intro: Boolean, onBack: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val app = context.ash
    val dao = app.db.books()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val all by remember { dao.scanFolders() }.collectAsState(initial = null)
    val books by remember { dao.recent() }.collectAsState(initial = emptyList())
    val busy by app.collector.busy.collectAsState()
    val bad by app.collector.unavailable.collectAsState()
    val hasAccess = remember(all) { StorageAccess.hasFileAccess(context) }
    val includes = all.orEmpty().filter { it.include }
    val excludes = all.orEmpty().filter { !it.include }
    LaunchedEffect(all) { if (all != null) app.collector.checkFolders() }

    val volumes = remember { HashMap<String, String>() }
    val volumeLabel = { v: String -> volumes.getOrPut(v.lowercase()) { StorageAccess.volumeLabel(context, v) } }
    // 폴더별 책 수·전체 책 수(겹친 폴더의 책은 한 번만)
    val rows by produceState<Pair<List<FolderRow>, Int>?>(null, all, books) {
        value = withContext(Dispatchers.Default) {
            val entries = includes.map { StorageAccess.entryOf(it) }
            val dirs = books.mapNotNull { app.collector.dirOf(it) }
            val (per, total) = FolderRules.counts(entries, dirs)
            val walk = FolderRules.walkRoots(entries).map { it.key }.toSet()
            includes.mapIndexed { i, f ->
                val e = entries[i]
                val cover = if (f.path in walk) null else entries.firstOrNull { o -> o.key != e.key && o.subfolders && FolderRules.within(o.canon, e.canon) }
                FolderRow(
                    f, nameOf(e.canon, f.label, volumeLabel), pathOf(e.canon, f.label, volumeLabel), per[i],
                    cover?.let { c -> nameOf(c.canon, c.name, volumeLabel) },
                )
            } to total
        }
    }

    fun addFolder(uri: Uri, replacing: ScanFolder? = null) {
        scope.launch {
            val key = uri.toString()
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val canon = StorageAccess.canonOf(key)
            val others = includes.filter { it.path != replacing?.path }.map { StorageAccess.entryOf(it) }
            val cover = FolderRules.coveringFolder(canon, others)
            if (cover != null) {
                if (others.none { it.key == key }) releaseLater(app, key, 0)
                val name = nameOf(cover.canon, cover.name, volumeLabel)
                snack.showSnackbar(if (FolderRules.same(cover.canon, canon)) "이미 고른 폴더예요." else "이미 '$name'에 들어 있는 폴더예요.")
                return@launch
            }
            val label = StorageAccess.labelOf(key)
            if (replacing != null) {
                dao.deleteScanFolder(replacing.path)
                if (replacing.path != key) releaseLater(app, replacing.path, 0)
                dao.upsertScanFolder(ScanFolder(key, true, label, replacing.addedAt, replacing.subfolders))
            } else dao.upsertScanFolder(ScanFolder(key, true, label))
            onScan()
        }
    }
    val pick = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) addFolder(u) }
    var repickFor by remember { mutableStateOf<ScanFolder?>(null) }
    val repick = rememberLauncherForActivityResult(PickTree()) { u -> val r = repickFor; repickFor = null; if (u != null) addFolder(u, r) }

    val download = remember { StorageAccess.downloadDir().path }
    val hasDownload = includes.any { FolderRules.same(StorageAccess.canonOf(it.path), StorageAccess.canonOf(download)) }
    fun addDownload() {
        if (hasAccess) scope.launch {
            val canon = StorageAccess.canonOf(download)
            val cover = FolderRules.coveringFolder(canon, includes.map { StorageAccess.entryOf(it) })
            if (cover != null) { snack.showSnackbar("이미 '${nameOf(cover.canon, cover.name, volumeLabel)}'에 들어 있는 폴더예요."); return@launch }
            dao.upsertScanFolder(ScanFolder(download, true, "Download"))
            onScan()
        } else pick.launch(StorageAccess.downloadInitialUri())
    }
    fun remove(f: ScanFolder) {
        scope.launch {
            dao.deleteScanFolder(f.path)
            onScan()
            // 되돌릴 시간(스낵바)이 지나도 다시 넣지 않았으면 권한을 놓는다 — 화면을 떠나도 앱 범위에서
            if (!f.path.startsWith("/")) releaseLater(app, f.path, 12_000L)
            val r = snack.showSnackbar("서재에서만 뺐어요. 기기의 파일은 그대로예요.", actionLabel = "되돌리기", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                dao.upsertScanFolder(f)
                onScan()
            }
        }
    }

    val cs = MaterialTheme.colorScheme
    val total = rows?.second
    val list: LazyListScope.() -> Unit = {
        item { ScreenTitle(if (intro) "서재 폴더 고르기" else "서재 폴더", onBack = onBack) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "폴더에서 책을 모으는 중" }) }
        item {
            Text(
                "고른 폴더 안의 책을 서재에 모아 보여 줘요. 폴더는 여러 개 고를 수 있어요.",
                fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (all != null && includes.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Folder, null, Modifier.size(48.dp), tint = cs.primary)
                Text("아직 고른 폴더가 없어요", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                Button(onClick = { pick.launch(StorageAccess.downloadInitialUri()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("폴더 추가", fontSize = 16.sp) }
            }
        }
        items(rows?.first.orEmpty(), key = { it.f.path }) { r ->
            FolderCard(
                r, unavailable = r.f.path in bad,
                onToggleSub = { v -> scope.launch { dao.setSubfolders(r.f.path, v); onScan() } },
                onRemove = { remove(r.f) },
                onRepick = { repickFor = r.f; launchRepick(repick, r.f) },
            )
        }
        if (includes.isNotEmpty()) item {
            DashedButton("+ 폴더 추가", onClick = { pick.launch(StorageAccess.downloadInitialUri()) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (!hasDownload) item {
            OutlinedButton(onClick = ::addDownload, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Download 폴더 바로 추가", fontSize = 15.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("'폴더 추가'를 누르면 기기의 폴더 고르기 화면이 열려요. 고른 폴더만 읽고, 다른 폴더는 보지 않아요.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant)
                if (Build.VERSION.SDK_INT >= 30 && !hasAccess) Text(
                    "Android 11부터는 Download 폴더 자체는 고를 수 없어요. 그 안의 폴더를 골라 주세요.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (excludes.isNotEmpty()) {
            // 옛 판에서 고른 '빼는 폴더'는 지금도 지킨다. 여기서 풀 수만 있다.
            item { Text("빼는 폴더", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp)) }
            items(excludes, key = { "x-" + it.path }) { f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { dao.deleteScanFolder(f.path); onScan() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("풀기") }
                }
                HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
    val summary: @Composable (Modifier) -> Unit = { m ->
        if (includes.isNotEmpty()) Row(
            m.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.primary).heightIn(min = 52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("서재에 있는 책", color = cs.onPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (total == null) "…" else "책 ${total}권", color = cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
    val goLibrary: @Composable (Modifier) -> Unit = { m ->
        if (intro) Button(onClick = onBack, modifier = m.fillMaxWidth().heightIn(min = 52.dp)) { Text("서재로 가기", fontSize = 16.sp) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (twoPane(maxWidth)) {
            // 태블릿 가로: 왼쪽 폴더 목록, 오른쪽 요약·안내
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1.3f).fillMaxSize()) { list(); item { Spacer(Modifier.height(24.dp)) } }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 72.dp, start = 8.dp, end = 24.dp)) {
                    summary(Modifier)
                    goLibrary(Modifier.padding(top = 16.dp))
                }
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            list()
            // '책 N권' 요약은 목록 끝에(목록을 가리지 않게)
            item { summary(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { goLibrary(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            item { Spacer(Modifier.height(72.dp)) }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

/** [다시 고르기]: 그 폴더 자리에서 폴더 고르기를 연다. */
private fun launchRepick(launcher: androidx.activity.result.ActivityResultLauncher<Uri?>, f: ScanFolder) {
    val initial = if (f.path.startsWith("/")) StorageAccess.downloadInitialUri() else runCatching {
        val u = Uri.parse(f.path)
        android.provider.DocumentsContract.buildDocumentUriUsingTree(u, android.provider.DocumentsContract.getTreeDocumentId(u))
    }.getOrNull()
    runCatching { launcher.launch(initial) }
}

/** 폴더 권한을 [afterMs] 뒤에 놓는다(그때도 서재 폴더 목록에 없을 때만). 앱 범위에서 돈다. */
private fun releaseLater(app: kr.vici.ashreader.AshApp, key: String, afterMs: Long) {
    if (key.startsWith("/")) return
    app.appScope.launch {
        if (afterMs > 0) delay(afterMs)
        if (app.db.books().scanFolder(key) == null) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(key), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}

private fun nameOf(canon: String, fallback: String, volumeLabel: (String) -> String): String =
    if (canon.startsWith("path:") || canon.startsWith("tree:")) fallback.substringAfterLast('/') else FolderRules.nameOf(canon, volumeLabel)

private fun pathOf(canon: String, fallback: String, volumeLabel: (String) -> String): String =
    if (canon.startsWith("path:") || canon.startsWith("tree:")) fallback else FolderRules.pathOf(canon, volumeLabel)

@Composable
private fun FolderCard(r: FolderRow, unavailable: Boolean, onToggleSub: (Boolean) -> Unit, onRemove: () -> Unit, onRepick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(shape)
            .background(if (unavailable) cs.surfaceVariant else cs.surfaceContainer).border(1.dp, cs.outlineVariant, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Folder, null, Modifier.size(26.dp), tint = if (unavailable) cs.onSurfaceVariant else cs.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 4.dp, bottom = 4.dp).alpha(if (unavailable) 0.75f else 1f)) {
                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MiddleEllipsisText(r.path, 14.sp, cs.onSurfaceVariant, Modifier.fillMaxWidth())
                Text(if (unavailable) "폴더를 찾을 수 없어요" else "책 ${r.count}권", fontSize = 14.sp,
                    fontWeight = if (unavailable) FontWeight.Medium else FontWeight.Normal, color = cs.onSurfaceVariant, maxLines = 2)
                r.coveredBy?.let { Text("'$it'에 함께 들어 있어요", fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 2) }
            }
            TextButton(
                onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).semantics { contentDescription = "${r.name} 폴더 빼기" },
            ) { Text("빼기", color = cs.secondary, fontSize = 15.sp) }
        }
        if (unavailable) {
            OutlinedButton(onClick = onRepick, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp).heightIn(min = 48.dp)) {
                Text("다시 고르기", fontSize = 15.sp)
            }
        } else {
            // 줄 전체(48dp)를 눌러 켜고 끈다
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = r.f.subfolders, role = Role.Checkbox, onValueChange = onToggleSub)
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(checked = r.f.subfolders, onCheckedChange = null, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
                Text("하위 폴더도 포함", fontSize = 15.sp, color = cs.primary)
            }
        }
    }
}
