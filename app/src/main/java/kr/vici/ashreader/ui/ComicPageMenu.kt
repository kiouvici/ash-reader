// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.Covers

/** SAF '파일로 저장': (제안 파일 이름, MIME) → 고른 곳. */
private class SaveImage : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun extOf(mime: String) = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "img" }

/**
 * F-59 쪽 길게 누르기 메뉴(만화): 서재 표지로 지정 / 그림 공유 / 그림을 파일로 저장(SAF).
 * 원본 쪽 그림 파일을 그대로 쓴다(PDF 만화는 PNG 로 만든다). [once] = 한 번만 보기 문서(표지 지정 불가).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicPageMenu(
    doc: ComicReaderDoc,
    page: Int,
    title: String,
    bookId: String,
    bookPath: String,
    once: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val img by produceState<Pair<ByteArray, String>?>(null, doc, page) {
        value = withContext(Dispatchers.IO) { runCatching { doc.pageImage(page) }.getOrNull() }
    }
    val baseName = (title.take(40).replace(Regex("[\\\\/:*?\"<>|]"), "_") + " ${page + 1}쪽")
    val saver = rememberLauncherForActivityResult(SaveImage()) { uri ->
        val data = img
        if (uri == null || data == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(data.first) } != null }.getOrDefault(false)
            }
            onMessage(if (ok) "그림을 저장했어요." else "그림을 저장하지 못했어요.")
            onDismiss()
        }
    }

    fun share() {
        val data = img ?: return
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    // 지난 공유 파일은 지운다(받는 앱이 다 읽었을 시간)
                    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000L) it.delete() }
                    val f = File(dir, "page-${page + 1}.${extOf(data.second)}")
                    f.writeBytes(data.first)
                    FileProvider.getUriForFile(context, context.packageName + ".files", f)
                }.getOrNull()
            }
            if (uri == null) { onMessage("그림을 공유하지 못했어요."); onDismiss(); return@launch }
            val send = Intent(Intent.ACTION_SEND).setType(data.second).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { context.startActivity(Intent.createChooser(send, "그림 공유")) } catch (_: ActivityNotFoundException) { onMessage("공유할 앱이 없어요.") }
            onDismiss()
        }
    }

    fun setCover() {
        if (once) { onMessage("서재에 추가하면 표지로 지정할 수 있어요."); onDismiss(); return }
        val data = img ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data.first, 0, data.first.size, o)
                    var s = 1
                    while (s < 64 && o.outWidth / (s * 2) >= Covers.WIDTH * 2) s *= 2
                    val b = BitmapFactory.decodeByteArray(data.first, 0, data.first.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@runCatching false
                    try { Covers.setCustom(context, bookId, File(bookPath), b) } finally { b.recycle() }
                    true
                }.getOrDefault(false)
            }
            onMessage(if (ok) "${page + 1}쪽을 서재 표지로 정했어요." else "표지로 정하지 못했어요.")
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text("${page + 1}쪽", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            val ready = img != null
            MenuRow(Icons.Bookmark, "서재 표지로 지정", if (once) "서재에 추가한 책만 지정할 수 있어요" else null, ready) { setCover() }
            MenuRow(Icons.Share, "그림 공유", null, ready) { share() }
            MenuRow(Icons.Save, "그림을 파일로 저장", null, ready) {
                val d = img ?: return@MenuRow
                saver.launch("$baseName.${extOf(d.second)}" to d.second)
            }
            if (!ready) Text("그림을 준비하는 중…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, sub: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, fontSize = 15.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
