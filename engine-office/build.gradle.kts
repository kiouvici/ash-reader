// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    // AGP 9 내장 Kotlin 사용
}

android {
    namespace = "kr.vici.ashreader.office"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // 시험 파일 위치. private 은 저장소에 없으므로 있을 때만 쓴다(D-006).
            it.systemProperty("ash.testFiles", rootProject.file("test-files").absolutePath)
            providers.environmentVariable("ASH_PRIVATE_TEST_FILES").orNull?.let { p -> it.systemProperty("ash.privateFiles", p) }
            it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
        }
    }
}

dependencies {
    // DOC(Word 97-2003) 텍스트·표: POI HWPF. poi-ooxml(xmlbeans)은 넣지 않는다.
    implementation(libs.poi)
    implementation(libs.poi.scratchpad)
    testImplementation(libs.junit)
}
