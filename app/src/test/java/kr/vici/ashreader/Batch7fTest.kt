// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.BackupHeader
import kr.vici.ashreader.data.BackupItem
import kr.vici.ashreader.data.BackupNames
import kr.vici.ashreader.data.FolderRules
import kr.vici.ashreader.data.FolderRules.Entry
import kr.vici.ashreader.doc.EpubUpscaleRules
import kr.vici.ashreader.doc.EpubUpscaleRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배치 7F: 서재 폴더 겹침(S-17), 백업 목록·머리 읽기(S-53), EPUB 그림 AI 대상 고르기(F-73 확장). */
class Batch7fTest {
    private val root = "/storage/emulated/0"

    // ───────── S-17 서재 폴더 ─────────

    @Test
    fun canonicalForms() {
        assertEquals("primary:Download/소설", FolderRules.canonicalOfPath("/storage/emulated/0/Download/소설/", root))
        assertEquals("primary:", FolderRules.canonicalOfPath(root, root))
        assertEquals("1A2B-3C4D:Comics", FolderRules.canonicalOfPath("/storage/1A2B-3C4D/Comics", root))
        assertNull(FolderRules.canonicalOfPath("/data/user/0/x", root))
        assertEquals("primary:Download/소설", FolderRules.canonicalOfDocId("primary:Download/소설/"))
        assertEquals("primary:", FolderRules.canonicalOfDocId("primary:"))
        assertNull(FolderRules.canonicalOfDocId("noColon"))
    }

    @Test
    fun withinRespectsSegmentsAndVolumeCase() {
        assertTrue(FolderRules.within("primary:Download", "primary:Download/소설"))
        assertTrue(FolderRules.within("primary:Download", "primary:Download"))
        assertFalse(FolderRules.within("primary:Down", "primary:Download")) // 마디 단위
        assertTrue(FolderRules.within("primary:", "primary:Documents/업무"))
        assertTrue(FolderRules.within("1a2b-3c4d:Comics", "1A2B-3C4D:Comics/원피스")) // SD 카드 uuid 대소문자
        assertFalse(FolderRules.within("1A2B-3C4D:Comics", "primary:Comics"))
    }

    @Test
    fun overlapRejectedOnlyWhenCoveredBySubfolderParent() {
        val dl = Entry("/storage/emulated/0/Download", "primary:Download", subfolders = true, name = "Download")
        val docs = Entry("tree-docs", "primary:Documents", subfolders = false, name = "Documents")
        val existing = listOf(dl, docs)
        assertEquals(dl, FolderRules.coveringFolder("primary:Download/KakaoTalk", existing))
        assertEquals(dl, FolderRules.coveringFolder("primary:Download", existing)) // 같은 폴더
        assertEquals(docs, FolderRules.coveringFolder("primary:Documents", existing))
        assertNull(FolderRules.coveringFolder("primary:Documents/업무", existing)) // 하위 폴더 미포함 부모 → 겹치지 않음
        assertNull(FolderRules.coveringFolder("primary:Books", existing))
    }

    @Test
    fun walkRootsSkipsCoveredFolders() {
        val child = Entry("c", "primary:Download/소설", true, "소설")
        val parent = Entry("p", "primary:Download", true, "Download")
        val flat = Entry("f", "primary:Documents", false, "Documents")
        val inFlat = Entry("g", "primary:Documents/업무", true, "업무")
        val roots = FolderRules.walkRoots(listOf(child, parent, flat, inFlat)).map { it.key }
        assertEquals(listOf("p", "f", "g"), roots)
        // 같은 폴더가 둘이면 먼저 것만
        val dup = Entry("p2", "PRIMARY:Download", true, "Download")
        assertEquals(listOf("p"), FolderRules.walkRoots(listOf(parent, dup)).map { it.key })
    }

    @Test
    fun countsDoNotDoubleCount() {
        val parent = Entry("p", "primary:Download", true, "Download")
        val child = Entry("c", "primary:Download/소설", true, "소설")
        val flat = Entry("f", "primary:Documents", false, "Documents")
        val dirs = listOf(
            "primary:Download", "primary:Download/소설", "primary:Download/소설/1부",
            "primary:Documents", "primary:Documents/옛것", "primary:Books",
        )
        val (per, total) = FolderRules.counts(listOf(parent, child, flat), dirs)
        assertEquals(listOf(3, 2, 1), per)
        assertEquals(4, total) // Download 3 + Documents 1, 소설은 겹쳐도 한 번, 하위 폴더 미포함 Documents/옛것·밖의 Books 는 셈에서 빠짐
        assertEquals("primary:Download/소설", FolderRules.parentOf("primary:Download/소설/a.epub"))
        assertEquals("primary:", FolderRules.parentOf("primary:a.epub"))
    }

