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
