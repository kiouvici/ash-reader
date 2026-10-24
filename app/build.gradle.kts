// SPDX-License-Identifier: AGPL-3.0-or-later
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 내장 Kotlin 사용: org.jetbrains.kotlin.android 는 적용하지 않는다.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// N-05 음성 시험 전용: -Pash.negativeTest=true 일 때만 금지 권한(위치)을 넣은 빌드 유형(negativeProbe)을 만든다.
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
    // 필수 1: minSdk(26)보다 새 API 호출은 오류로 본다. CI compile job 의 lintDebug 가 검사한다.
    lint {
        checkOnly += "NewApi"
        error += "NewApi"
        abortOnError = true
        checkDependencies = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "kr.vici.ashreader"
        minSdk = 26
        targetSdk = 36
        versionCode = ashVersionCode()
        versionName = ashVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 64비트 전용(기술 설계서 K-14, D-020): 만화·DjVu·MOBI 엔진은 64비트만 빌드하므로, 32비트 .so 가 일부만 들어가
        // 32비트 기기에서 설치는 되나 열 때 멈추는 일을 막는다(32비트 전용 기기는 설치 단계에서 거부 — 알려진 제약).
        ndk { abiFilters += (providers.gradleProperty("ash.abis").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")) }
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
            // src/negativeProbe/AndroidManifest.xml 이 금지 권한(위치)을 추가한다. 배포 금지.
            create("negativeProbe") {
                initWith(getByName("debug"))
                signingConfig = signingConfigs.getByName("debug")
                applicationIdSuffix = ".negativeprobe"
                // 엔진 라이브러리 모듈에는 이 빌드 유형이 없으므로 debug 로 맞춘다
                matchingFallbacks += listOf("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AppInfo.VERSION_LABEL = BuildConfig.VERSION_NAME (CI 판 이름)
        buildConfig = true
    }

    // 작업 06: 책 정보 찾기 Open API 키(국립중앙도서관 인증키·카카오 REST API 키). 저장소에 넣지 않는다 — 노트북은 local.properties
    // (kakao.restKey / nlk.apiKey, 커밋 금지), CI 는 시크릿 → 환경변수(ASH_KAKAO_KEY / ASH_NL_KEY). 없으면 빈 값 = 구글 북스만.
    defaultConfig {
        buildConfigField("String", "KAKAO_KEY", "\"${ashSecret("kakao.restKey", "ASH_KAKAO_KEY")}\"")
        buildConfigField("String", "NL_KEY", "\"${ashSecret("nlk.apiKey", "ASH_NL_KEY")}\"")
    }
}

// Room 스키마 내보내기(exportSchema = true): app/schemas/<DB 클래스>/<판>.json — 판을 올릴 때 함께 커밋한다
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(project(":engine-convert"))
    implementation(project(":engine-djvu"))

    testImplementation(libs.junit)
    // DB 이전 시험(AshDbMigrationTest, CI 에뮬레이터 job)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    // 7D 종단(E2E) UI 시험(e2e 패키지): Compose 시험 규칙 + UiAutomator(시스템 화면·실제 터치)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// N-05 ①: 병합된 매니페스트 금지 권한 검사(본 앱은 책 정보 찾기용 INTERNET 만 허용) ⑤: 네트워크는 net/ 에서만 (구현은 buildSrc/AshBuild.kt)
androidComponents {
    onVariants { variant ->
        registerPermissionCheck(variant.name, variant.artifacts.get(SingleArtifact.MERGED_MANIFEST), allowInternet = true)
    }
}
wirePermissionChecks()
registerNetworkConfinedCheck(file("src/main/java/kr/vici/ashreader/net"))

// C1: 오픈소스 고지 목록 갱신용(손으로 돌림). ./gradlew --no-configuration-cache :app:ashThirdPartyDeps
registerThirdPartyDepsTask()
