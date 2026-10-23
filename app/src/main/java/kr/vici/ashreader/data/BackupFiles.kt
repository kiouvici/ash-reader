// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 백업 파일 이름·머리 읽기(S-53 백업 가져오기). 안드로이드 클래스를 쓰지 않는 부분 — 단위 시험.
 *
 * 이름(M-11): `ASH백업_YYYY-MM-DD.ashbak`, 같은 날 두 번째부터 `ASH백업_YYYY-MM-DD_2.ashbak`.
 * 예전 판 이름 `ASH-백업-YYYYMMDD-HHMM.json`(그리고 확장자만 .ashbak 인 것)도 목록에 보인다.
 */
object BackupNames {
    const val EXT = "ashbak"
    private val NEW = Regex("""^ASH백업_(\d{4})-(\d{2})-(\d{2})(?:_(\d{1,4}))?\.ashbak$""", RegexOption.IGNORE_CASE)
    private val OLD = Regex("""^ASH-백업-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\.(json|ashbak)$""", RegexOption.IGNORE_CASE)

    /** 백업 파일로 보일 이름인가(다른 파일은 숨긴다). */
    fun isBackupName(name: String): Boolean = NEW.matches(name) || OLD.matches(name) || name.endsWith(".$EXT", ignoreCase = true)

    /** 오늘 날짜로 새 백업 이름. 같은 이름이 [existing]에 있으면 _2, _3 … */
    fun nextName(year: Int, month: Int, day: Int, existing: Collection<String>): String {
        val d = "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month, day)
        val taken = existing.map { it.lowercase() }.toHashSet()
        var n = 1
        while (true) {
            val name = if (n == 1) "ASH백업_$d.$EXT" else "ASH백업_${d}_$n.$EXT"
            if (name.lowercase() !in taken) return name
            n++
        }
    }

    /**
     * 이름에 든 날짜(정렬용, 시간대 [cal] 기준 ms). 새 이름은 그날 0시 + 번호(같은 날 뒤 번호가 더 최근), 옛 이름은 시각까지. 없으면 null.
     */
    fun timeOf(name: String, cal: Calendar = Calendar.getInstance()): Long? {
        NEW.matchEntire(name)?.let { m ->
            val (y, mo, d) = m.destructured
            val n = m.groupValues[4].toIntOrNull() ?: 1
            cal.clear(); cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), 0, 0, 0)
            return cal.timeInMillis + n
        }
        OLD.matchEntire(name)?.let { m ->
            val g = m.groupValues
            cal.clear(); cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), 0)
            return cal.timeInMillis
        }
        return null
    }
}

