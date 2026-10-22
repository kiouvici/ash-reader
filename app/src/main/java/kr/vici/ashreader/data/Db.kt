// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
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
) {
    companion object {
        const val TO_READ = 0
        const val READING = 1
        const val DONE = 2
    }
}

/** 읽은 비율 0..1 (Room 열이 아니므로 확장 함수로 둔다). */
fun Book.progress(): Float = if (status == Book.DONE) 1f else if (pageCount <= 0) 0f else ((lastPage + 1f) / pageCount).coerceIn(0f, 1f)

@Entity(tableName = "bookmark", indices = [Index("bookId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val page: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
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
)

/**
 * 형광펜(S-28). 위치(locator) = 쪽 + 그 쪽 글자 열의 [startIdx]..[endIdx](끝 제외) + 고른 글자 [text].
 * 이북류는 글꼴·크기를 바꾸면 쪽이 달라지므로 [text]로 근처 쪽에서 다시 찾는다.
 * [color] = 0 노랑, 1 초록, 2 파랑, 3 분홍. [note] = 붙인 메모(없으면 빈 글).
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
    @Query("SELECT * FROM book ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC")
    fun recent(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun get(id: String): Book?

    @Query("SELECT * FROM book WHERE seriesKey = :key AND seriesKey != '' ORDER BY volume")
    fun series(key: String): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE seriesKey = :key AND volume = :volume LIMIT 1")
    suspend fun volume(key: String, volume: Int): Book?

    @Upsert
    suspend fun upsert(b: Book)

    @Query("UPDATE book SET lastPage = :page, pageCount = :count, lastOpenedAt = :now, status = :status WHERE id = :id")
    suspend fun progress(id: String, page: Int, count: Int, status: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(b: Bookmark)

    @Query("DELETE FROM bookmark WHERE bookId = :bookId AND page = :page")
    suspend fun removeBookmark(bookId: String, page: Int)

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
}

@Database(entities = [Book::class, Bookmark::class, Note::class, ReadingSession::class, Highlight::class], version = 2, exportSchema = false)
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

        @Volatile private var inst: AshDb? = null
        fun get(context: Context): AshDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(context.applicationContext, AshDb::class.java, "ash.db").addMigrations(M1_2).build().also { inst = it }
        }
    }
}
