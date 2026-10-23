// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

object AppInfo {
    const val NAME = "ASH Reader"
    /** CI 가 정한 판 이름(1.0.0-rc.<실행 번호>), 로컬 빌드는 1.0.0-dev. */
    const val VERSION_LABEL = BuildConfig.VERSION_NAME
    /** AGPL-3.0 §13 소스 코드 안내(라이선스 화면). 앱은 인터넷을 쓰지 않으므로 주소만 보여 준다. */
    const val SOURCE_URL = "https://github.com/kiouvici/ash-reader"
}
