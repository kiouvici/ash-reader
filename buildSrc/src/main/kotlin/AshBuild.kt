// SPDX-License-Identifier: AGPL-3.0-or-later
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

// ---------------------------------------------------------------------------
// N-05 ①: 병합된 매니페스트에 INTERNET 권한이 있으면 빌드 실패
// ---------------------------------------------------------------------------
abstract class CheckNoInternetPermissionTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = setOf("android.permission.INTERNET")
        val manifestFile = mergedManifest.get().asFile
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifestFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val found = mutableListOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as Element).getAttributeNS(androidNs, "name")
                if (name in forbidden) found += "<$tag android:name=\"$name\">"
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        if (found.isNotEmpty()) {
            out.writeText("FAIL\n" + found.joinToString("\n") + "\n")
            throw GradleException(
                "N-05 위반 [${variantName.get()}]: 병합된 매니페스트에 금지 권한이 있습니다.\n" +
                    found.joinToString("\n") { "  - $it" } +
                    "\n매니페스트: ${manifestFile.absolutePath}\n" +
                    "INTERNET 권한을 끌어온 라이브러리를 찾아 의존성에서 제거하세요. " +
                    "tools:node=\"remove\" 로 숨기지 않습니다 (M-14).",
            )
        }
        out.writeText("OK: ${forbidden.joinToString()} 없음\n")
        logger.lifecycle("N-05 권한 검사 통과 [${path}]: INTERNET 권한 없음")
    }
}

/** 변형 하나에 대한 검사 태스크 등록. androidComponents.onVariants 안에서 부른다. */
fun Project.registerNoInternetCheck(variantName: String, mergedManifest: Provider<RegularFile>) {
    val cap = variantName.replaceFirstChar { it.uppercase() }
    tasks.register("check${cap}NoInternetPermission", CheckNoInternetPermissionTask::class.java) {
        group = "verification"
        description = "$variantName 병합 매니페스트에 INTERNET 권한이 없는지 검사 (N-05 ①)"
        this.mergedManifest.set(mergedManifest)
        this.variantName.set(variantName)
        report.set(layout.buildDirectory.file("reports/permission-check/$variantName.txt"))
    }
}

/** 모든 변형의 assemble<Variant> 와 check 에 검사 태스크를 연결 (태스크 이름으로 지연 연결). */
fun Project.wireNoInternetChecks() {
    tasks.configureEach {
        val match = Regex("^assemble([A-Z][A-Za-z0-9]*)$").find(name)
        if (match != null) {
            val checkName = "check${match.groupValues[1]}NoInternetPermission"
            dependsOn(tasks.matching { it.name == checkName })
        }
        if (name == "check") {
            dependsOn(tasks.matching { it.name.endsWith("NoInternetPermission") })
        }
    }
}

// ---------------------------------------------------------------------------
// 서명 키 (P4): 워크플로가 시크릿에서 환경변수를 설정한다.
//  - release: ASH_KEYSTORE_FILE / ASH_KEYSTORE_PASSWORD / ASH_KEY_ALIAS / ASH_KEY_PASSWORD
//  - debug  : ASH_DEBUG_KEYSTORE_FILE / ASH_DEBUG_KEYSTORE_PASSWORD / ASH_DEBUG_KEY_ALIAS (키 비밀번호 = 저장소 비밀번호)
// 값이 없으면 null → AGP 기본 debug 키로 대체. main·태그 빌드에서 키가 없으면 워크플로가 실패시킨다.
// ---------------------------------------------------------------------------
class AshKey(val storeFile: File, val storePassword: String, val alias: String, val keyPassword: String)

object AshSigning {
    private fun Project.env(name: String): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

    fun release(project: Project): AshKey? = with(project) {
        val path = env("ASH_KEYSTORE_FILE") ?: return null
        val storePw = env("ASH_KEYSTORE_PASSWORD") ?: return null
        val alias = env("ASH_KEY_ALIAS") ?: return null
        val keyPw = env("ASH_KEY_PASSWORD") ?: return null
        File(path).takeIf { it.exists() }?.let { AshKey(it, storePw, alias, keyPw) }
    }

    fun debug(project: Project): AshKey? = with(project) {
        val path = env("ASH_DEBUG_KEYSTORE_FILE") ?: return null
        val storePw = env("ASH_DEBUG_KEYSTORE_PASSWORD") ?: return null
        val alias = env("ASH_DEBUG_KEY_ALIAS") ?: return null
        File(path).takeIf { it.exists() }?.let { AshKey(it, storePw, alias, storePw) }
    }
}

/** versionCode = 1000 + GITHUB_RUN_NUMBER (CI). 로컬 빌드는 1000. */
fun Project.ashVersionCode(): Int =
    1000 + (providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 0)

/**
 * 사용자에게 보이는 판 이름. CI 는 1.0.0-rc.<실행 번호>, 로컬은 1.0.0-dev.
 * 정식 배포 때는 -Pash.versionName=1.0.0 으로 덮는다.
 */
fun Project.ashVersionName(): String {
    providers.gradleProperty("ash.versionName").orNull?.takeIf { it.isNotBlank() }?.let { return it }
    val run = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
    return if (run != null) "$ASH_VERSION_BASE-rc.$run" else "$ASH_VERSION_BASE-dev"
}

const val ASH_VERSION_BASE = "1.0.0"

/** 빌드 커밋 (CI의 GITHUB_SHA, 로컬은 "local"). */
fun Project.ashGitSha(): String =
    providers.environmentVariable("GITHUB_SHA").orNull?.takeIf { it.isNotBlank() } ?: "local"

// ---------------------------------------------------------------------------
// 측정 앱: test-files/public 을 assets/testfiles/ 로 묶는다 (숨김 파일 제외)
// ---------------------------------------------------------------------------
abstract class BundleTestFilesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val src = sourceDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dest = File(out, "testfiles").apply { mkdirs() }
        var count = 0
        src.walkTopDown()
            .onEnter { it == src || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach {
                it.copyTo(File(dest, it.relativeTo(src).path), overwrite = true)
                count++
            }
        logger.lifecycle("시험 파일 ${count}개를 assets/testfiles/ 로 묶음")
    }
}
