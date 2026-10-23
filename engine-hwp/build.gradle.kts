// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-hwp — HWP 5.0·HWPX 원본 보기. rhwp v0.8.7(1a76570, ASH 패치 patches/) + resvg 를 cargo-ndk 로 libash_hwp.so 로 빌드한다.
// 기술 설계서 3.3 A안, 9장 실험 ①.
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

val rustDir = file("rust")
val jniOut = layout.buildDirectory.dir("rustJniLibs").get().asFile
val abis = providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

android {
    namespace = "kr.vici.ashreader.engine.hwp"
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
    }
    sourceSets {
        getByName("main") { jniLibs.srcDir(jniOut) }
        // 공개 시험 파일(저장소 test-files/public/hwp)을 계측 시험 assets로 그대로 쓴다
        getByName("androidTest") { assets.srcDir(rootProject.file("test-files/public/hwp")) }
    }
    buildTypes { release { isMinifyEnabled = false } }
}

// rhwp 받기 + ASH 패치(patches/*.patch) 적용. 스크립트가 커밋·패치 해시 표지로 이미 받은 것을 건너뛰므로 매번 부른다.
val rhwpPatches = file("patches")
val fetchRhwp by tasks.registering(Exec::class) {
    group = "rust"
    description = "rhwp v0.8.7 sparse checkout + ASH 패치"
    inputs.dir(rhwpPatches)
    outputs.upToDateWhen { false }
    commandLine("bash", file("scripts/fetch-rhwp.sh").absolutePath, file("build/rhwp").absolutePath, rhwpPatches.absolutePath)
}

// cargo-ndk: -P 26(minSdk), NDK r28 = 16KB 페이지 정렬 기본
val cargoNdk by tasks.registering(Exec::class) {
    group = "rust"
    description = "cargo ndk 로 libash_hwp.so 빌드"
    dependsOn(fetchRhwp)
    inputs.dir(file("rust/src")); inputs.file(file("rust/Cargo.toml")); inputs.dir(rhwpPatches)
    outputs.dir(jniOut)
    workingDir = rustDir
    val args = mutableListOf("cargo", "ndk", "-P", "26", "-o", jniOut.absolutePath)
    abis.forEach { args += listOf("-t", it) }
    args += listOf("build", "--release", "--lib")
    commandLine(args)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(cargoNdk) }

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
