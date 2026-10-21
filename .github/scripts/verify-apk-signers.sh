#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# P4: APK 서명 인증서 확인 (apksigner verify --print-certs).
# 사용법: verify-apk-signers.sh <release.apk> <debug.apk>
# 환경변수: EXPECTED_RELEASE_SHA256 (콜론 구분 가능), RELEASE_MODE(release|fallback), DEBUG_MODE(fixed|fallback)
set -euo pipefail
RELEASE_APK="$1"; DEBUG_APK="$2"
APKSIGNER="$(ls -1d "${ANDROID_HOME:?ANDROID_HOME 없음}"/build-tools/*/apksigner | sort -V | tail -1)"
norm() { tr -d ':[:space:]' | tr 'A-F' 'a-f'; }
colon() { sed -E 's/(..)/\1:/g; s/:$//' | tr 'a-f' 'A-F'; }

cert_info() { # $1 apk -> "sha256|DN"
  local out sha dn
  out="$("$APKSIGNER" verify --print-certs "$1")"
  sha="$(grep -m1 'certificate SHA-256 digest:' <<<"$out" | sed 's/.*digest: //' | norm)"
  dn="$(grep -m1 'certificate DN:' <<<"$out" | sed 's/.*DN: //')"
  echo "${sha}|${dn}"
}

fail=0
IFS='|' read -r R_SHA R_DN <<<"$(cert_info "$RELEASE_APK")"
IFS='|' read -r D_SHA D_DN <<<"$(cert_info "$DEBUG_APK")"
R_FP="$(colon <<<"$R_SHA")"; D_FP="$(colon <<<"$D_SHA")"
EXP="$(norm <<<"${EXPECTED_RELEASE_SHA256:?}")"

{
  echo ""
  echo "### 서명 인증서"
  echo "| APK | SHA-256 | DN |"
  echo "|---|---|---|"
  echo "| release | \`${R_FP}\` | ${R_DN} |"
  echo "| debug | \`${D_FP}\` | ${D_DN} |"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "::notice title=release 서명 SHA-256::${R_FP} (${R_DN})"
echo "::notice title=debug 서명 SHA-256::${D_FP} (${D_DN})"

if [ "${RELEASE_MODE:-}" = "release" ]; then
  if [ "$R_SHA" = "$EXP" ]; then
    echo "release 서명 지문 일치" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
  else
    echo "::error title=release 서명 지문 불일치::기대 $(colon <<<"$EXP"), 실제 ${R_FP}"
    fail=1
  fi
else
  echo "::warning title=release 지문 검사 생략::릴리스 키 시크릿이 없어 대체 서명된 빌드입니다"
fi

if [ "${DEBUG_MODE:-}" = "fixed" ]; then
  if grep -q "CN=Android Debug" <<<"$D_DN"; then
    echo "::error title=debug 서명 오류::고정 디버그 키(ashdebug)가 아니라 AGP 기본 키로 서명됐습니다"
    fail=1
  fi
  if [ "$D_SHA" = "$R_SHA" ]; then
    echo "::error title=debug 서명 오류::debug APK가 release 키로 서명됐습니다"
    fail=1
  fi
fi
exit "$fail"
