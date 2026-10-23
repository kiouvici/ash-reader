// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 백업·내보내기(S-53). 서재 정보·읽은 위치·책갈피·메모·형광펜·독서 기록·설정을 JSON 파일 하나로 저장하고,
 * 다시 가져올 때는 지금 기기의 기록과 합친다(겹치는 항목은 하나만 남김). 책 파일 자체는 넣지 않는다.
 * 파일은 사용자가 고른 곳(SAF)에만 쓰고 읽는다. 네트워크를 쓰지 않는다.
 */
class Backup(private val context: Context, private val db: AshDb, private val prefs: Prefs) {
    private val dao get() = db.books()
    private val booksDir: File get() = File(context.filesDir, "books")

    data class ExportResult(val books: Int, val bookmarks: Int, val notes: Int, val highlights: Int, val sessions: Int)

    data class ImportResult(
        val booksUpdated: Int,
        val booksMissing: Int,
        val bookmarks: Int,
        val notes: Int,
        val highlights: Int,
        val sessions: Int,
        val settings: Boolean,
    )

    class BadBackup(msg: String) : Exception(msg)

    suspend fun export(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val books = dao.allBooks()
        val bms = dao.allBookmarks()
        val notes = dao.allNotes()
        val hls = dao.allHighlights()
        val ses = dao.allSessions()
        val s = prefs.settings.first()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            // S-53 백업 가져오기 목록이 앞 몇 KB 만 읽어 내용을 보이도록 요약을 머리에 둔다(JSONObject 는 넣은 순서대로 쓴다)
            .put("summary", JSONObject().put("books", books.size).put("bookmarks", bms.size)
                .put("memos", notes.size + hls.count { it.note.isNotBlank() }).put("notes", notes.size).put("highlights", hls.size))
            .put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            .put("bookmarks", JSONArray().apply { bms.forEach { put(it.toJson()) } })
            .put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
            .put("highlights", JSONArray().apply { hls.forEach { put(it.toJson()) } })
            .put("sessions", JSONArray().apply { ses.forEach { put(it.toJson()) } })
            .put("storeApps", JSONArray().apply { dao.allStoreApps().forEach { put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("ord", it.ord)) } })
            .put("settings", s.toJson())
            .put("comicPrefs", ComicPrefsStore(context).exportAll())
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw BadBackup("파일을 만들 수 없어요.")
        out.use { it.write(root.toString(1).toByteArray(Charsets.UTF_8)) }
        ExportResult(books.size, bms.size, notes.size, hls.size, ses.size)
    }

    /**
     * 가져오기. [replace] = '모두 바꾸기'(S-53): 지금 책갈피·메모·형광펜·독서 기록을 지우고 읽은 위치·상태를 처음으로 한 뒤
     * 백업 내용으로 채운다(서재의 책 목록과 파일은 그대로). 기본은 '합치기'(겹치는 항목은 하나만, 위치는 더 최근 것).
     */
    suspend fun import(uri: Uri, replace: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { i ->
            // 끝까지 읽기 전에 상한에서 멈춘다(큰 파일을 통째로 메모리에 올리지 않음)
            val out = java.io.ByteArrayOutputStream()
            if (kr.vici.ashreader.engine.comic.ComicFormat.copyBounded(i, out, MAX_BYTES.toLong()) < 0) throw BadBackup("백업 파일이 너무 커요.")
            String(out.toByteArray(), Charsets.UTF_8)
        } ?: throw BadBackup("파일을 읽을 수 없어요.")
        val root = try { JSONObject(text) } catch (e: Exception) { throw BadBackup("ASH Reader 백업 파일이 아니에요.") }
        if (root.optString("format") != FORMAT) throw BadBackup("ASH Reader 백업 파일이 아니에요.")
        if (root.optInt("version", 0) > VERSION) throw BadBackup("더 새 판에서 만든 백업이에요. 앱을 업데이트한 뒤 가져와 주세요.")

        var updated = 0; var missing = 0; var nb = 0; var nn = 0; var nh = 0; var ns = 0
        db.withTransaction {
            if (replace) {
                dao.clearBookmarks(); dao.clearNotes(); dao.clearHighlights(); dao.clearSessions()
                dao.resetReading()
            }
            // 책: 이 기기에 같은 책(같은 식별 해시)이 있으면 읽은 위치·상태를 더 최근 것으로 맞춘다.
            // 책 파일이 이 기기에 없으면 서재에 넣지 않는다(같은 파일을 다시 넣으면 책갈피·형광펜이 그대로 붙는다).
            for (o in root.optJSONArray("books").objects()) {
                val id = o.optString("id"); if (id.isEmpty()) continue
                val cur = dao.get(id)
                val inc = bookOf(o)
                if (cur == null) {
                    // 필수 6: 백업 안의 경로는 믿지 않는다. 이 앱 서재 폴더(booksDir) 안의 실제 파일만 받는다.
                    val safe = LibraryRepo.insideBooks(booksDir, inc.path)
                    if (safe != null && safe.isFile) { dao.upsert(inc.copy(path = safe.path)); updated++ } else missing++
                } else if (replace || inc.lastOpenedAt > cur.lastOpenedAt) {
                    dao.upsert(cur.copy(lastPage = inc.lastPage, pageCount = inc.pageCount, status = inc.status, lastOpenedAt = inc.lastOpenedAt, viewMode = inc.viewMode,
                        favorite = inc.favorite || (cur.favorite && !replace), textEncoding = inc.textEncoding.ifEmpty { cur.textEncoding }).let { c ->
                            // 직접 묶기·풀기(F-62)는 백업 쪽이 정한 것을 따른다
                            if (inc.seriesManual != Book.SERIES_AUTO) c.copy(seriesKey = inc.seriesKey, seriesManual = inc.seriesManual) else c
                        })
                    updated++
                } else if (inc.favorite && !cur.favorite) {
                    dao.setFavorite(id, true)
                }
            }
            val bmKeys = dao.allBookmarks().map { it.bookId to it.page }.toMutableSet()
            for (o in root.optJSONArray("bookmarks").objects()) {
                val b = Bookmark(bookId = o.optString("bookId"), page = o.optInt("page"), label = o.optString("label"), createdAt = o.optLong("createdAt"), frac = o.fracOf())
                if (b.bookId.isNotEmpty() && bmKeys.add(b.bookId to b.page)) { dao.addBookmark(b); nb++ }
            }
            val noteKeys = dao.allNotes().map { Triple(it.bookId, it.page, it.text) }.toMutableSet()
            for (o in root.optJSONArray("notes").objects()) {
                val n = Note(bookId = o.optString("bookId"), page = o.optInt("page"), text = o.optString("text"), color = o.optInt("color"), createdAt = o.optLong("createdAt"), frac = o.fracOf())
                if (n.bookId.isNotEmpty() && n.text.isNotEmpty() && noteKeys.add(Triple(n.bookId, n.page, n.text))) { dao.addNote(n); nn++ }
            }
            val hlKeys = dao.allHighlights().map { listOf(it.bookId, it.page, it.startIdx, it.endIdx, it.text) }.toMutableSet()
            for (o in root.optJSONArray("highlights").objects()) {
                val h = Highlight(
                    bookId = o.optString("bookId"), page = o.optInt("page"), startIdx = o.optInt("startIdx"), endIdx = o.optInt("endIdx"),
                    text = o.optString("text"), color = o.optInt("color"), note = o.optString("note"), createdAt = o.optLong("createdAt"),
                    frac = o.fracOf(),
                )
                if (h.bookId.isNotEmpty() && hlKeys.add(listOf(h.bookId, h.page, h.startIdx, h.endIdx, h.text))) { dao.addHighlight(h); nh++ }
            }
            val sesKeys = dao.allSessions().map { it.bookId to it.startedAt }.toMutableSet()
            for (o in root.optJSONArray("sessions").objects()) {
                val s = ReadingSession(bookId = o.optString("bookId"), startedAt = o.optLong("startedAt"), durationMs = o.optLong("durationMs"), pagesTurned = o.optInt("pagesTurned"))
                if (s.bookId.isNotEmpty() && s.durationMs > 0 && sesKeys.add(s.bookId to s.startedAt)) { dao.addSession(s); ns++ }
            }
        }
        // 서점 바로가기(F-36): 백업에 있으면 그 목록으로 바꾼다
        root.optJSONArray("storeApps")?.let { arr ->
            val apps = arr.objects().mapNotNull { o -> o.optString("pkg").takeIf { it.isNotEmpty() }?.let { StoreApp(it, o.optString("label", it), o.optInt("ord")) } }
            db.withTransaction {
                dao.allStoreApps().forEach { dao.deleteStoreApp(it.pkg) }
                dao.upsertStoreApps(apps)
            }
        }
        // 만화 책마다 설정(S-33): 이 기기 서재에 있는 책과 '모든 만화 기본값'만
        root.optJSONObject("comicPrefs")?.let { cp ->
            val ids = dao.allBooks().map { it.id }.toHashSet()
            ComicPrefsStore(context).importAll(cp) { it in ids }
        }
        // 설정은 백업 값으로 바꾼다(한 사람이 쓰는 앱이라 합칠 대상이 없음).
        val so = root.optJSONObject("settings")
        if (so != null) {
            prefs.update { settingsOf(so, it).copy(storageAsked = it.storageAsked, storeInit = true) }
        }
        ImportResult(updated, missing, nb, nn, nh, ns, so != null)
    }

    companion object {
        const val FORMAT = "ash-reader-backup"
        const val VERSION = 1
        const val MIME = "application/json"
        private const val MAX_BYTES = 64 shl 20

        /** 백업 파일 MIME. 확장자 .ashbak 를 그대로 두도록 일반 이진으로 만든다(application/json 이면 .json 이 붙는 제공자가 있음). */
        const val FILE_MIME = "application/octet-stream"

        /** M-11 백업 이름 `ASH백업_YYYY-MM-DD.ashbak`, 같은 날 [existing]에 있으면 `_2`, `_3` … */
        fun fileName(existing: Collection<String> = emptyList(), now: Long = System.currentTimeMillis()): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
            return BackupNames.nextName(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH), existing)
        }

        private fun JSONObject.fracOf(): Float = optDouble("frac", -1.0).toFloat().let { if (it.isNaN()) -1f else it }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun Book.toJson() = JSONObject()
            .put("id", id).put("title", title).put("format", format).put("path", path).put("folder", folder)
            .put("sizeBytes", sizeBytes).put("pageCount", pageCount).put("lastPage", lastPage).put("addedAt", addedAt)
            .put("lastOpenedAt", lastOpenedAt).put("status", status).put("seriesKey", seriesKey).put("volume", volume).put("viewMode", viewMode)
            .put("favorite", favorite).put("textEncoding", textEncoding).put("seriesManual", seriesManual)

        private fun bookOf(o: JSONObject) = Book(
            id = o.optString("id"), title = o.optString("title"), format = o.optString("format"), path = o.optString("path"),
            folder = o.optString("folder"), sizeBytes = o.optLong("sizeBytes"), pageCount = o.optInt("pageCount"),
            lastPage = o.optInt("lastPage"), addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            lastOpenedAt = o.optLong("lastOpenedAt"), status = o.optInt("status"), seriesKey = o.optString("seriesKey"),
            volume = o.optInt("volume"), viewMode = o.optInt("viewMode"),
            favorite = o.optBoolean("favorite", false), textEncoding = o.optString("textEncoding"),
            seriesManual = o.optInt("seriesManual", Book.SERIES_AUTO).coerceIn(Book.SERIES_AUTO, Book.SERIES_EXCLUDED),
        )

        private fun Bookmark.toJson() = JSONObject().put("bookId", bookId).put("page", page).put("label", label).put("createdAt", createdAt).put("frac", frac.toDouble())

        private fun Note.toJson() = JSONObject().put("bookId", bookId).put("page", page).put("text", text).put("color", color).put("createdAt", createdAt).put("frac", frac.toDouble())

        private fun Highlight.toJson() = JSONObject()
            .put("bookId", bookId).put("page", page).put("startIdx", startIdx).put("endIdx", endIdx)
            .put("text", text).put("color", color).put("note", note).put("createdAt", createdAt).put("frac", frac.toDouble())

        private fun ReadingSession.toJson() = JSONObject()
            .put("bookId", bookId).put("startedAt", startedAt).put("durationMs", durationMs).put("pagesTurned", pagesTurned)

        private fun Settings.toJson() = JSONObject()
            .put("font", font.name).put("fontSize", fontSize).put("lineHeight", lineHeight.toDouble()).put("theme", theme.name)
            .put("curl", curl).put("volumeKeys", volumeKeys).put("panelMode", panelMode.name).put("aiUpscale", aiUpscale).put("epubAiUpscale", epubAiUpscale)
            .put("clearOnceOnExit", clearOnceOnExit).put("tapGuideShown", tapGuideShown).put("upscaleCacheMb", upscaleCacheMb)
            .put("txtBlank", txtBlank).put("txtJoin", txtJoin).put("txtTrim", txtTrim).put("txtDefaultEncoding", txtDefaultEncoding)
            .put("warmth", warmth).put("warmAuto", warmAuto).put("warmFrom", warmFrom).put("warmTo", warmTo)
            .put("customBg", customBg).put("customFg", customFg).put("autoTurnSec", autoTurnSec).put("noTurnEffect", noTurnEffect)
            .put("infoItems", infoItems).put("infoTop", infoTop).put("tapLayout", tapLayout.name).put("tapCustom", tapCustom)
            .put("leftHanded", leftHanded).put("edgeBrightness", edgeBrightness).put("screenTimeout", screenTimeout.name)
            .put("rotation", rotation.name).put("nightKeepImages", nightKeepImages)
            .put("brightness", brightness.toDouble()).put("dim", dim)
            .put("ttsRate", ttsRate.toDouble()).put("ttsPitch", ttsPitch.toDouble()).put("ttsSleep", ttsSleep).put("showStores", showStores)
            .put("scrollView", scrollView).put("autoScrollSpeed", autoScrollSpeed).put("infoPerPage", infoPerPage)
            .put("paraSpace", paraSpace).put("marginH", marginH).put("marginV", marginV).put("align", align)
            .put("breakChar", breakChar).put("hyphen", hyphen).put("indent", indent).put("dialogIndent", dialogIndent)
            .put("txtTocMask", txtTocMask).put("txtTocCustom", txtTocCustom).put("libSort", libSort).put("libSortDesc", libSortDesc)

        private fun settingsOf(o: JSONObject, base: Settings) = base.copy(
            font = BodyFont.entries.firstOrNull { it.name == o.optString("font") } ?: base.font,
            fontSize = o.optInt("fontSize", base.fontSize).coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE),
            lineHeight = o.optDouble("lineHeight", base.lineHeight.toDouble()).toFloat().coerceIn(1.2f, 2.4f),
            theme = ReadTheme.entries.firstOrNull { it.name == o.optString("theme") } ?: base.theme,
            curl = o.optBoolean("curl", base.curl),
            volumeKeys = o.optBoolean("volumeKeys", base.volumeKeys),
            panelMode = PanelMode.entries.firstOrNull { it.name == o.optString("panelMode") } ?: base.panelMode,
            aiUpscale = o.optBoolean("aiUpscale", base.aiUpscale),
            epubAiUpscale = o.optBoolean("epubAiUpscale", base.epubAiUpscale),
            clearOnceOnExit = o.optBoolean("clearOnceOnExit", base.clearOnceOnExit),
            tapGuideShown = o.optBoolean("tapGuideShown", base.tapGuideShown),
            upscaleCacheMb = o.optInt("upscaleCacheMb", base.upscaleCacheMb).let { v -> if (v in Prefs.UPSCALE_CACHE_CHOICES) v else base.upscaleCacheMb },
            txtBlank = o.optInt("txtBlank", base.txtBlank).coerceIn(0, 2),
            txtJoin = o.optBoolean("txtJoin", base.txtJoin),
            txtTrim = o.optBoolean("txtTrim", base.txtTrim),
            txtDefaultEncoding = o.optString("txtDefaultEncoding", base.txtDefaultEncoding),
            warmth = o.optInt("warmth", base.warmth).coerceIn(0, 80),
            warmAuto = o.optBoolean("warmAuto", base.warmAuto),
            warmFrom = o.optInt("warmFrom", base.warmFrom).coerceIn(0, 24 * 60 - 1),
            warmTo = o.optInt("warmTo", base.warmTo).coerceIn(0, 24 * 60 - 1),
            customBg = o.optInt("customBg", base.customBg),
            customFg = o.optInt("customFg", base.customFg),
            autoTurnSec = o.optInt("autoTurnSec", base.autoTurnSec).coerceIn(2, 120),
            noTurnEffect = o.optBoolean("noTurnEffect", base.noTurnEffect),
            infoItems = o.optInt("infoItems", base.infoItems),
            infoTop = o.optBoolean("infoTop", base.infoTop),
            tapLayout = TapLayout.entries.firstOrNull { it.name == o.optString("tapLayout") } ?: base.tapLayout,
            tapCustom = o.optString("tapCustom", base.tapCustom).takeIf { it.length == 9 } ?: base.tapCustom,
            leftHanded = o.optBoolean("leftHanded", base.leftHanded),
            edgeBrightness = o.optBoolean("edgeBrightness", base.edgeBrightness),
            screenTimeout = ScreenTimeout.entries.firstOrNull { it.name == o.optString("screenTimeout") } ?: base.screenTimeout,
            rotation = Rotation.entries.firstOrNull { it.name == o.optString("rotation") } ?: base.rotation,
            nightKeepImages = o.optBoolean("nightKeepImages", base.nightKeepImages),
            brightness = o.optDouble("brightness", base.brightness.toDouble()).toFloat().let { if (it.isNaN() || it < 0f) -1f else it.coerceIn(0.01f, 1f) },
            dim = o.optInt("dim", base.dim).coerceIn(0, 60),
            ttsRate = o.optDouble("ttsRate", base.ttsRate.toDouble()).toFloat().coerceIn(0.5f, 3f),
            ttsPitch = o.optDouble("ttsPitch", base.ttsPitch.toDouble()).toFloat().coerceIn(0.5f, 2f),
            ttsSleep = o.optInt("ttsSleep", base.ttsSleep),
            showStores = o.optBoolean("showStores", base.showStores),
            scrollView = o.optBoolean("scrollView", base.scrollView),
            autoScrollSpeed = o.optInt("autoScrollSpeed", base.autoScrollSpeed).coerceIn(1, 10),
            infoPerPage = o.optBoolean("infoPerPage", base.infoPerPage),
            paraSpace = o.optInt("paraSpace", base.paraSpace).coerceIn(0, 15),
            marginH = o.optInt("marginH", base.marginH).coerceIn(0, 40),
            marginV = o.optInt("marginV", base.marginV).coerceIn(0, 40),
            align = o.optInt("align", base.align).coerceIn(0, 1),
            breakChar = o.optBoolean("breakChar", base.breakChar),
            hyphen = o.optBoolean("hyphen", base.hyphen),
            indent = o.optInt("indent", base.indent).coerceIn(0, 3),
            dialogIndent = o.optInt("dialogIndent", base.dialogIndent).coerceIn(0, 1),
            txtTocMask = o.optInt("txtTocMask", base.txtTocMask) and 15,
            txtTocCustom = kr.vici.ashreader.doc.TxtToc.safeCustom(o.optString("txtTocCustom", base.txtTocCustom)),
            libSort = o.optInt("libSort", base.libSort).coerceAtLeast(0),
            libSortDesc = o.optBoolean("libSortDesc", base.libSortDesc),
        )
    }
}
