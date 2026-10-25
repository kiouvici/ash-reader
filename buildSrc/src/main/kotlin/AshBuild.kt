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
// N-05 ①(작업 06·D-024 로 바뀜): 병합된 매니페스트에 위험 권한이 있으면 빌드 실패.
// 본 앱은 책 정보 찾기(인터넷, 기본 꺼짐)를 위해 INTERNET 만 허용하고, 측정 앱은 지금처럼 INTERNET 도 금지.
// ---------------------------------------------------------------------------

/** 어느 앱에도 넣지 않는 권한(위치·카메라·마이크·연락처·전화·문자·달력·신체·주변 기기·미디어 위치). */
val ASH_FORBIDDEN_PERMISSIONS = listOf(
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION",
    "CAMERA", "RECORD_AUDIO", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
    "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS", "ANSWER_PHONE_CALLS",
    "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH",
    "READ_CALENDAR", "WRITE_CALENDAR", "BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "ACTIVITY_RECOGNITION",
    "NEARBY_WIFI_DEVICES", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "UWB_RANGING",
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED",
).map { "android.permission.$it" }

abstract class CheckPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    /** true = INTERNET 허용(본 앱, 책 정보 찾기). */
    @get:Input
    abstract val allowInternet: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = ASH_FORBIDDEN_PERMISSIONS.toSet() + if (allowInternet.get()) emptySet() else setOf("android.permission.INTERNET")
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
                    "그 권한을 끌어온 라이브러리를 찾아 의존성에서 제거하세요. " +
                    "tools:node=\"remove\" 로 숨기지 않습니다 (M-14).",
            )
        }
        out.writeText("OK: 금지 권한 ${forbidden.size}개 없음" + (if (allowInternet.get()) " (INTERNET 허용)" else "") + "\n")
        logger.lifecycle("N-05 권한 검사 통과 [${path}]: 금지 권한 없음" + if (allowInternet.get()) " · INTERNET 은 책 정보 찾기용 허용" else " · INTERNET 없음")
    }
}

/** 변형 하나에 대한 검사 태스크 등록. androidComponents.onVariants 안에서 부른다. */
fun Project.registerPermissionCheck(variantName: String, mergedManifest: Provider<RegularFile>, allowInternet: Boolean) {
    val cap = variantName.replaceFirstChar { it.uppercase() }
    tasks.register("check${cap}Permissions", CheckPermissionsTask::class.java) {
        group = "verification"
        description = "$variantName 병합 매니페스트에 금지 권한이 없는지 검사 (N-05 ①)"
        this.mergedManifest.set(mergedManifest)
        this.variantName.set(variantName)
        this.allowInternet.set(allowInternet)
        report.set(layout.buildDirectory.file("reports/permission-check/$variantName.txt"))
    }
}

/** 모든 변형의 assemble<Variant> 와 check 에 검사 태스크를 연결 (태스크 이름으로 지연 연결). */
fun Project.wirePermissionChecks() {
    tasks.configureEach {
        val match = Regex("^assemble([A-Z][A-Za-z0-9]*)$").find(name)
        if (match != null) {
            val checkName = "check${match.groupValues[1]}Permissions"
            dependsOn(tasks.matching { it.name == checkName })
        }
        if (name == "check") {
            dependsOn(tasks.matching { it.name.endsWith("Permissions") && it.name.startsWith("check") && it.name != "check" })
        }
    }
}

// ---------------------------------------------------------------------------
// N-05 ⑤(작업 06): 네트워크 호출은 책 정보 모듈 한 곳(app/…/kr/vici/ashreader/net/)에서만.
// 모든 모듈의 Kotlin·Java 소스에서 네트워크 API 를 쓰는 줄을 찾아 그 폴더 밖이면 빌드 실패.
// ---------------------------------------------------------------------------
abstract class CheckNetworkConfinedTask : DefaultTask() {
    /** 검사할 소스 뿌리들(모듈마다 src/main/java). */
    @get:Input
    abstract val roots: org.gradle.api.provider.ListProperty<String>

