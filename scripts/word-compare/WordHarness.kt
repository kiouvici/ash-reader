// SPDX-License-Identifier: AGPL-3.0-or-later
// 호스트 JVM 측정용 진입점(앱에 들어가지 않음). :engine-office 변환기 + :engine-mupdf PagedComposer 를 호스트 MuPDF Java 바인딩(libmupdf_java64.so)으로 돌린다.
// 빌드: kotlinc(또는 kotlin-compiler-embeddable) -cp libmupdf.jar <PagedWord·WordHtml·DocxPaged·RtfPaged·DocxConverter·PagedComposer·MupdfLock·이 파일>
// 실행: java -Djava.library.path=<so 폴더> -cp ... MainKt <문서|synthetic> <출력 폴더>  (글꼴 경로는 FONT_DIR 환경변수, 없으면 app/src/main/res/font)
import kr.vici.ashreader.office.*
import kr.vici.ashreader.engine.mupdf.PagedComposer
import java.io.File
fun main(a: Array<String>) {
    val src = if (a[0] == "synthetic") File(a[1] + ".docx").also { File(a[1]).parentFile.mkdirs(); it.writeBytes(SyntheticDocx.build()) } else File(a[0]); val outDir = File(a[1]); outDir.deleteRecursively(); outDir.mkdirs()
    val F = (System.getenv("FONT_DIR") ?: "app/src/main/res/font") + "/"
    val css = PagedWord.fontCss(F + "nanum_myeongjo_regular.ttf", F + "nanum_myeongjo_bold.ttf", F + "nanum_gothic_regular.ttf", null, F + "ridibatang.otf")
    val t0 = System.nanoTime()
    val pw = when {
        src.name.endsWith(".docx") -> DocxPaged().convert(src.readBytes())
        else -> RtfPaged.convert(src.readBytes())
    }
    val t1 = System.nanoTime()
    pw.writeTo(File(outDir, "paged"))
    if (System.getenv("DBG") != null) PagedComposer.debug = { println(it) }
    val r = PagedComposer.compose(File(outDir, "paged"), css, File(outDir, "out.pdf"))
    val t2 = System.nanoTime()
    println("stats=${pw.stats} pages=${r.pages} convertMs=${(t1-t0)/1e6} composeMs=${(t2-t1)/1e6}")
}
