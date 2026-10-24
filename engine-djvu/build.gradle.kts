// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-djvu — DjVu 쪽 그리기(DjVuLibre 3.5.30 libdjvu, GPL-2.0-or-later, 정적 링크, JNI). 기술 설계서 2장·R-16.
// D-001 A안: GPL/AGPL 허용. DjVuLibre 는 "GPL v2 or (at your option) any later version" 이라 AGPL-3.0-or-later 앱과 함께 배포할 수 있다.
// 서드파티 소스는 저장소에 넣지 않는다. fetchThirdParty 가 release.3.5.30 태그를 받아 커밋 SHA 를 확인한다.
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

val thirdParty = layout.buildDirectory.dir("third_party").get().asFile
val abis = providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")

android {
    namespace = "kr.vici.ashreader.engine.djvu"
    compileSdk = 37
    // 필수 1: minSdk(26)보다 새 API 호출은 오류로 본다. CI compile job 의 lintDebug 가 검사한다.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        abortOnError = true
        checkDependencies = false
        checkReleaseBuilds = false
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DASH_THIRD_PARTY=${thirdParty.invariantSeparatorsPath}") // Windows 경로도 '/'
                cppFlags += listOf("-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = false } }
}

val fetchThirdParty by tasks.registering(Exec::class) {
    group = "djvu"
    description = "DjVuLibre 3.5.30 을 받아 커밋 확인"
    val script = file("scripts/fetch-third-party.sh")
    inputs.file(script)
    val marker = File(thirdParty, "djvulibre/.ash-sha")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    commandLine("bash", script.absolutePath, thirdParty.absolutePath)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach { dependsOn(fetchThirdParty) }

dependencies {
    testImplementation(libs.junit)
}
