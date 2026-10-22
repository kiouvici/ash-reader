# SPDX-License-Identifier: AGPL-3.0-or-later
# 실험 ② APK 크기 측정 전용(-Pash.withOffice=true). 앱이 실제로 부를 진입점만 유지한다.
-keep class kr.vici.ashreader.office.DocExtractor { public *; }
-keep class kr.vici.ashreader.office.DocxConverter { public *; }
-keep class kr.vici.ashreader.office.AgileDecryptor { public *; }
-keep class kr.vici.ashreader.office.FormatSniffer { public *; }
