// SPDX-License-Identifier: AGPL-3.0-or-later
// 책 넘김 효과 (기술 설계서 4장, F-15~18·F-48). OpenGL ES 2.0 + TextureView, 입력은 쪽 비트맵.
// 알고리즘은 harism/android-pagecurl·eschao/android-PageFlip(Apache-2.0)의 원통 말림 방식을 참고해 새로 작성했다(코드 복사 없음).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "kr.vici.ashreader.pagecurl"
    compileSdk = 37
    // 필수 1: minSdk(26)보다 새 API 호출은 오류로 본다. CI compile job 의 lintDebug 가 검사한다.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        abortOnError = true
        checkDependencies = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
