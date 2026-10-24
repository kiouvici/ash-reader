// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 서재의 책 한 권. [id] = 문서 식별 해시(F-63). [path] = 앱 저장소 안 사본 경로. */
@Entity(tableName = "book")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val format: String,
    val path: String,
    val folder: String = "",
    val sizeBytes: Long = 0,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    /** 0 = 읽을 책, 1 = 읽는 중, 2 = 다 읽음 */
    val status: Int = 0,
    val seriesKey: String = "",
    val volume: Int = 0,
    /** 한글 문서 보기 방식: 0 = 원본, 1 = 이북식(F-28). 다시 열 때 이 방식으로 연다. */
    @ColumnInfo(defaultValue = "0") val viewMode: Int = 0,
    /** 즐겨찾기(F-71). */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** TXT 인코딩 수동 지정(F-32): "" = 자동, 아니면 TextEncoding.Kind 이름. */
    @ColumnInfo(defaultValue = "''") val textEncoding: String = "",
    /** 어디서 왔나: [ORIGIN_COPY] 앱 안 사본 / [ORIGIN_FILE] 폴더 수집(파일 경로) / [ORIGIN_SAF] 폴더 수집(SAF, [sourceUri]). */
    @ColumnInfo(defaultValue = "0") val origin: Int = ORIGIN_COPY,
    /** SAF 로 모은 문서의 content:// 주소(열 때 캐시로 복사). */
    @ColumnInfo(defaultValue = "''") val sourceUri: String = "",
    /** 수집 때 본 파일 수정 시각(크기·시각이 같으면 해시를 다시 내지 않음). */
    @ColumnInfo(defaultValue = "0") val fileMtime: Long = 0,
    /** 0 = 보임, [HIDDEN_MISSING] = 파일이 없어짐(다시 찾으면 이어짐), [HIDDEN_REMOVED] = 사용자가 서재에서 뺌(다시 모으지 않음). */
    @ColumnInfo(defaultValue = "0") val hidden: Int = 0,
    /** 시리즈 묶기(F-62): [SERIES_AUTO] 파일명으로 자동 / [SERIES_MANUAL] 직접 묶음([seriesKey] = 묶음 이름) / [SERIES_EXCLUDED] 묶기 풀기·시리즈에서 뺌. */
    @ColumnInfo(defaultValue = "0") val seriesManual: Int = SERIES_AUTO,
) {
    companion object {
        const val TO_READ = 0
        const val READING = 1
        const val DONE = 2
        const val VIEW_ORIGINAL = 0
        const val VIEW_EBOOK = 1
        /** 그림만 든 PDF 를 만화 보기로(펼침·AI 화질). */
        const val VIEW_COMIC = 2
        const val ORIGIN_COPY = 0
        const val ORIGIN_FILE = 1
        const val ORIGIN_SAF = 2
        /** 압축 파일 안 문서(S-46). [sourceUri] = "압축 책 id\n항목 이름", [path] = 풀어 둔 캐시(zipx/). */
        const val ORIGIN_ZIP = 3
        const val HIDDEN_MISSING = 1
        const val HIDDEN_REMOVED = 2
        /** 압축 파일 안 문서: 서재에는 압축 파일 한 권으로만 보이고, 읽은 위치·책갈피는 이 기록에 둔다. */
        const val HIDDEN_INNER = 3
        const val SERIES_AUTO = 0
        const val SERIES_MANUAL = 1
        const val SERIES_EXCLUDED = 2
    }
}

/** 서재 '내 서점 앱' 바로가기 한 줄(F-36). [ord] 작은 것부터. */
@Entity(tableName = "store_app")
data class StoreApp(
    @PrimaryKey val pkg: String,
    val label: String,
    val ord: Int,
)

