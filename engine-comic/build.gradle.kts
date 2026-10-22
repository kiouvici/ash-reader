// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-comic — 만화(CBZ·CBR) 읽기, 칸 인식(F-72), AI 화질 향상(F-73). 기술 설계서 K-7·K-9, 9장 실험 ⑤.
//  - CBZ: java.util.zip (파일명 UTF-8 → CP949 대체)
//  - CBR: libarchive 3.8.10 (RAR4·RAR5, solid) 정적 링크, JNI
//  - 칸 인식: Kumiko(AGPL-3.0) 방식을 순수 Kotlin으로 옮김 (JVM 단위 테스트로 채점)
//  - 화질 향상: ncnn 20261020(BSD-3, Vulkan 프리빌드) + Real-CUGAN 2x(MIT) 모델
// 서드파티 소스·프리빌드·모델은 저장소에 넣지 않는다. fetchThirdParty 가 SHA-256 을 확인하며 build/third_party 에 받는다.
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

val thirdParty = layout.buildDirectory.dir("third_party").get().asFile
val abis = providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")

android {
    namespace = "kr.vici.ashreader.engine.comic"
    compileSdk = 37
    // ncnn 20261020 프리빌드는 clang 21(NDK r30 계열) libc++(std::__hash_memory)로 빌드됐다.
    // 이 모듈만 그 NDK 를 쓴다(c++_static 이라 다른 모듈과 충돌 없음). CI 가 sdkmanager 목록에서 r30 을 골라 ashComicNdk 로 넘긴다.
    ndkVersion = providers.gradleProperty("ashComicNdk").orNull ?: "30.0.14904198"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DASH_THIRD_PARTY=${thirdParty.absolutePath}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") { assets.srcDir(File(thirdParty, "assets")) }
    }

    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = false } }
}

val fetchThirdParty by tasks.registering(Exec::class) {
    group = "comic"
    description = "libarchive·ncnn 프리빌드·Real-CUGAN 모델을 받아 SHA-256 확인"
    val script = file("scripts/fetch-third-party.sh")
    inputs.file(script)
    val marker = File(thirdParty, ".ash-ok-v1")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    commandLine("bash", script.absolutePath, thirdParty.absolutePath)
}

tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") ||
        (it.name.startsWith("generate") && it.name.endsWith("Assets")) ||
        (it.name.startsWith("merge") && it.name.endsWith("Assets"))
}.configureEach { dependsOn(fetchThirdParty) }

dependencies {
    testImplementation(libs.junit)
}
