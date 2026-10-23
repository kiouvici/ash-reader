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

    /** 내장 저장공간 맨 위 경로(/storage/emulated/0). */
    @Suppress("DEPRECATION")
    fun primaryRoot(): String = Environment.getExternalStorageDirectory().path

    /** 경로 → 정규 위치("primary:Download/소설", S-17 겹침 비교). 저장공간 밖이면 null. */
    fun canonOfPath(path: String): String? = FolderRules.canonicalOfPath(path, primaryRoot())

    /** 서재 폴더 key(경로 또는 SAF 주소) → 정규 위치. 외장 저장소 제공자가 아닌 폴더(클라우드 등)는 겹치지 않는 고유 값. */
    fun canonOf(key: String): String = if (key.startsWith("/")) canonOfPath(key) ?: "path:$key" else runCatching {
        val u = Uri.parse(key)
        if (u.authority == EXTERNAL_STORAGE_AUTHORITY) FolderRules.canonicalOfDocId(DocumentsContract.getTreeDocumentId(u)) else null
    }.getOrNull() ?: "tree:$key"

    fun entryOf(f: ScanFolder) = FolderRules.Entry(f.path, canonOf(f.path), f.subfolders, f.label)

    /**
     * 볼륨 이름: 내장 저장공간은 "내장 저장공간", SD 카드·USB 는 기기가 붙인 이름(예: "SanDisk SD 카드"), 모르면 "SD 카드".
     * [vol] = 정규 위치의 볼륨 부분("primary", "1A2B-3C4D").
     */
    fun volumeLabel(context: Context, vol: String): String {
        if (vol.equals("primary", ignoreCase = true)) return "내장 저장공간"
        if (vol == "path" || vol == "tree") return "다른 저장소"
        return runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes.firstOrNull { it.uuid?.equals(vol, ignoreCase = true) == true }?.getDescription(context)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "SD 카드"
    }

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
            if (id.substringBefore(':').equals("primary", true)) rel.ifEmpty { "내장 저장소" } else "SD 카드/" + rel
        }.getOrDefault(key)
    }
}

