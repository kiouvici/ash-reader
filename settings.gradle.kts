// SPDX-License-Identifier: AGPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ash-reader"
include(":app")
include(":bench") // 1단계 기술 검증 측정 앱 (ASH 측정)
include(":pagecurl") // 책 넘김 효과 (실험 ③)
include(":engine-mupdf") // MuPDF + 한국어 줄바꿈 패치 (실험 ④)
include(":engine-hwp") // rhwp + resvg (실험 ①)
include(":engine-office") // DOCX·DOC·암호 DOCX (실험 ②)
