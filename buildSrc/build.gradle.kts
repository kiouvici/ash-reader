// SPDX-License-Identifier: AGPL-3.0-or-later
// 모듈 공통 빌드 로직(N-05 권한 검사, 서명 키 환경변수, 시험 파일 묶기). AGP에 의존하지 않는다.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}