    @Test
    fun namesAndMiddleEllipsis() {
        val vol = { v: String -> if (v == "primary") "내장 저장공간" else "SD 카드" }
        assertEquals("업무", FolderRules.nameOf("primary:Documents/업무", vol))
        assertEquals("SD 카드", FolderRules.nameOf("1A2B-3C4D:", vol))
        assertEquals("SD 카드/Comics", FolderRules.pathOf("1A2B-3C4D:Comics", vol))
        val long = "내장 저장공간/Documents/아주/깊은/폴더/안의/업무"
        val cut = FolderRules.middleEllipsis(long, 20)
        assertEquals(20, cut.length)
        assertTrue(cut.startsWith("내장 저장공간"))
        assertTrue(cut.endsWith("업무"))
        assertTrue(cut.contains("…"))
        assertEquals("짧은 길", FolderRules.middleEllipsis("짧은 길", 20))
    }

    // ───────── S-53 백업 목록 ─────────

    @Test
    fun backupNames() {
        assertTrue(BackupNames.isBackupName("ASH백업_2026-10-23.ashbak"))
        assertTrue(BackupNames.isBackupName("ASH백업_2026-10-23_2.ashbak"))
        assertTrue(BackupNames.isBackupName("ASH-백업-20261020-0915.json")) // 옛 판 이름
        assertTrue(BackupNames.isBackupName("내 백업.ASHBAK"))
        assertFalse(BackupNames.isBackupName("소설.epub"))
        assertFalse(BackupNames.isBackupName("data.json"))
        assertEquals("ASH백업_2026-10-23.ashbak", BackupNames.nextName(2026, 10, 23, listOf("ASH백업_2026-10-22.ashbak")))
        assertEquals("ASH백업_2026-10-23_2.ashbak", BackupNames.nextName(2026, 10, 23, listOf("ASH백업_2026-10-23.ashbak")))
        assertEquals("ASH백업_2026-10-23_3.ashbak", BackupNames.nextName(2026, 10, 23, listOf("ash백업_2026-10-23.ashbak", "ASH백업_2026-10-23_2.ashbak")))
        val t1 = BackupNames.timeOf("ASH백업_2026-10-23.ashbak")!!
        val t2 = BackupNames.timeOf("ASH백업_2026-10-23_2.ashbak")!!
        val old = BackupNames.timeOf("ASH-백업-20261020-0915.json")!!
        assertTrue(t2 > t1 && t1 > old)
        assertNull(BackupNames.timeOf("내 백업.ashbak"))
    }

    @Test
    fun headerParseReadsSummaryOnly() {
        val text = """{"format":"ash-reader-backup","version":1,"exportedAt":1761210120000,""" +
            """"summary":{"books":128,"bookmarks":340,"memos":52,"notes":40,"highlights":90},"books":[{"title":"\"format\":\"x\""}]}"""
        val h = BackupHeader.parse(text)
        assertNotNull(h)
        assertEquals("ash-reader-backup", h!!.format)
        assertEquals(1, h.version)
        assertEquals(1761210120000L, h.exportedAt)
        assertEquals(128, h.books); assertEquals(340, h.bookmarks); assertEquals(52, h.memos)
        assertTrue(h.hasSummary)
        // 들여쓰기한 옛 백업(요약 없음)
        val old = BackupHeader.parse("{\n \"format\": \"ash-reader-backup\",\n \"version\": 1,\n \"exportedAt\": 5,\n \"books\": [\n")
        assertNotNull(old); assertFalse(old!!.hasSummary); assertEquals(5L, old.exportedAt)
        // 백업이 아님·손상
        assertNull(BackupHeader.parse("PK\u0003\u0004 zip"))
        assertNull(BackupHeader.parse("{\"books\":[]}"))
        assertEquals(BackupItem.Status.NOT_BACKUP, BackupItem.statusOf(BackupHeader.parse("""{"format":"other","version":1}"""), 1))
        assertEquals(BackupItem.Status.TOO_NEW, BackupItem.statusOf(BackupHeader.parse("""{"format":"ash-reader-backup","version":9}"""), 1))
        assertEquals(BackupItem.Status.NOT_BACKUP, BackupItem.statusOf(null, 1))
    }

