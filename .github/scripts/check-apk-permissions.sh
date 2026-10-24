#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# N-05 ①(최종 산출물 검사): aapt2 dump permissions 로 APK에 금지 권한이 있으면 실패.
# 작업 06(D-024): 본 앱은 책 정보 찾기를 위해 INTERNET 만 허용, 측정 앱(bench*)은 INTERNET 도 금지.
# 위험 권한 목록은 buildSrc/AshBuild.kt 의 ASH_FORBIDDEN_PERMISSIONS 와 같게 유지한다.
# 사용법: check-apk-permissions.sh <apk>...
set -euo pipefail
FORBIDDEN=(ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION ACCESS_MEDIA_LOCATION
  CAMERA RECORD_AUDIO READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS
  READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG PROCESS_OUTGOING_CALLS ANSWER_PHONE_CALLS
  SEND_SMS RECEIVE_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH
  READ_CALENDAR WRITE_CALENDAR BODY_SENSORS BODY_SENSORS_BACKGROUND ACTIVITY_RECOGNITION
  NEARBY_WIFI_DEVICES BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE UWB_RANGING
  READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO READ_MEDIA_VISUAL_USER_SELECTED)
AAPT2="$(ls -1d "${ANDROID_HOME:?ANDROID_HOME 없음}"/build-tools/*/aapt2 | sort -V | tail -1)"
[ -x "$AAPT2" ] || { echo "::error::aapt2를 찾지 못했습니다"; exit 2; }
fail=0
for apk in "$@"; do
  perms="$("$AAPT2" dump permissions "$apk")"
  name="$(basename "$apk")"
  echo "== $name"
  echo "$perms"
  list=("${FORBIDDEN[@]}")
  case "$name" in bench*) list+=(INTERNET) ;; esac
  for p in "${list[@]}"; do
    if grep -qE "uses-permission(-sdk-23)?: name='android.permission.${p}'" <<<"$perms"; then
      echo "::error title=N-05 위반 (최종 APK)::$name 에 android.permission.${p} 권한이 있습니다"
      fail=1
    fi
  done
done
[ "$fail" -eq 0 ] && echo "N-05 최종 APK 검사 통과: 금지 권한 없음"
exit "$fail"