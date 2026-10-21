#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# N-05 ①(최종 산출물 검사): aapt2 dump permissions 로 APK에 금지 권한이 있으면 실패.
# 사용법: check-apk-permissions.sh <apk>...
set -euo pipefail
FORBIDDEN=("android.permission.INTERNET")
AAPT2="$(ls -1d "${ANDROID_HOME:?ANDROID_HOME 없음}"/build-tools/*/aapt2 | sort -V | tail -1)"
[ -x "$AAPT2" ] || { echo "::error::aapt2를 찾지 못했습니다"; exit 2; }
fail=0
for apk in "$@"; do
  perms="$("$AAPT2" dump permissions "$apk")"
  echo "== $(basename "$apk")"
  echo "$perms"
  for p in "${FORBIDDEN[@]}"; do
    if grep -qE "uses-permission(-sdk-23)?: name='${p}'" <<<"$perms"; then
      echo "::error title=N-05 위반 (최종 APK)::$(basename "$apk") 에 ${p} 권한이 있습니다"
      fail=1
    fi
  done
done
[ "$fail" -eq 0 ] && echo "N-05 최종 APK 검사 통과: 금지 권한 없음"
exit "$fail"
