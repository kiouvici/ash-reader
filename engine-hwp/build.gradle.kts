// SPDX-License-Identifier: AGPL-3.0-or-later
//
// :engine-hwp — HWP 5.0·HWPX 원본 보기. rhwp v0.8.7(1a76570) + resvg 를 cargo-ndk 로 libash_hwp.so 로 빌드한다.
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

val fetchRhwp by tasks.registering(Exec::class) {
    group = "rust"
    description = "rhwp v0.8.7 sparse checkout"
    val marker = file("build/rhwp/.ash-1a76570e833917d15817415a53c09ad61ab3203f")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    commandLine("bash", file("scripts/fetch-rhwp.sh").absolutePath, file("build/rhwp").absolutePath)
}

// cargo-ndk: -P 26(minSdk), NDK r28 = 16KB 페이지 정렬 기본
val cargoNdk by tasks.registering(Exec::class) {
    group = "rust"
    description = "cargo ndk 로 libash_hwp.so 빌드"
    dependsOn(fetchRhwp)
    inputs.dir(file("rust/src")); inputs.file(file("rust/Cargo.toml"))
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