    /** 네트워크를 써도 되는 폴더(절대 경로). */
    @get:Input
    abstract val allowedDir: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val net = Regex(
            "import\\s+java\\.net\\.(URL|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|InetSocketAddress)\\b|" +
                "import\\s+javax\\.net\\b|java\\.net\\.(URL|HttpURLConnection|Socket)\\b|" +
                "\\bHttpsURLConnection\\b|\\bopenConnection\\s*\\(|\\bokhttp3\\b|\\bio\\.ktor\\b|\\bretrofit2\\b|\\bandroid\\.webkit\\b|\\bWebView\\b",
        )
        val allowed = File(allowedDir.get()).canonicalFile
        val bad = mutableListOf<String>()
        for (r in roots.get()) {
            val root = File(r)
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                if (f.canonicalFile.startsWith(allowed)) return@forEach
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//")
                    if (net.containsMatchIn(code)) bad += "${f.path}:${i + 1}: ${line.trim()}"
                }
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        if (bad.isNotEmpty()) {
            out.writeText("FAIL\n" + bad.joinToString("\n") + "\n")
            throw GradleException("N-05 위반 [네트워크]: 책 정보 모듈(${allowed.path}) 밖에서 네트워크를 씁니다.\n" + bad.joinToString("\n") { "  - $it" })
        }
        out.writeText("OK\n")
        logger.lifecycle("N-05 네트워크 검사 통과: 네트워크 호출은 ${allowed.name}/ 에만 있음")
    }
}

/** 루트 아래 모든 모듈의 src/main/java 를 검사하는 :app:checkNetworkConfined 등록. */
fun Project.registerNetworkConfinedCheck(allowedDir: File) {
    val t = tasks.register("checkNetworkConfined", CheckNetworkConfinedTask::class.java) {
        group = "verification"
        description = "네트워크 호출이 책 정보 모듈에만 있는지 검사 (N-05 ⑤)"
        roots.set(rootProject.rootDir.listFiles().orEmpty().filter { File(it, "src/main/java").isDirectory }.map { File(it, "src/main/java").path })
        this.allowedDir.set(allowedDir.path)
        report.set(layout.buildDirectory.file("reports/permission-check/network.txt"))
        outputs.upToDateWhen { false }
    }
    tasks.configureEach { if (name == "check" || name.startsWith("assemble")) dependsOn(t) }
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

/** 작업 06: 비밀 값(책 정보 Open API 키) — 환경변수 [env] → local.properties 의 [name] → 빈 값. 값은 로그에 쓰지 않는다. */
fun Project.ashSecret(name: String, env: String): String =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { f -> java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty(name) }.orEmpty()

private fun Project.ashCiRun(): Int? =
    providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

/** 노트북 빌드의 기준 CI 실행 번호 (-Pash.baseRun 또는 ASH_BASE_RUN, scripts/local-build.ps1 이 채운다). */
private fun Project.ashBaseRun(): Int? =
    (providers.gradleProperty("ash.baseRun").orNull ?: providers.environmentVariable("ASH_BASE_RUN").orNull)
        ?.trim()?.toIntOrNull()

/**
 * versionCode = 1000 + GITHUB_RUN_NUMBER (CI).
 * 로컬은 1000 + baseRun + 1 (같은 번호 CI 판 위에 덮어 설치), baseRun 이 없으면 1000.
 */
fun Project.ashVersionCode(): Int {
    ashCiRun()?.let { return 1000 + it }
    return 1000 + (ashBaseRun()?.let { it + 1 } ?: 0)
}

/**
 * 사용자에게 보이는 판 이름. CI 는 1.0.0-rc.<실행 번호>,
 * 로컬은 baseRun 이 있으면 1.0.0-rc.<baseRun>-local, 없으면 1.0.0-dev.
 * 정식 배포 때는 -Pash.versionName=1.0.0 으로 덮는다.
 */
fun Project.ashVersionName(): String {
    providers.gradleProperty("ash.versionName").orNull?.takeIf { it.isNotBlank() }?.let { return it }
    ashCiRun()?.let { return "$ASH_VERSION_BASE-rc.$it" }
    return ashBaseRun()?.let { "$ASH_VERSION_BASE-rc.$it-local" } ?: "$ASH_VERSION_BASE-dev"
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
