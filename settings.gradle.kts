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
include(":engine-mupdf") // MuPDF + 한국어 줄바꿈 패치 (실험 ④)
include(":engine-hwp") // rhwp + resvg (실험 ①)
