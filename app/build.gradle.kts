// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 내장 Kotlin 사용: org.jetbrains.kotlin.android 는 적용하지 않는다.
    alias(libs.plugins.kotlin.compose)
}

// versionCode = 1000 + GITHUB_RUN_NUMBER (CI). 로컬 빌드는 1000.
val ciRunNumber: Int = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 0

// N-05 음성 시험 전용: -Pash.negativeTest=true 일 때만 INTERNET 권한을 넣은 빌드 유형(negativeProbe)을 만든다.
val negativeTest: Boolean = providers.gradleProperty("ash.negativeTest").orNull == "true"

// 서명: 환경변수(워크플로가 시크릿에서 설정)가 모두 있으면 release·debug 모두 그 키로 서명.
// 없으면 AGP 기본 debug 키. main·태그 빌드에서 키가 없으면 워크플로가 실패시킨다.
val releaseKeystorePath: String? = providers.environmentVariable("ASH_KEYSTORE_FILE").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("ASH_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("ASH_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("ASH_KEY_PASSWORD").orNull
val hasReleaseSigning: Boolean =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).exists()

android {
    namespace = "kr.vici.ashreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.vici.ashreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1000 + ciRunNumber
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("ash") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("ash")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("ash")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        if (negativeTest) {
            // src/negativeProbe/AndroidManifest.xml 이 INTERNET 권한을 추가한다. 배포 금지.
            create("negativeProbe") {
                initWith(getByName("debug"))
                signingConfig = signingConfigs.getByName("debug")
                applicationIdSuffix = ".negativeprobe"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

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
        logger.lifecycle("N-05 권한 검사 통과 [${variantName.get()}]: INTERNET 권한 없음")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<CheckNoInternetPermissionTask>("check${cap}NoInternetPermission") {
            group = "verification"
            description = "${variant.name} 병합 매니페스트에 INTERNET 권한이 없는지 검사 (N-05 ①)"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variantName.set(variant.name)
            report.set(layout.buildDirectory.file("reports/permission-check/${variant.name}.txt"))
        }
    }
}

// 모든 변형의 assemble<Variant> 와 check 에 연결 (태스크 이름으로 지연 연결)
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
