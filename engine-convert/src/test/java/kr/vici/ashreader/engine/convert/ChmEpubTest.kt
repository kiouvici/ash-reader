// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChmEpubTest {
    private val hhc = """
        <HTML><BODY><OBJECT type="text/site properties"><param name="Window Styles" value="0x800025"></OBJECT>
        <UL>
          <LI><OBJECT type="text/sitemap"><param name="Name" value="1장 시작"><param name="Local" value="ch1.htm"></OBJECT>
          <UL>
            <LI><OBJECT type="text/sitemap"><param name="Name" value="1.1 &amp; 절"><param name="Local" value="sub/ch1-1.htm#a"></OBJECT>
          </UL>
          <LI><OBJECT type="text/sitemap"><param name="Name" value="2장"><param name="Local" value="ms-its:book.chm::/ch2.htm"></OBJECT>
          <LI><OBJECT type="text/sitemap"><param name="Name" value="바깥"><param name="Local" value="http://example.com/"></OBJECT>
        </UL></BODY></HTML>
    """.trimIndent()

    @Test fun parseHhc() {
        val e = Hhc.parse(hhc)
        assertEquals(listOf("1장 시작", "1.1 & 절", "2장", "바깥"), e.map { it.name })
        assertEquals(listOf(0, 1, 0, 0), e.map { it.depth })
    }

    @Test fun resolvePaths() {
        assertEquals("sub/a.htm", ChmEpub.resolve("", "sub/a.htm"))
        assertEquals("ch2.htm", ChmEpub.resolve("", "ms-its:book.chm::/ch2.htm"))
        assertEquals("a b.htm#x", ChmEpub.resolve("", "a%20b.htm#x"))
        assertEquals("x/c.htm", ChmEpub.resolve("x/y", "../c.htm"))
        assertNull(ChmEpub.resolve("", "../../etc/passwd"))
        assertNull(ChmEpub.resolve("", "http://example.com/"))
        assertNull(ChmEpub.resolve("", "javascript:alert(1)"))
    }

    @Test fun buildConvertsCp949AndMakesSpine() {
        val dir = Files.createTempDirectory("chm").toFile()
        try {
            val root = File(dir, "OEBPS").apply { mkdirs() }
            val cp = TextEncoding.cp949()
            File(root, "toc.hhc").writeBytes(hhc.toByteArray(cp))
            File(root, "ch1.htm").writeBytes("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=euc-kr\"><script>x()</script></head><body>첫 장 똠</body></html>".toByteArray(cp))
            File(root, "sub").mkdirs()
            File(root, "sub/ch1-1.htm").writeBytes("<html><body>절 내용</body></html>".toByteArray(cp))
            File(root, "ch2.htm").writeBytes("<html><body>둘째 장</body></html>".toByteArray(Charsets.UTF_8))
            File(root, "zz-extra.html").writeBytes("<html><body>부록</body></html>".toByteArray(Charsets.UTF_8))
            File(root, "pic.gif").writeBytes(byteArrayOf(0x47, 0x49, 0x46))
            val b = ChmEpub.build(root, "시험 & 책")
            assertEquals(listOf("ch1.htm", "sub/ch1-1.htm", "ch2.htm", "zz-extra.html"), b.spine)
            val ch1 = File(root, "ch1.htm").readText(Charsets.UTF_8)
            assertTrue(ch1.contains("첫 장 똠"))
            assertFalse(ch1.contains("<script"))
            assertFalse(ch1.contains("euc-kr"))
            val opf = File(root, "content.opf").readText()
            assertTrue(opf.contains("시험 &amp; 책"))
            assertTrue(opf.contains("href=\"pic.gif\""))
            val ncx = File(root, "toc.ncx").readText()
            assertTrue(ncx.contains("sub/ch1-1.htm#a"))
            assertEquals(3, Regex("<navPoint").findAll(ncx).count())
            assertEquals(Regex("<navPoint").findAll(ncx).count(), Regex("</navPoint>").findAll(ncx).count())
            val out = File(dir, "out.epub")
            EpubWriter.zip(dir, "OEBPS/content.opf", out)
            ZipFile(out).use { z ->
                assertEquals("mimetype", z.entries().nextElement().name)
                assertTrue(z.getEntry("META-INF/container.xml") != null)
                assertTrue(z.getEntry("OEBPS/sub/ch1-1.htm") != null)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun mobiOpfDropsMissingNcx() {
        val dir = Files.createTempDirectory("mobi").toFile()
        try {
            File(dir, "part00000.html").writeText("<html/>")
            val opf = File(dir, "content.opf")
            opf.writeText("""<package><manifest><item id="part00000" href="part00000.html" media-type="application/xhtml+xml"/>
                <item id="resource00006" href="resource00006.ncx" media-type="application/x-dtbncx+xml"/></manifest>
                <spine toc="resource00006"><itemref idref="part00000"/></spine></package>""")
            MobiEpub.fixOpf(opf)
            val s = opf.readText()
            assertFalse(s.contains("resource00006"))
            assertTrue(s.contains("<spine>"))
            assertTrue(s.contains("part00000.html"))
        } finally { dir.deleteRecursively() }
    }
}
