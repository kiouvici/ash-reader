#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# 에뮬레이터 계측 시험 실행 + 실패 원인을 주석으로 남긴다. 사용법: run-emulator-test.sh <모듈> <로그태그> <로그파일>
set -u
M="$1"; T="$2"; L="$3"
adb logcat -c
./gradlew --console=plain ":$M:connectedDebugAndroidTest" > gradle-test.log 2>&1; rc=$?
adb logcat -d -s "$T:*" AndroidRuntime:E TestRunner:* > "$L" || true
if [ $rc -ne 0 ]; then
  grep -E "FAILED|Exception|Error|What went wrong" -A2 gradle-test.log | head -30 | while IFS= read -r x; do echo "::error title=gradle::$x"; done
  grep -h -A4 "<failure" "$M"/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null | head -20 | while IFS= read -r x; do echo "::error title=junit::$x"; done
  grep -E "FATAL|Exception|UnsatisfiedLink" "$L" | head -15 | while IFS= read -r x; do echo "::error title=logcat::$x"; done
fi
exit $rc