/** 백업 머리(파일 앞 몇 KB 만 읽어 낸다). [memos] = 메모 + 메모 붙은 형광펜. 요약이 없는 옛 백업은 -1. */
data class BackupHeader(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val books: Int = -1,
    val bookmarks: Int = -1,
    val memos: Int = -1,
) {
    val hasSummary: Boolean get() = books >= 0

    companion object {
        /** 머리로 읽을 앞부분 크기. */
        const val PREFIX_BYTES = 4096

        private val FORMAT = Regex(""""format"\s*:\s*"([^"]*)"""")
        private val VERSION = Regex(""""version"\s*:\s*(\d+)""")
        private val AT = Regex(""""exportedAt"\s*:\s*(\d+)""")
        private val SUMMARY = Regex(""""summary"\s*:\s*\{([^{}]*)\}""")
        private fun num(src: String, key: String) = Regex(""""$key"\s*:\s*(\d+)""").find(src)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        /**
         * 앞부분 글 [prefix]에서 머리를 읽는다. 첫 배열('[') 앞까지만 본다(책 목록 안의 글자에 속지 않게).
         * JSON 이 아니거나 format 이 없으면 null.
         */
        fun parse(prefix: String): BackupHeader? {
            val t = prefix.trimStart('﻿', ' ', '\n', '\r', '\t')
            if (!t.startsWith("{")) return null
            val head = t.substringBefore('[')
            val format = FORMAT.find(head)?.groupValues?.get(1) ?: return null
            val version = VERSION.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val at = AT.find(head)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val sum = SUMMARY.find(head)?.groupValues?.get(1)
            return if (sum == null) BackupHeader(format, version, at)
            else BackupHeader(format, version, at, num(sum, "books"), num(sum, "bookmarks"), num(sum, "memos"))
        }
    }
}

/** 목록 한 줄. [status]: OK / 백업 아님·손상 / 새 판. [location] = 읽을 주소(content:// 또는 file://). */
data class BackupItem(
    val name: String,
    val location: String,
    val size: Long,
    val modified: Long,
    val header: BackupHeader?,
    val status: Status,
) {
    enum class Status { OK, NOT_BACKUP, TOO_NEW }

    /** 최신 순 정렬 기준: 백업 시각 → 이름의 날짜 → 파일 수정 시각. */
    val sortTime: Long get() = header?.exportedAt?.takeIf { it > 0 } ?: BackupNames.timeOf(name) ?: modified

    companion object {
        fun statusOf(h: BackupHeader?, maxVersion: Int): Status = when {
            h == null || h.format != Backup.FORMAT -> Status.NOT_BACKUP
            h.version > maxVersion -> Status.TOO_NEW
            else -> Status.OK
        }

        /** 최신 순(같으면 이름 거꾸로). */
        fun newestFirst(list: List<BackupItem>): List<BackupItem> =
            list.sortedWith(compareByDescending<BackupItem> { it.sortTime }.thenByDescending { it.name })
    }
}

/**
 * 백업 폴더(S-53): 마지막으로 고른 폴더를 기기에만 기억한다(백업에는 넣지 않음 — 기기마다 다른 주소).
 * 폴더는 SAF 폴더 주소(content://…/tree/…) 또는 '모든 파일 접근'이 있을 때의 경로.
 */
class BackupFolder(private val context: Context) {
    private val sp get() = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** 기억한 폴더. 없으면 null. */
    var folder: String?
        get() = sp.getString("folder", null)
        set(v) { sp.edit().putString("folder", v).apply() }

    /** 기본으로 권할 폴더: Download/ASH Reader 백업. */
    fun defaultDir(): File = File(StorageAccess.downloadDir(), DEFAULT_NAME)

    /** 지금 찾을 폴더(기억한 것, 없으면 '모든 파일 접근'이 있을 때만 기본 폴더 경로). 읽을 수 없으면 null. */
    fun current(): String? = folder ?: defaultDir().takeIf { StorageAccess.hasFileAccess(context) }?.path

    /** 화면에 보일 폴더 이름("Download/ASH Reader 백업"). */
    fun labelOf(key: String): String = StorageAccess.labelOf(key)

    /** SAF 폴더 고르기 처음 위치(기억한 폴더 → Download/ASH Reader 백업). */
    fun initialUri(): Uri = folder?.takeIf { !it.startsWith("/") }?.let { k ->
        runCatching { val u = Uri.parse(k); DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u)) }.getOrNull()
    } ?: DocumentsContract.buildDocumentUri(StorageAccess.EXTERNAL_STORAGE_AUTHORITY, "primary:Download/$DEFAULT_NAME")

    /** 폴더 안 백업 파일(이름으로 거름, 머리 읽기). 폴더를 읽을 수 없으면 null. 백그라운드에서. */
    suspend fun list(key: String): List<BackupItem>? = withContext(Dispatchers.IO) {
        val raw = ArrayList<Triple<String, String, Pair<Long, Long>>>() // 이름, 주소, (크기, 수정 시각)
        if (key.startsWith("/")) {
            val dir = File(key)
            if (!dir.isDirectory) return@withContext if (dir.exists()) null else emptyList()
            dir.listFiles()?.filter { it.isFile && BackupNames.isBackupName(it.name) }?.forEach {
                raw += Triple(it.name, Uri.fromFile(it).toString(), it.length() to it.lastModified())
            }
        } else {
            val ok = runCatching {
                val tree = Uri.parse(key)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR || !BackupNames.isBackupName(name)) continue
                        val u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        raw += Triple(name, u.toString(), (if (c.isNull(3)) 0L else c.getLong(3)) to (if (c.isNull(4)) 0L else c.getLong(4)))
                    }
                } ?: error("목록 없음")
            }.isSuccess
            if (!ok) return@withContext null
        }
        BackupItem.newestFirst(raw.take(MAX_FILES).map { (name, loc, sm) ->
            val h = header(Uri.parse(loc), sm.first)
            BackupItem(name, loc, sm.first, sm.second, h, BackupItem.statusOf(h, Backup.VERSION))
        })
    }

    /** 이름들(새 백업 이름 _2 붙이기용). */
    suspend fun names(key: String): List<String> = list(key)?.map { it.name }.orEmpty()

    /**
     * 앞 4KB 만 읽어 머리를 낸다. 요약이 없는 옛 백업은 크기가 작으면(8MB 이하) 한 번 다 읽어 센다.
     */
    private fun header(u: Uri, size: Long): BackupHeader? = runCatching {
        val prefix = context.contentResolver.openInputStream(u)?.use { i ->
            val buf = ByteArray(BackupHeader.PREFIX_BYTES)
            var n = 0
            while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        } ?: return null
        val h = BackupHeader.parse(prefix) ?: return null
        if (h.hasSummary || h.format != Backup.FORMAT || size > OLD_COUNT_MAX) h else countOld(u, h)
    }.getOrNull()

    private fun countOld(u: Uri, h: BackupHeader): BackupHeader = runCatching {
        val text = context.contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return h
        val o = org.json.JSONObject(text)
        val hl = o.optJSONArray("highlights")
        var hlMemo = 0
        if (hl != null) for (i in 0 until hl.length()) if (hl.optJSONObject(i)?.optString("note").orEmpty().isNotBlank()) hlMemo++
        h.copy(
            books = o.optJSONArray("books")?.length() ?: 0,
            bookmarks = o.optJSONArray("bookmarks")?.length() ?: 0,
            memos = (o.optJSONArray("notes")?.length() ?: 0) + hlMemo,
        )
    }.getOrDefault(h)

    companion object {
        const val DEFAULT_NAME = "ASH Reader 백업"
        private const val MAX_FILES = 500
        private const val OLD_COUNT_MAX = 8L shl 20
    }
}