/**
 * 서재 수집(F-01): 사용자가 고른 서재 폴더(S-17, 여러 개)와 — '하위 폴더도 포함'이면 — 그 하위 폴더에서 지원 형식 파일을 찾아 서재에 넣는다.
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

    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())
    /** 찾을 수 없는 서재 폴더(DB path): SD 카드를 뺐거나, 권한이 풀렸거나, 폴더가 지워짐(S-17). 그 안 책은 지우지 않고 '열 수 없음'. */
    val unavailable: StateFlow<Set<String>> = _unavailable

    /** 폴더를 지금 읽을 수 있나. 경로는 디렉터리가 있어야 하고, SAF 폴더는 권한이 남아 있고 맨 위 문서를 물어볼 수 있어야 한다. */
    private fun available(f: ScanFolder, asPath: String?, fileOk: Boolean, persisted: Set<String>): Boolean {
        if (f.path.startsWith("/")) return fileOk && File(f.path).isDirectory
        if (fileOk && asPath != null && File(asPath).isDirectory) return true
        if (f.path !in persisted) return false
        return runCatching {
            val tree = Uri.parse(f.path)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** 폴더 상태만 다시 본다(서재 폴더 화면을 열 때·돌아올 때 — 훑기보다 가볍다). */
    suspend fun checkFolders(): Set<String> = withContext(Dispatchers.IO) {
        val fileOk = StorageAccess.hasFileAccess(context)
        val persisted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val bad = dao.allScanFolders().filter { it.include }.filter { f ->
            val asPath = if (f.path.startsWith("/")) f.path else StorageAccess.pathOfTree(Uri.parse(f.path))
            !available(f, asPath, fileOk, persisted)
        }.map { it.path }.toSet()
        _unavailable.value = bad
        bad
    }

    /** 책이 든 폴더(정규 위치). 폴더에서 모은 책이 아니면 null. */
    fun dirOf(b: Book): String? = when (b.origin) {
        Book.ORIGIN_FILE -> StorageAccess.canonOfPath(b.path)?.let { FolderRules.parentOf(it) }
        Book.ORIGIN_SAF -> runCatching { DocumentsContract.getDocumentId(Uri.parse(b.sourceUri)) }.getOrNull()
            ?.let { FolderRules.canonicalOfDocId(it) }?.let { FolderRules.parentOf(it) }
        else -> null
    }

    /** 찾을 수 없는 폴더 안의 책(서재에서 '열 수 없음'으로 보인다). */
    fun unavailableBooks(books: List<Book>, folders: List<ScanFolder>, bad: Set<String>): Set<String> {
        if (bad.isEmpty()) return emptySet()
        val badEntries = folders.filter { it.include && it.path in bad }.map { StorageAccess.entryOf(it) }
        val okEntries = folders.filter { it.include && it.path !in bad }.map { StorageAccess.entryOf(it) }
        return books.mapNotNullTo(HashSet()) { b ->
            val d = dirOf(b) ?: return@mapNotNullTo null
            if (badEntries.any { FolderRules.holds(it, d) } && okEntries.none { FolderRules.holds(it, d) }) b.id else null
        }
    }

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
        val includes = folders.filter { it.include }
        val bad = HashSet<String>()
        // S-17: 다른 폴더('하위 폴더도 포함')에 덮인 폴더는 따로 훑지 않는다(두 번 세지 않기)
        val roots = FolderRules.walkRoots(includes.map { StorageAccess.entryOf(it) }).map { it.key }.toSet()
        for (f in includes) {
            val asPath = if (f.path.startsWith("/")) f.path else StorageAccess.pathOfTree(Uri.parse(f.path))
            asPath?.let { includeRoots += it }
            if (!f.path.startsWith("/")) includeRoots += f.path
            if (!available(f, asPath, fileOk, persisted)) { bad += f.path; continue }
            if (f.path !in roots || found.size >= MAX_FILES) continue
            val maxDepth = if (f.subfolders) MAX_DEPTH else 0
            if (fileOk && asPath != null && File(asPath).isDirectory) {
                walkFile(File(asPath), f.label, 0, maxDepth, excludePaths, found)
                walkedPaths += asPath
                if (!f.path.startsWith("/")) walkedTrees += f.path
            } else if (!f.path.startsWith("/") && f.path in persisted) {
                val tree = Uri.parse(f.path)
                val ok = runCatching {
                    walkSaf(tree, DocumentsContract.getTreeDocumentId(tree), f.label, 0, maxDepth, excludeDocs, found)
                }.isSuccess
                if (ok) walkedTrees += f.path else bad += f.path
            }
        }
        _unavailable.value = bad
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
        // 사라진 문서 숨기기: 훑은 폴더 안이었는데 이번에 못 찾음, 또는 이제 어느 서재 폴더에도 속하지 않음.
        // 찾을 수 없는 폴더(SD 카드 뺌·권한 풀림) 안 문서는 훑지 않았으므로 그대로 둔다('열 수 없음').
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

    private fun walkFile(dir: File, label: String, depth: Int, maxDepth: Int, exclude: List<String>, out: MutableMap<String, Found>) {
        if (depth > maxDepth || out.size >= MAX_FILES) return
        if (exclude.any { under(dir.path, it) }) return
        val list = dir.listFiles() ?: return
        for (f in list.sortedBy { it.name }) {
            if (out.size >= MAX_FILES) return
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n == "Android") continue
                if (depth < maxDepth) walkFile(f, "$label/$n", depth + 1, maxDepth, exclude, out)
            } else if (f.isFile && n.substringAfterLast('.', "").lowercase() in FormatDetector.EXTENSIONS && f.length() > 0) {
                out[f.path] = Found(f.path, n, f.length(), f.lastModified(), label, saf = false)
            }
        }
    }

    private fun walkSaf(tree: Uri, docId: String, label: String, depth: Int, maxDepth: Int, exclude: List<String>, out: MutableMap<String, Found>) {
        if (depth > maxDepth || out.size >= MAX_FILES) return
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
                if (depth < maxDepth) walkSaf(tree, id, "$label/$name", depth + 1, maxDepth, exclude, out)
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