    @Test
    fun listingNewestFirst() {
        fun item(name: String, at: Long, mod: Long) = BackupItem(
            name, "content://x/$name", 1, mod,
            if (at > 0) BackupHeader("ash-reader-backup", 1, at, 1, 1, 1) else null,
            if (at > 0) BackupItem.Status.OK else BackupItem.Status.NOT_BACKUP,
        )
        val a = item("ASH백업_2026-10-02.ashbak", 1_759_400_000_000L, 0)
        val b = item("ASH백업_2026-10-23.ashbak", 1_761_210_000_000L, 0)
        val c = item("ASH-백업-20261020-0915.json", 1_760_920_000_000L, 0)
        val d = item("망가진.ashbak", 0, 1_700_000_000_000L) // 머리 없음 → 수정 시각
        assertEquals(listOf(b, c, a, d).map { it.name }, BackupItem.newestFirst(listOf(a, d, c, b)).map { it.name })
    }

    // ───────── F-73 확장 EPUB 그림 ─────────

    @Test
    fun epubEligibility() {
        // 300×400 삽화가 화면에 900×1200 으로 → 대상
        assertTrue(EpubUpscaleRules.eligible(300, 400, 900f, 1200f, Kind.RASTER))
        // 이미 원래 크기 이하로 보임 → 아님
        assertFalse(EpubUpscaleRules.eligible(800, 1000, 700f, 875f, Kind.RASTER))
        // 조금만 커짐(1.1배) → 아님
        assertFalse(EpubUpscaleRules.eligible(600, 800, 660f, 880f, Kind.RASTER))
        // 아이콘(짧은 변 64 미만)
        assertFalse(EpubUpscaleRules.eligible(48, 48, 200f, 200f, Kind.RASTER))
        // 짧은 변 1000 초과 → 이미 큼
        assertFalse(EpubUpscaleRules.eligible(1200, 1600, 2400f, 3200f, Kind.RASTER))
        // 1000 은 대상
        assertTrue(EpubUpscaleRules.eligible(1000, 1400, 1440f, 2016f, Kind.RASTER))
        // SVG·GIF·모름·회전된 그림
        assertFalse(EpubUpscaleRules.eligible(300, 400, 900f, 1200f, Kind.SVG))
        assertFalse(EpubUpscaleRules.eligible(300, 400, 900f, 1200f, Kind.GIF))
        assertFalse(EpubUpscaleRules.eligible(300, 400, 900f, 1200f, Kind.UNKNOWN))
        assertFalse(EpubUpscaleRules.eligible(300, 400, 900f, 1200f, Kind.RASTER, orientation = 6))
    }

    @Test
    fun epubKindIndex() {
        val idx = EpubUpscaleRules.Index(
            listOf(
                Triple("OEBPS/images/p1.jpg", 300, 400),
                Triple("OEBPS/images/anim.gif", 120, 90),
                Triple("OEBPS/images/same.gif", 300, 400), // 같은 크기 GIF 가 있어도 래스터가 이긴다
                Triple("OEBPS/images/logo.svg", 0, 0),
                Triple("OEBPS/images/rot.JPEG", 640, 480),
            ),
        )
        assertEquals(Kind.RASTER, idx.kindOf(300, 400))
        assertEquals(Kind.GIF, idx.kindOf(120, 90))
        assertEquals(Kind.RASTER, idx.kindOf(480, 640)) // EXIF 로 돈 그림
        assertEquals(Kind.UNKNOWN, idx.kindOf(400, 400)) // SVG 등 표에 없는 크기
        assertEquals(Kind.SVG, EpubUpscaleRules.kindOfName("a/b.SVG"))
        assertEquals(Kind.RASTER, EpubUpscaleRules.kindOfName("x.webp"))
        // 작업 03: zip 이 아닌 문서(PDF·FB2·HTML)는 이름 표가 없어 모든 그림을 래스터로 본다
        val pdf = EpubUpscaleRules.Index(emptyList(), fallback = Kind.RASTER)
        assertEquals(Kind.RASTER, pdf.kindOf(400, 400))
        assertTrue(EpubUpscaleRules.eligible(400, 300, 900f, 675f, pdf.kindOf(400, 300)))
    }

    @Test
    fun epubFlatImagesSkipped() {
        val twoColors = IntArray(1000) { if (it % 3 == 0) 0xFF000000.toInt() else -1 }
        assertTrue(EpubUpscaleRules.isFlat(twoColors))
        val photo = IntArray(1000) { (0xFF shl 24) or (it * 7919 and 0xFFFFFF) }
        assertFalse(EpubUpscaleRules.isFlat(photo))
    }
}
