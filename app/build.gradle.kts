// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 내장 Kotlin 사용: org.jetbrains.kotlin.android 는 적용하지 않는다.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// N-05 음성 시험 전용: -Pash.negativeTest=true 일 때만 INTERNET 권한을 넣은 빌드 유형(negativeProbe)을 만든다.
val negativeTest: Boolean = providers.gradleProperty("ash.negativeTest").orNull == "true"

// 실험 ② APK 크기 측정 전용: -Pash.withOffice=true 일 때만 :engine-office(POI HWPF 포함)를 넣고
// 진입점(office-probe.pro)을 유지시켜 R8 후 증가량을 잰다. 기본 빌드에는 영향 없음.
val withOffice: Boolean = providers.gradleProperty("ash.withOffice").orNull == "true"

// 서명 키·versionCode 규칙은 buildSrc/AshBuild.kt (AshSigning, ashVersionCode)
val releaseKey: AshKey? = AshSigning.release(project)
val debugKey: AshKey? = AshSigning.debug(project)

android {
    namespace = "kr.vici.ashreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.vici.ashreader"
        minSdk = 26
        targetSdk = 36
        versionCode = ashVersionCode()
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseKey?.let { k ->
            create("ashRelease") {
                storeFile = k.storeFile
                storePassword = k.storePassword
                keyAlias = k.alias
                keyPassword = k.keyPassword
            }
        }
        debugKey?.let { k ->
            create("ashDebug") {
                storeFile = k.storeFile
                storePassword = k.storePassword
                keyAlias = k.alias
                keyPassword = k.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (debugKey != null) signingConfig = signingConfigs.getByName("ashDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (withOffice) proguardFile("office-probe.pro")
            signingConfig = if (releaseKey != null) {
                signingConfigs.getByName("ashRelease")
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

    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // 2단계: 엔진 연결 (F-17 쪽 비트맵 공급)
    implementation(project(":pagecurl"))
    implementation(project(":engine-mupdf"))
    implementation(project(":engine-hwp"))
    implementation(project(":engine-comic"))
    implementation(project(":engine-office"))

    testImplementation(libs.junit)
}

// N-05 ①: 병합된 매니페스트 INTERNET 권한 검사 (구현은 buildSrc/AshBuild.kt)
androidComponents {
    onVariants { variant ->
        registerNoInternetCheck(variant.name, variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}
wireNoInternetChecks()