/** 서재 폴더(F-01, S-17)·빼는 폴더(옛 판). [path] = 절대 경로 또는 SAF 폴더 content:// 주소. */
@Entity(tableName = "scan_folder")
data class ScanFolder(
    @PrimaryKey val path: String,
    /** true = 서재 폴더(모으는 폴더), false = 빼는 폴더(옛 판에서 고른 것 — 지금도 지킨다) */
    val include: Boolean,
    /** 화면에 보일 이름(예: "Download/소설") */
    val label: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** '하위 폴더도 포함'(S-17). 끄면 그 폴더 바로 안의 파일만 모은다. 판 6. */
    @ColumnInfo(defaultValue = "1") val subfolders: Boolean = true,
)

/**
 * 보기 방식·쪽 나눔과 무관한 위치(F-28): 문서 전체에서의 비율 0..1 (-1 = 모름, 옛 기록).
 * 같은 쪽 나눔이면 [page]로 정확히 돌아오고, 쪽 수가 바뀌면(이북식↔원본, 글꼴·크기 변경) 비율로 옮긴다.
 */
fun locatorPage(page: Int, frac: Float, count: Int): Int =
    if (frac >= 0f && count > 0) (frac * count).toInt().coerceIn(0, count - 1) else page

/** [page]쪽 안 [within](0..1) 지점의 비율 위치. */
fun locatorFrac(page: Int, count: Int, within: Float = 0.5f): Float =
    if (count <= 0) -1f else ((page + within.coerceIn(0.01f, 0.99f)) / count).coerceIn(0f, 1f)

/** 읽은 비율 0..1 (Room 열이 아니므로 확장 함수로 둔다). */
fun Book.progress(): Float = if (status == Book.DONE) 1f else if (pageCount <= 0) 0f else ((lastPage + 1f) / pageCount).coerceIn(0f, 1f)

@Entity(tableName = "bookmark", indices = [Index("bookId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "-1") val frac: Float = -1f,
)

/** 형광펜·메모. 글자 선택(S-28)은 2단계 후반에 연결 — 지금은 쪽 단위 메모만 저장. */
@Entity(tableName = "note", indices = [Index("bookId")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val text: String,
    val color: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "-1") val frac: Float = -1f,
)

/**
 * 형광펜(S-28). 위치(locator) = 쪽 + 그 쪽 글자 열의 [startIdx]..[endIdx](끝 제외) + 고른 글자 [text].
 * 이북류는 글꼴·크기를 바꾸면 쪽이 달라지므로 [text]로 근처 쪽에서 다시 찾는다.
 * [color] = 0 노랑, 1 초록, 2 파랑, 3 분홍. [note] = 붙인 메모(없으면 빈 글).
 * [frac] = 보기 방식과 무관한 위치(문서 비율, [locatorPage]). 쪽 수가 바뀌어도 [text]를 그 근처에서 찾는다.
 * 한글 원본 보기는 글자 위치가 없어 칠해 보이지 않는다(이북식에서 다시 보임).
 */
@Entity(tableName = "highlight", indices = [Index("bookId")])
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val startIdx: Int,
    val endIdx: Int,
    val text: String,
    val color: Int = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "-1") val frac: Float = -1f,
)

/** 독서 기록(S-50). 기기에만 저장. */
@Entity(tableName = "session", indices = [Index("startedAt")])
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val startedAt: Long,
    val durationMs: Long,
    val pagesTurned: Int,
)

data class BookTime(val bookId: String, val total: Long)

@Dao
interface BookDao {
    @Query("SELECT * FROM book WHERE hidden = 0 ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC")
    fun recent(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun get(id: String): Book?

    @Query("SELECT * FROM book WHERE seriesKey = :key AND seriesKey != '' AND hidden = 0 ORDER BY volume")
    fun series(key: String): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE seriesKey = :key AND volume = :volume AND hidden = 0 LIMIT 1")
    suspend fun volume(key: String, volume: Int): Book?

    /** 다음 권 찾기(F-61)·시리즈 묶기(F-62)에 쓰는 보이는 책 전체. */
    @Query("SELECT * FROM book WHERE hidden = 0")
    suspend fun visibleBooks(): List<Book>

    /** 시리즈 직접 묶기·풀기(F-62). */
    @Query("UPDATE book SET seriesKey = :key, seriesManual = :mode WHERE id = :id")
    suspend fun setSeries(id: String, key: String, mode: Int)

    @Upsert
    suspend fun upsert(b: Book)

    @Query("UPDATE book SET lastPage = :page, pageCount = :count, lastOpenedAt = :now, status = :status WHERE id = :id")
    suspend fun progress(id: String, page: Int, count: Int, status: Int, now: Long = System.currentTimeMillis())

    /** 연 시각만 고친다(압축 파일 S-46 — 안 문서의 기록은 따로). */
    @Query("UPDATE book SET lastOpenedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE book SET viewMode = :mode WHERE id = :id")
    suspend fun setViewMode(id: String, mode: Int)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE book SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE book SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: Int)

