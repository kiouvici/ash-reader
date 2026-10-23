// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 저장소 접근(F-01, 기술 설계서 7.2).
 * - Android 11+: '모든 파일 접근'(MANAGE_EXTERNAL_STORAGE, 사이드로드라 Play 정책 대상 아님). 거부하면 SAF 폴더 선택(하위 폴더 단위).
 * - Android 10: READ_EXTERNAL_STORAGE + requestLegacyExternalStorage. Android 8~9: READ_EXTERNAL_STORAGE.
 * 원본은 읽기만 한다(N-04).
 */
object StorageAccess {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 경로(java.io.File)로 공유 저장소를 읽을 수 있나. */
    fun hasFileAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+ '모든 파일 접근' 설정 화면(이 앱). 없으면 전체 목록 화면. */
    fun allFilesSettingsIntents(context: Context): List<Intent> = if (Build.VERSION.SDK_INT >= 30) listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ) else emptyList()

    fun appSettingsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

    @Suppress("DEPRECATION")
    fun downloadDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** SAF 폴더 고르기 처음 위치: Download (Android 11+ 는 Download 자체는 못 고르고 그 안 폴더만 고를 수 있다). */
    fun downloadInitialUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Download")

    /** SAF 폴더(트리) → 실제 경로(외장 저장소 제공자일 때만). "primary:Download/소설" → /storage/emulated/0/Download/소설 */
    @Suppress("DEPRECATION")
    fun pathOfTree(tree: Uri): String? = runCatching {
        if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().path else "/storage/$vol"
        if (rel.isEmpty()) base else "$base/$rel"
    }.getOrNull()

    /** 화면에 보일 폴더 이름. */
    fun labelOf(key: String): String {
        if (key.startsWith("/")) {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory().path
            return key.removePrefix(root).trim('/').ifEmpty { "내장 저장소" }
        }
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(key))
            val rel = id.substringAfter(':', "")
            if (id.substringBefore(':').equals("primary", true)) rel.ifEmpty { "내장 저장소" } else "SD카드/" + rel
        }.getOrDefault(key)
    }
}

/**
 * 서재 수집(F-01): 모으는 폴더(기본 Download)와 그 하위 폴더에서 지원 형식 파일을 찾아 서재에 넣는다.
 * - 경로 접근이 되면 java.io.File 로, 아니면 SAF(DocumentsContract 자식 질의)로 훑는다.
 * - 경로+크기+수정 시각이 같으면 해시를 다시 내지 않는다(증분). 옮겨지거나 이름이 바뀐 파일은 식별 해시(F-63)로 이어진다.
 * - 훑은 폴더 안에서 사라진 문서는 숨긴다(기록은 남김, 다시 찾으면 이어짐). '서재에서 빼기'한 문서는 다시 넣지 않는다.
 */
class Collector(private val context: Context, private val db: AshDb) {
    private val dao get() = db.books()
    private val mutex = Mutex()

    data class Result(val added: Int, val updated: Int, val missing: Int, val scannedFolders: Int)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private class Found(val key: String, val name: String, val size: Long, val mtime: Long, val folder: String, val saf: Boolean)

    suspend fun scan(): Result = mutex.withLock {
        _busy.value = true
        try { withContext(Dispatchers.IO) { scanLocked() } } finally { _busy.value = false }
    }

