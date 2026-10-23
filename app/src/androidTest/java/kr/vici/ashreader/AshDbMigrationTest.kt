// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kr.vici.ashreader.data.AshDb
import kr.vici.ashreader.data.Book
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * DB 이전 시험: 1판(첫 배포 스키마)을 손으로 만들고 데이터를 넣은 뒤 지금 판으로 연다.
 * Room 은 이전을 마친 뒤 모든 표·열·색인을 엔티티와 대조하므로(onValidateSchema) 어긋나면 여기서 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class AshDbMigrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun cleanAfter() { ctx.deleteDatabase(name) }

    @Test
    fun migrate1ToLatest_keepsData() {
        val f = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `book` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `format` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                    "`folder` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `lastPage` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL, `status` INTEGER NOT NULL, `seriesKey` TEXT NOT NULL, " +
                    "`volume` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                    "`page` INTEGER NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_bookId` ON `bookmark` (`bookId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                    "`page` INTEGER NOT NULL, `text` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_bookId` ON `note` (`bookId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `pagesTurned` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_startedAt` ON `session` (`startedAt`)")
            db.execSQL(
                "INSERT INTO book VALUES ('b1', '원피스 3', 'CBZ', '/x/원피스 3.cbz', '만화', 100, 200, 41, 1, 2, 1, '원피스', 3)",
            )
            db.execSQL("INSERT INTO bookmark (bookId, page, label, createdAt) VALUES ('b1', 10, '', 5)")
            db.execSQL("INSERT INTO note (bookId, page, text, color, createdAt) VALUES ('b1', 12, '메모', 0, 6)")
            db.version = 1
        }

        val room = Room.databaseBuilder(ctx, AshDb::class.java, name).addMigrations(*AshDb.MIGRATIONS).build()
        try {
            runBlocking {
                val b = room.books().get("b1")
                assertNotNull(b)
                assertEquals(41, b!!.lastPage)
                assertEquals("원피스", b.seriesKey)
                assertEquals(0, b.viewMode)
                assertEquals(false, b.favorite)
                assertEquals(Book.ORIGIN_COPY, b.origin)
                assertEquals(Book.SERIES_AUTO, b.seriesManual)
                assertEquals(1, room.books().allBookmarks().size)
                assertEquals(-1f, room.books().allBookmarks()[0].frac)
                assertEquals(1, room.books().allNotes().size)
                assertEquals(0, room.books().allHighlights().size)
                assertEquals(0, room.books().allStoreApps().size)
            }
        } finally {
            room.close()
        }
    }

    /**
     * 5 → 6(S-17): 판 5 DB(1판 표 + 이전 1~4)에 서재 폴더를 넣어 두고 지금 판으로 연다.
     * 기존 폴더는 '하위 폴더도 포함'(subfolders = 1)이어야 한다(지금까지와 같게 모음).
     */
    @Test
    fun migrate5To6_scanFolderKeepsSubfolders() {
        val cfg = SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `format` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`folder` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `lastPage` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER NOT NULL, `status` INTEGER NOT NULL, `seriesKey` TEXT NOT NULL, " +
                        "`volume` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `page` INTEGER NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_bookId` ON `bookmark` (`bookId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `page` INTEGER NOT NULL, `text` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_bookId` ON `note` (`bookId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `pagesTurned` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_startedAt` ON `session` (`startedAt`)")
                AshDb.M1_2.migrate(db); AshDb.M2_3.migrate(db); AshDb.M3_4.migrate(db); AshDb.M4_5.migrate(db)
                db.execSQL("INSERT INTO scan_folder (path, include, label, addedAt) VALUES ('/storage/emulated/0/Download', 1, 'Download', 7)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }).build()
        FrameworkSQLiteOpenHelperFactory().create(cfg).use { it.writableDatabase.version }

        val room = Room.databaseBuilder(ctx, AshDb::class.java, name).addMigrations(*AshDb.MIGRATIONS).build()
        try {
            runBlocking {
                val f = room.books().allScanFolders()
                assertEquals(1, f.size)
                assertEquals(true, f[0].subfolders)
                assertEquals("Download", f[0].label)
                room.books().setSubfolders(f[0].path, false)
                assertEquals(false, room.books().scanFolder(f[0].path)!!.subfolders)
            }
        } finally {
            room.close()
        }
    }
}
