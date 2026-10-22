// SPDX-License-Identifier: AGPL-3.0-or-later
// C1 오픈소스 고지: 배포 APK(release) 에 들어가는 외부 자바·코틀린 모듈과 POM 의 라이선스 이름을 뽑는다.
// 결과(build/third-party/android-deps.tsv)를 scripts/third-party/gen_notices.py 가 읽어
// THIRD_PARTY_NOTICES 와 앱 assets/licenses 를 만든다. 빌드에는 영향 없음(손으로 돌리는 태스크).
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Element

fun Project.registerThirdPartyDepsTask(configurationName: String = "releaseRuntimeClasspath") {
    val confs = configurations
    val deps = dependencies
    val outFile = layout.buildDirectory.file("third-party/android-deps.tsv")
    tasks.register("ashThirdPartyDeps") {
        group = "help"
        description = "배포 APK 에 들어가는 외부 모듈과 라이선스(POM) 목록"
        notCompatibleWithConfigurationCache("의존성 해석 결과와 POM 을 실행 시점에 읽는다")
        outputs.upToDateWhen { false }
        doLast {
            val ids = confs.getByName(configurationName).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .sortedBy { "${it.group}:${it.module}" }
            val cache = HashMap<String, PomInfo?>()
            fun pom(g: String, a: String, v: String): PomInfo? = cache.getOrPut("$g:$a:$v") {
                runCatching {
                    val f = confs.detachedConfiguration(deps.create("$g:$a:$v@pom")).apply { isTransitive = false }.resolve().firstOrNull()
                    f?.let { PomInfo.parse(it) }
                }.getOrNull()
            }
            val lines = ids.map { id ->
                var info = pom(id.group, id.module, id.version)
                val names = LinkedHashSet<String>()
                val urls = LinkedHashSet<String>()
                var depth = 0
                while (info != null && depth < 6) {
                    if (info.licenses.isNotEmpty()) { info.licenses.forEach { (n, u) -> names += n; if (u.isNotBlank()) urls += u }; break }
                    val p = info.parent ?: break
                    info = pom(p.first, p.second, p.third); depth++
                }
                val first = pom(id.group, id.module, id.version)
                listOf(
                    "${id.group}:${id.module}:${id.version}",
                    names.joinToString(" | ").ifEmpty { "?" },
                    urls.joinToString(" | "),
                    first?.name.orEmpty(),
                    first?.url.orEmpty(),
                ).joinToString("\t")
            }
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            // 각 jar·aar 안의 META-INF/NOTICE·LICENSE 원문(아파치 2.0 NOTICE 고지 근거)
            val noticeDir = File(f.parentFile, "notices").apply { deleteRecursively(); mkdirs() }
            val arts = confs.getByName(configurationName).incoming.artifactView { lenient(true) }.artifacts
            for (a in arts) {
                val cid = a.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
                val found = runCatching { metaInfTexts(a.file) }.getOrDefault(emptyMap())
                for ((name, text) in found) {
                    File(noticeDir, "${cid.group}__${cid.module}__${name.substringAfterLast('/')}.txt").writeText(text)
                }
            }
            f.writeText(lines.joinToString("\n", postfix = "\n"))
            lines.forEach { println("ASH-DEP\t$it") }
            println("ashThirdPartyDeps: ${lines.size}개 → ${f}")
        }
    }
}

/** jar·aar(안의 classes.jar 포함)에서 META-INF/NOTICE*, META-INF/LICENSE* 를 읽는다. */
private fun metaInfTexts(file: File): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    fun want(n: String) = n.startsWith("META-INF/", true) && n.substringAfterLast('/').let { b ->
        b.startsWith("NOTICE", true) || b.startsWith("LICENSE", true)
    } && n.count { it == '/' } == 1
    fun scan(input: InputStream) {
        val z = ZipInputStream(input)
        while (true) {
            val e = z.nextEntry ?: break
            if (!e.isDirectory && want(e.name)) out[e.name] = String(z.readBytes(), Charsets.UTF_8)
        }
    }
    if (!file.name.endsWith(".jar") && !file.name.endsWith(".aar")) return out
    ZipFile(file).use { zf ->
        for (e in zf.entries()) {
            if (e.isDirectory) continue
            if (want(e.name)) out[e.name] = zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
            else if (e.name == "classes.jar") zf.getInputStream(e).use { scan(it) }
        }
    }
    return out
}

private class PomInfo(
    val name: String,
    val url: String,
    val licenses: List<Pair<String, String>>,
    val parent: Triple<String, String, String>?,
) {
    companion object {
        fun parse(f: File): PomInfo {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(f)
            val root = doc.documentElement
            fun Element.child(name: String): Element? {
                val nl = childNodes
                for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == name) return n }
                return null
            }
            fun Element.text(name: String): String = child(name)?.textContent?.trim().orEmpty()
            val lic = root.child("licenses")?.let { ls ->
                val out = ArrayList<Pair<String, String>>()
                val nl = ls.childNodes
                for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == "license") out += n.text("name") to n.text("url") }
                out
            }.orEmpty()
            val parent = root.child("parent")?.let { p -> Triple(p.text("groupId"), p.text("artifactId"), p.text("version")) }
            return PomInfo(root.text("name"), root.text("url"), lic, parent)
        }
    }
}
