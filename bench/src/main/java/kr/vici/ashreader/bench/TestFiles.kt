// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/** 시험 파일 하나. 앱 assets(공개 파일) 또는 사용자가 고른 폴더(SAF, 실무 파일). */
sealed class TestFile {
    abstract val name: String
    abstract val size: Long
    abstract val origin: String
    abstract fun open(context: Context): InputStream

    data class Asset(val path: String, override val size: Long) : TestFile() {
        override val name: String get() = path.removePrefix("${TestFiles.ASSET_ROOT}/")
        override val origin: String get() = "assets"
        override fun open(context: Context): InputStream = context.assets.open(path)
    }

    data class Document(val uri: Uri, override val name: String, override val size: Long) : TestFile() {
        override val origin: String get() = "폴더"
        override fun open(context: Context): InputStream =
            context.contentResolver.openInputStream(uri) ?: error("열 수 없음: $name")
    }
}

object TestFiles {
    const val ASSET_ROOT = "testfiles"
    private const val PREFS = "bench"
    private const val KEY_TREE = "tree_uri"

    fun listAssets(context: Context): List<TestFile> {
        val out = mutableListOf<TestFile>()
        fun walk(dir: String) {
            val children = runCatching { context.assets.list(dir) }.getOrNull().orEmpty()
            for (c in children) {
                val p = "$dir/$c"
                val sub = runCatching { context.assets.list(p) }.getOrNull().orEmpty()
                if (sub.isNotEmpty()) {
                    walk(p)
                } else {
                    val size = runCatching { context.assets.openFd(p).use { it.length } }
                        .getOrElse { runCatching { context.assets.open(p).use { s -> s.skip(Long.MAX_VALUE) } }.getOrDefault(-1L) }
                    out += TestFile.Asset(p, size)
                }
            }
        }
        walk(ASSET_ROOT)
        return out.sortedBy { it.name }
    }

    fun savedTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)
            ?.takeIf { uri -> context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }

    fun saveTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    /** 고른 폴더 아래 파일 목록 (하위 폴더 3단계까지). 읽기 전용, 원본 수정 없음(N-04). */
    fun listTree(context: Context, tree: Uri): List<TestFile> {
        val out = mutableListOf<TestFile>()
        val cr = context.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        fun walk(docId: String, prefix: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: id
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 3) walk(id, "$prefix$name/", depth + 1)
                    } else {
                        out += TestFile.Document(DocumentsContract.buildDocumentUriUsingTree(tree, id), prefix + name, size)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out.sortedBy { it.name }
    }
}
