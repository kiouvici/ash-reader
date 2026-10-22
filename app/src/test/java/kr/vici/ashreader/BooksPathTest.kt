// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import java.io.File
import java.nio.file.Files
import kr.vici.ashreader.data.LibraryRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 필수 6: 백업·삭제 경로는 서재 폴더 안의 파일만 받는다. */
class BooksPathTest {
    @Test fun onlyInsideBooks() {
        val root = Files.createTempDirectory("ash").toFile()
        val books = File(root, "books").apply { mkdirs() }
        val ok = File(books, "abc.pdf").apply { writeText("x") }
        val outside = File(root, "secret.db").apply { writeText("x") }
        assertEquals(ok.canonicalFile, LibraryRepo.insideBooks(books, ok.path))
        assertNull(LibraryRepo.insideBooks(books, outside.path))
        assertNull(LibraryRepo.insideBooks(books, File(books, "../secret.db").path))
        assertNull(LibraryRepo.insideBooks(books, File(books, "sub/../../secret.db").path))
        assertNull(LibraryRepo.insideBooks(books, ""))
        assertNull(LibraryRepo.insideBooks(books, "/data/data/kr.vici.ashreader/databases/ash.db"))
        root.deleteRecursively()
    }
}
