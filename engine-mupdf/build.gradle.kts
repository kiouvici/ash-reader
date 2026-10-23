// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-mupdf — MuPDF 1.28.5 + ASH 한국어 줄바꿈 패치(word-break: keep-all, 금칙)를 NDK로 빌드한다.
// 기술 설계서 3.2(한국어 줄바꿈·금칙), 9장 실험 ④.
//
// MuPDF 소스는 저장소에 넣지 않는다. `fetchMupdf` 태스크가 태그 1.28.5를 받아(서브모듈 포함)
// `patches/*.patch`를 적용해 build/mupdf-src 에 둔다. ndk-build는 MuPDF의 platform/java/Android.mk를 쓴다.
import java.security.MessageDigest
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

val mupdfVersion = "1.28.5"
val mupdfSrc = layout.buildDirectory.dir("mupdf-src").get().asFile

android {
    namespace = "kr.vici.ashreader.engine.mupdf"
    compileSdk = 37
    // 필수 1: minSdk(26)보다 새 API 호출은 오류로 본다. CI compile job 의 lintDebug 가 검사한다.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        abortOnError = true
        checkDependencies = false
        checkReleaseBuilds = false
        // MuPDF 원본 Java 바인딩(build/mupdf-src)은 lint.xml 에서 뺀다
        lintConfig = file("lint.xml")
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "-j4",
                    "APP_PLATFORM=android-26",
                    // PDF JS(mujs)·바코드·OCR 끔. 16KB 페이지 정렬은 NDK r28 기본값.
                    "MUPDF_EXTRA_CFLAGS=-DFZ_ENABLE_JS=0 -DFZ_ENABLE_BARCODE=0 -DFZ_ENABLE_OCR_OUTPUT=0",
                )
            }
        }
        ndk {
            // 배포는 arm64(M-10). armeabi-v7a는 최저 기기 시험용, x86_64는 에뮬레이터용.
            abiFilters += (providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", File(mupdfSrc, "platform/java/src"))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = File(mupdfSrc, "platform/java/Android.mk")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

// 1) MuPDF 소스 받기 + 패치 적용 (한 번만, 결과 표지 파일로 캐시)
val fetchMupdf by tasks.registering(Exec::class) {
    group = "mupdf"
    description = "MuPDF $mupdfVersion 소스(서브모듈 포함)를 받아 ASH 패치를 적용한다"
    val patchDir = file("patches")
    // 패치를 더하거나 바꾸면 표지 이름이 바뀌어 소스를 새로 받아 다시 적용한다(I-713)
    val patchTag = MessageDigest.getInstance("SHA-256").let { md ->
        patchDir.listFiles { f -> f.name.endsWith(".patch") }.orEmpty().sortedBy { it.name }.forEach { md.update(it.readBytes()) }
        md.digest().take(6).joinToString("") { "%02x".format(it) }
    }
    val marker = File(mupdfSrc, ".ash-patched-$mupdfVersion-$patchTag")
    inputs.dir(patchDir)
    outputs.file(marker)
    onlyIf { !marker.exists() }
    commandLine("bash", file("scripts/fetch-mupdf.sh").absolutePath, mupdfVersion, mupdfSrc.absolutePath, patchDir.absolutePath, "$mupdfVersion-$patchTag")
}

// ndk-build·자바 컴파일 전에 소스가 있어야 한다
tasks.matching { it.name.startsWith("configureNdkBuild") || it.name.startsWith("generate") && it.name.endsWith("Sources") || it.name.startsWith("compile") }
    .configureEach { dependsOn(fetchMupdf) }

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