    @Query("UPDATE book SET lastPage = 0, status = CASE WHEN status = 2 THEN 1 ELSE status END WHERE id = :id")
    suspend fun restart(id: String)

    @Query("UPDATE book SET textEncoding = :enc WHERE id = :id")
    suspend fun setEncoding(id: String, enc: String)

    @Query("UPDATE book SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Int)

    /** 폴더에서 모은 문서(수집 대상). */
    @Query("SELECT * FROM book WHERE origin != 0")
    suspend fun collected(): List<Book>

    // ---- 서점 바로가기(F-36)
    @Query("SELECT * FROM store_app ORDER BY ord, label")
    fun storeApps(): Flow<List<StoreApp>>

    @Query("SELECT * FROM store_app ORDER BY ord, label")
    suspend fun allStoreApps(): List<StoreApp>

    @Upsert
    suspend fun upsertStoreApps(apps: List<StoreApp>)

    @Query("DELETE FROM store_app WHERE pkg = :pkg")
    suspend fun deleteStoreApp(pkg: String)

    // ---- 모으는·빼는 폴더(F-01)
    @Query("SELECT * FROM scan_folder ORDER BY include DESC, addedAt")
    fun scanFolders(): Flow<List<ScanFolder>>

    @Query("SELECT * FROM scan_folder")
    suspend fun allScanFolders(): List<ScanFolder>

    @Upsert
    suspend fun upsertScanFolder(f: ScanFolder)

    @Query("DELETE FROM scan_folder WHERE path = :path")
    suspend fun deleteScanFolder(path: String)

    @Query("SELECT * FROM scan_folder WHERE path = :path")
    suspend fun scanFolder(path: String): ScanFolder?

    @Query("UPDATE scan_folder SET subfolders = :on WHERE path = :path")
    suspend fun setSubfolders(path: String, on: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(b: Bookmark)

    @Query("DELETE FROM bookmark WHERE bookId = :bookId AND page = :page")
    suspend fun removeBookmark(bookId: String, page: Int)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun removeBookmarkById(id: Long)

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY page")
    fun bookmarks(bookId: String): Flow<List<Bookmark>>

    @Insert
    suspend fun addNote(n: Note)

    @Query("SELECT * FROM note WHERE bookId = :bookId ORDER BY page")
    fun notes(bookId: String): Flow<List<Note>>

    @Insert
    suspend fun addHighlight(h: Highlight): Long

    @Query("DELETE FROM highlight WHERE id = :id")
    suspend fun removeHighlight(id: Long)

    @Query("SELECT * FROM highlight WHERE bookId = :bookId ORDER BY page, startIdx")
    fun highlights(bookId: String): Flow<List<Highlight>>

    @Insert
    suspend fun addSession(s: ReadingSession)

    @Query("SELECT * FROM session WHERE startedAt >= :from ORDER BY startedAt")
    fun sessionsSince(from: Long): Flow<List<ReadingSession>>

    @Query("SELECT DISTINCT startedAt / 86400000 FROM session ORDER BY 1 DESC")
    fun sessionDays(): Flow<List<Long>>

    @Query("SELECT bookId, SUM(durationMs) AS total FROM session GROUP BY bookId")
    fun timeByBook(): Flow<List<BookTime>>

    // ---- 백업·내보내기(S-53): 한 번에 읽고 쓰기
    @Query("SELECT * FROM book") suspend fun allBooks(): List<Book>
    @Query("SELECT * FROM bookmark") suspend fun allBookmarks(): List<Bookmark>
    @Query("SELECT * FROM note") suspend fun allNotes(): List<Note>
    @Query("SELECT * FROM highlight") suspend fun allHighlights(): List<Highlight>
    @Query("SELECT * FROM session") suspend fun allSessions(): List<ReadingSession>

    // ---- 백업 가져오기 '모두 바꾸기'(S-53): 지금 기록을 지운 뒤 백업 내용으로
    @Query("DELETE FROM bookmark") suspend fun clearBookmarks()
    @Query("DELETE FROM note") suspend fun clearNotes()
    @Query("DELETE FROM highlight") suspend fun clearHighlights()
    @Query("DELETE FROM session") suspend fun clearSessions()
    /** 읽은 위치·상태·즐겨찾기·시리즈 직접 묶기를 처음으로(책 목록·파일 위치는 그대로). */
    @Query("UPDATE book SET lastPage = 0, status = 0, lastOpenedAt = 0, favorite = 0, seriesManual = 0 WHERE seriesManual != 0 OR lastPage != 0 OR status != 0 OR lastOpenedAt != 0 OR favorite != 0")
    suspend fun resetReading()
}

@Database(entities = [Book::class, Bookmark::class, Note::class, ReadingSession::class, Highlight::class, StoreApp::class, ScanFolder::class], version = 6, exportSchema = true)
abstract class AshDb : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        /** 1 → 2: 형광펜 표 추가(기존 서재·책갈피·메모는 그대로). */
        val M1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `highlight` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`page` INTEGER NOT NULL, `startIdx` INTEGER NOT NULL, `endIdx` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "`color` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlight_bookId` ON `highlight` (`bookId`)")
            }
        }

        /** 2 → 3: 한글 보기 방식(책), 보기 방식과 무관한 위치 비율(책갈피·메모·형광펜). */
        val M2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book` ADD COLUMN `viewMode` INTEGER NOT NULL DEFAULT 0")
                for (t in listOf("bookmark", "note", "highlight")) db.execSQL("ALTER TABLE `$t` ADD COLUMN `frac` REAL NOT NULL DEFAULT -1")
            }
        }

        /**
         * 3 → 4: 즐겨찾기(F-71)·TXT 인코딩(F-32)·폴더 수집(F-01: 출처·SAF 주소·수정 시각·숨김) 열,
         * 서점 바로가기(F-36)·모으는 폴더 표. 기존 서재는 모두 앱 안 사본(origin 0)으로 남는다.
         */
        val M3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book` ADD COLUMN `textEncoding` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `book` ADD COLUMN `origin` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book` ADD COLUMN `sourceUri` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `book` ADD COLUMN `fileMtime` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `store_app` (`pkg` TEXT NOT NULL, `label` TEXT NOT NULL, `ord` INTEGER NOT NULL, PRIMARY KEY(`pkg`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_folder` (`path` TEXT NOT NULL, `include` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`path`))",
                )
            }
        }

        /** 4 → 5: 시리즈 직접 묶기·풀기(F-62). 기존 책은 모두 자동 묶기(0). */
        val M4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book` ADD COLUMN `seriesManual` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 5 → 6: 서재 폴더마다 '하위 폴더도 포함'(S-17). 기존 폴더는 모두 포함(지금까지와 같게). */
        val M5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scan_folder` ADD COLUMN `subfolders` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** 앱이 쓰는 모든 이전(이전 시험 AshDbMigrationTest 도 이 목록을 쓴다). */
        val MIGRATIONS = arrayOf(M1_2, M2_3, M3_4, M4_5, M5_6)

        @Volatile private var inst: AshDb? = null
        fun get(context: Context): AshDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(context.applicationContext, AshDb::class.java, "ash.db").addMigrations(*MIGRATIONS).build().also { inst = it }
        }
    }
}