    private suspend fun scanLocked(): Result {
        val folders = dao.allScanFolders()
        val fileOk = StorageAccess.hasFileAccess(context)
        val persisted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val excludePaths = folders.filter { !it.include }.mapNotNull { f -> if (f.path.startsWith("/")) f.path else StorageAccess.pathOfTree(Uri.parse(f.path)) }
        val excludeDocs = folders.filter { !it.include && !it.path.startsWith("/") }.mapNotNull { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it.path)) }.getOrNull() }
        val found = LinkedHashMap<String, Found>()
        val walkedPaths = ArrayList<String>()
        val walkedTrees = ArrayList<String>()
        val includeRoots = ArrayList<String>() // 접근 못 해도 '이 폴더 문서'로 치는 뿌리
        for (f in folders.filter { it.include }) {
            if (found.size >= MAX_FILES) break
            val asPath = if (f.path.startsWith("/")) f.path else StorageAccess.pathOfTree(Uri.parse(f.path))
            asPath?.let { includeRoots += it }
            if (!f.path.startsWith("/")) includeRoots += f.path
            if (fileOk && asPath != null && File(asPath).isDirectory) {
                walkFile(File(asPath), f.label, 0, excludePaths, found)
                walkedPaths += asPath
                if (!f.path.startsWith("/")) walkedTrees += f.path
            } else if (!f.path.startsWith("/") && f.path in persisted) {
                val tree = Uri.parse(f.path)
                val ok = runCatching {
                    walkSaf(tree, DocumentsContract.getTreeDocumentId(tree), f.label, 0, excludeDocs, found)
                }.isSuccess
                if (ok) walkedTrees += f.path
            }
        }
        var added = 0; var updated = 0; var missing = 0
        val known = dao.collected()
        val byKey = HashMap<String, Book>()
        for (b in known) byKey[if (b.origin == Book.ORIGIN_SAF) b.sourceUri else b.path] = b
        val seen = HashSet<String>()
        for (x in found.values) {
            val old = byKey[x.key]
            if (old != null && old.sizeBytes == x.size && old.fileMtime == x.mtime) {
                seen += old.id
                if (old.hidden == Book.HIDDEN_MISSING) { dao.setHidden(old.id, 0); updated++ }
                continue
            }
            val info = runCatching { identify(x) }.getOrNull() ?: continue
            val (id, fmt) = info
            if (fmt == DocFormat.UNKNOWN) continue
            seen += id
            val cur = dao.get(id)
            val title = x.name.substringBeforeLast('.')
            val origin = if (x.saf) Book.ORIGIN_SAF else Book.ORIGIN_FILE
            if (cur == null) {
                val s = SeriesParser.parse(title)
                dao.upsert(
                    Book(
                        id = id, title = title, format = fmt.name, path = if (x.saf) "" else x.key, folder = x.folder,
                        sizeBytes = x.size, seriesKey = s?.first.orEmpty(), volume = s?.second ?: 0,
                        origin = origin, sourceUri = if (x.saf) x.key else "", fileMtime = x.mtime,
                    ),
                )
                added++
            } else if (cur.origin != Book.ORIGIN_COPY) {
                // 옮김·이름 바꿈(같은 내용) 또는 내용이 바뀌지 않은 재수집: 위치만 갱신, 읽은 기록은 그대로
                if (cur.hidden == Book.HIDDEN_REMOVED) continue
                dao.upsert(
                    cur.copy(
                        path = if (x.saf) "" else x.key, sourceUri = if (x.saf) x.key else "", folder = x.folder,
                        sizeBytes = x.size, fileMtime = x.mtime, origin = origin, hidden = 0, format = fmt.name,
                    ),
                )
                updated++
            }
        }
        // 사라진 문서 숨기기: 훑은 폴더 안이었는데 이번에 못 찾음, 또는 이제 어느 모으는 폴더에도 속하지 않음
        for (b in dao.collected()) {
            if (b.hidden != 0 || b.id in seen) continue
            val loc = if (b.origin == Book.ORIGIN_SAF) b.sourceUri else b.path
            val inWalked = walkedPaths.any { under(loc, it) } || walkedTrees.any { loc.startsWith("$it/document/") }
            val inAnyRoot = includeRoots.any { r -> under(loc, r) || loc.startsWith("$r/document/") }
            if (inWalked || !inAnyRoot) { dao.setHidden(b.id, Book.HIDDEN_MISSING); missing++ }
        }
        return Result(added, updated, missing, walkedPaths.size + walkedTrees.size)
    }

    private fun under(path: String, root: String) = path == root || path.startsWith(root.trimEnd('/') + "/")

    private fun walkFile(dir: File, label: String, depth: Int, exclude: List<String>, out: MutableMap<String, Found>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        if (exclude.any { under(dir.path, it) }) return
        val list = dir.listFiles() ?: return
        for (f in list.sortedBy { it.name }) {
            if (out.size >= MAX_FILES) return
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n == "Android") continue
                walkFile(f, "$label/$n", depth + 1, exclude, out)
            } else if (f.isFile && n.substringAfterLast('.', "").lowercase() in FormatDetector.EXTENSIONS && f.length() > 0) {
                out[f.path] = Found(f.path, n, f.length(), f.lastModified(), label, saf = false)
            }
        }
    }

    private fun walkSaf(tree: Uri, docId: String, label: String, depth: Int, exclude: List<String>, out: MutableMap<String, Found>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        if (depth > 0 && exclude.any { docId == it || docId.startsWith("$it/") }) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val rows = ArrayList<Array<Any?>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) rows += arrayOf<Any?>(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4))
        }
        for (r in rows.sortedBy { it[1] as? String ?: "" }) {
            if (out.size >= MAX_FILES) return
            val id = r[0] as? String ?: continue
            val name = r[1] as? String ?: continue
            if (name.startsWith(".")) continue
            if (r[2] == DocumentsContract.Document.MIME_TYPE_DIR) {
                walkSaf(tree, id, "$label/$name", depth + 1, exclude, out)
            } else if (name.substringAfterLast('.', "").lowercase() in FormatDetector.EXTENSIONS && (r[3] as Long) > 0) {
                val u = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                out[u] = Found(u, name, r[3] as Long, r[4] as Long, label, saf = true)
            }
        }
    }

    /** (식별 해시, 실제 형식). 파일을 머리·가운데·끝만 읽는다. */
    private fun identify(x: Found): Pair<String, DocFormat> {
        if (!x.saf) {
            val f = File(x.key)
            return DocHash.of(f) to FormatDetector.detect(f, x.name)
        }
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(x.key), "r") ?: error("열 수 없음")
        return pfd.use { p ->
            FileInputStream(p.fileDescriptor).use { s ->
                val src = ByteSource.of(s.channel)
                DocHash.of(src) to FormatDetector.detect(src, x.name)
            }
        }
    }

    companion object {
        const val MAX_DEPTH = 12
        const val MAX_FILES = 20_000
    }
}
