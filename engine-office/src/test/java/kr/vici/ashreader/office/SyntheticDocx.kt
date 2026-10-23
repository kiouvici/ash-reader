// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Word 원본 보기(F-30) 시험용 합성 DOCX. 공개 시험 파일에 없는 기능을 담는다:
 * 구역 2개(세로 A4 + 가로 A4, 둘째 구역 쪽 번호 10부터), 첫 쪽 머리글, PAGE/NUMPAGES 필드(단순·복합),
 * 오른쪽 탭+점 채움, 번호 목록(내어쓰기), 병합·음영 표, 글 뒤 떠 있는 그림(쪽 기준), 둘러싸기 그림, 각주, 쪽 나눔.
 */
object SyntheticDocx {
    private const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""

    /** 1×1 빨간 PNG */
    val PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")

    private fun p(text: String, pPr: String = "", rPr: String = "") = "<w:p><w:pPr>$pPr</w:pPr><w:r><w:rPr>$rPr</w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    private fun drawing(anchor: Boolean, wrap: String, behind: Boolean): String {
        val ext = "<wp:extent cx=\"1270000\" cy=\"635000\"/>"
        val graphic = "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:blipFill><a:blip r:embed=\"rIdImg\"/></pic:blipFill></pic:pic></a:graphicData></a:graphic>"
        return if (!anchor) "<w:drawing><wp:inline>$ext$graphic</wp:inline></w:drawing>"
        else "<w:drawing><wp:anchor behindDoc=\"${if (behind) 1 else 0}\" simplePos=\"0\" relativeHeight=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">" +
            "<wp:positionH relativeFrom=\"page\"><wp:posOffset>3810000</wp:posOffset></wp:positionH>" +
            "<wp:positionV relativeFrom=\"page\"><wp:posOffset>2540000</wp:posOffset></wp:positionV>$ext$wrap$graphic</wp:anchor></w:drawing>"
    }

    fun build(): ByteArray {
        val body = StringBuilder()
        body.append(p("합성 시험 문서", "<w:jc w:val=\"center\"/><w:spacing w:after=\"240\"/>", "<w:b/><w:sz w:val=\"32\"/>"))
        // 오른쪽 탭 + 점 채움
        body.append("<w:p><w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"9000\"/></w:tabs></w:pPr>" +
            "<w:r><w:t>1장 시작하기</w:t></w:r><w:r><w:tab/><w:t>12</w:t></w:r></w:p>")
        for (t in listOf("첫째 항목", "둘째 항목", "셋째 항목")) body.append("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>$t</w:t></w:r></w:p>")
        // 표: 가로 병합 + 음영 + 테두리
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"6000\" w:type=\"dxa\"/><w:tblBorders><w:top w:val=\"single\" w:sz=\"8\"/><w:left w:val=\"single\" w:sz=\"8\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"8\"/><w:right w:val=\"single\" w:sz=\"8\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" +
            "<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:shd w:val=\"clear\" w:fill=\"D9D9D9\"/></w:tcPr>${p("병합 머리 칸")}</w:tc></w:tr>" +
            "<w:tr><w:tc>${p("왼칸")}</w:tc><w:tc><w:tcPr><w:vAlign w:val=\"center\"/></w:tcPr>${p("오른칸")}</w:tc></w:tr></w:tbl>")
        // 각주 + 글 뒤 떠 있는 그림 + 둘러싸기 그림
        body.append("<w:p><w:r><w:t>각주가 붙는 문장</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:footnoteReference w:id=\"1\"/></w:r>" +
            "<w:r>${drawing(true, "<wp:wrapNone/>", true)}</w:r></w:p>")
        body.append("<w:p><w:r>${drawing(true, "<wp:wrapSquare wrapText=\"bothSides\"/>", false)}</w:r><w:r><w:t>둘러싸기 그림 옆 글</w:t></w:r></w:p>")
        body.append("<w:p><w:r><w:t>글자처럼 놓인 그림:</w:t></w:r><w:r>${drawing(false, "", false)}</w:r></w:p>")
        // 쪽 나눔
        body.append("<w:p><w:r><w:t>나눔 앞</w:t></w:r><w:r><w:br w:type=\"page\"/></w:r><w:r><w:t>나눔 뒤 둘째 쪽</w:t></w:r></w:p>")
        // 첫 구역 끝(세로 A4, 첫 쪽 머리글 따로)
        body.append("<w:p><w:pPr><w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdH1\"/><w:headerReference w:type=\"first\" r:id=\"rIdH2\"/>" +
            "<w:footerReference w:type=\"default\" r:id=\"rIdF1\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/><w:titlePg/></w:sectPr></w:pPr>" +
            "<w:r><w:t>첫 구역 끝</w:t></w:r></w:p>")
        body.append(p("가로 구역 본문"))
        body.append("<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/><w:pgMar w:top=\"1000\" w:right=\"1000\" w:bottom=\"1000\" w:left=\"1000\" w:header=\"500\" w:footer=\"500\"/>" +
            "<w:pgNumType w:start=\"10\"/></w:sectPr>")
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $NS><w:body>$body</w:body></w:document>"
        val hdr1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:hdr $NS><w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t xml:space=\"preserve\">머리글 기본 </w:t></w:r>" +
            "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:hdr>"
        val hdr2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:hdr $NS>${p("첫 쪽 머리글")}</w:hdr>"
        val ftr1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:ftr $NS><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" +
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
            "<w:r><w:t>3</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r><w:r><w:t xml:space=\"preserve\"> / </w:t></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> NUMPAGES </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
            "<w:r><w:t>9</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>"
        val notes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:footnotes $NS><w:footnote w:id=\"0\" w:type=\"separator\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>" +
            "<w:footnote w:id=\"1\"><w:p><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> 합성 각주 본문</w:t></w:r></w:p></w:footnote></w:footnotes>"
        val numbering = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:numbering $NS><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles $NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"바탕\"/>" +
            "<w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdH1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>" +
            "<Relationship Id=\"rIdH2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header2.xml\"/>" +
            "<Relationship Id=\"rIdF1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>" +
            "<Relationship Id=\"rIdImg\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image1.png\"/></Relationships>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray())
            put("word/document.xml", doc.toByteArray())
            put("word/header1.xml", hdr1.toByteArray()); put("word/header2.xml", hdr2.toByteArray()); put("word/footer1.xml", ftr1.toByteArray())
            put("word/footnotes.xml", notes.toByteArray()); put("word/numbering.xml", numbering.toByteArray()); put("word/styles.xml", styles.toByteArray())
            put("word/_rels/document.xml.rels", rels.toByteArray()); put("word/media/image1.png", PNG)
        }
        return bos.toByteArray()
    }

    /**
     * 5묶음 시험용: 2단 구역(단 나눔 포함), 과부·고아 줄 방지 문단, 제목 스타일 2개(목차), 표 스타일 조건부 서식(첫 행·줄무늬), EMF 그림.
     */
    fun buildBatch5(): ByteArray {
        val body = StringBuilder()
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>1장 다단 시험</w:t></w:r></w:p>")
        val long = "가나다라마바사 아자차카타파하 다단 본문이 길게 이어지는 문장입니다. ".repeat(12)
        repeat(6) { body.append(p(long)) }
        body.append("<w:p><w:r><w:t>단 나눔 앞</w:t></w:r><w:r><w:br w:type=\"column\"/></w:r><w:r><w:t>단 나눔 뒤</w:t></w:r></w:p>")
        body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"GridTable\"/><w:tblW w:w=\"4000\" w:type=\"dxa\"/><w:tblLook w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>" +
            "<w:tr><w:tc>${p("머리1")}</w:tc><w:tc>${p("머리2")}</w:tc></w:tr>" +
            "<w:tr><w:tc>${p("줄1")}</w:tc><w:tc>${p("값1")}</w:tc></w:tr>" +
            "<w:tr><w:tc>${p("줄2")}</w:tc><w:tc>${p("값2")}</w:tc></w:tr></w:tbl>")
        body.append("<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1270000\" cy=\"635000\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:blipFill><a:blip r:embed=\"rIdEmf\"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>2장 끝</w:t></w:r></w:p>")
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"851\" w:footer=\"992\"/>" +
            "<w:cols w:num=\"2\" w:space=\"720\"/></w:sectPr>")
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $NS><w:body>$body</w:body></w:document>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles $NS><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:pPr><w:widowControl/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"GridTable\"><w:name w:val=\"Grid Table\"/><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>" +
            "<w:tblStylePr w:type=\"firstRow\"><w:rPr><w:b/><w:color w:val=\"FFFFFF\"/></w:rPr><w:tcPr><w:shd w:val=\"clear\" w:fill=\"4472C4\"/></w:tcPr></w:tblStylePr>" +
            "<w:tblStylePr w:type=\"band1Horz\"><w:tcPr><w:shd w:val=\"clear\" w:fill=\"D9E2F3\"/></w:tcPr></w:tblStylePr></w:style></w:styles>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdEmf\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image2.emf\"/></Relationships>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".toByteArray())
            put("word/document.xml", doc.toByteArray()); put("word/styles.xml", styles.toByteArray())
            put("word/_rels/document.xml.rels", rels.toByteArray()); put("word/media/image2.emf", SyntheticMetafile.emf())
        }
        return bos.toByteArray()
    }
}
