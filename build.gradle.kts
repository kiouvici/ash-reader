// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 클래스패스의 Kotlin Gradle Plugin 버전을 version catalog 값으로 맞추기 위한 선언 (적용하지 않음)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
