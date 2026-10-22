// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-convert — 리플로 원본을 합성 EPUB 으로 바꿔 MuPDF 에 넣는다(기술 설계서 K-3).
//  - MOBI·AZW3(KF8): libmobi 0.12 (LGPL-3.0-or-later) 정적 링크, JNI
//  - CHM: libmspack chmd (LGPL-2.1) 정적 링크, JNI → HHC 목차·HTML(UTF-8 로 바꿈) → EPUB
//  - TXT 인코딩 판별(F-32): 순수 Kotlin (JVM 단위 테스트)
// 서드파티 소스는 저장소에 넣지 않는다. fetchThirdParty 가 고정 커밋을 받아(SHA 확인) build/third_party 에 둔다.
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

val thirdParty = layout.buildDirectory.dir("third_party").get().asFile
val abis = providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")

android {
    namespace = "kr.vici.ashreader.engine.convert"
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
                arguments += listOf("-DASH_THIRD_PARTY=${thirdParty.absolutePath}")
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
    group = "convert"
    description = "libmobi·libmspack 고정 커밋을 받아 확인"
    val script = file("scripts/fetch-third-party.sh")
    inputs.file(script)
    val marker = File(thirdParty, ".ash-ok-v1")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    commandLine("bash", script.absolutePath, thirdParty.absolutePath)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach { dependsOn(fetchThirdParty) }

dependencies {
    testImplementation(libs.junit)
}
