// SPDX-License-Identifier: AGPL-3.0-or-later
// 1단계 기술 검증 측정 앱 "ASH 측정" (기술 설계서 9장). 대표님 기기에 설치해 실험 버튼으로 측정·CSV 공유.
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 측정 앱은 release·debug 모두 고정 디버그 키(ASH_DEBUG_*)로 서명한다. 없으면 AGP 기본 debug 키.
val debugKey: AshKey? = AshSigning.debug(project)

android {
    namespace = "kr.vici.ashreader.bench"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.vici.ashreader.bench"
        minSdk = 26
        targetSdk = 36
        versionCode = ashVersionCode()
        versionName = "0.1.0-bench"
        buildConfigField("String", "GIT_SHA", "\"${ashGitSha()}\"")
    }

    signingConfigs {
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
        // 측정 규칙 9.0: 판정은 release(R8 켬) 빌드로 한다.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (debugKey != null) "ashDebug" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// test-files/public → assets/testfiles/ (공개 시험 파일만. private 은 커밋하지 않으므로 SAF 폴더 선택으로 읽는다)
val bundleTestFiles = tasks.register<BundleTestFilesTask>("bundleTestFiles") {
    sourceDir.set(rootProject.layout.projectDirectory.dir("test-files/public"))
    outputDir.set(layout.buildDirectory.dir("generated/testfiles"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleTestFiles, BundleTestFilesTask::outputDir)
        // N-05 ①: 측정 앱에도 같은 INTERNET 권한 검사 적용
        registerNoInternetCheck(variant.name, variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}
wireNoInternetChecks()
