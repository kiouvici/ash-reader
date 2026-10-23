#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Gradle을 실행하고, 실패하면 핵심 오류 줄을 GitHub 주석(annotation)과 실행 요약에 남긴다.
# (로그 파일을 받을 수 없는 환경에서도 API로 실패 원인을 확인하기 위함)
set -uo pipefail
LOG="$(mktemp)"
./gradlew --stacktrace --console=plain "$@" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
if [ "$STATUS" -ne 0 ]; then
  EXCERPT="$(grep -vE '^> Task |^\s+at |^\s*$' "$LOG" | grep -E -A3 '^e: |error:|ERROR|FAILURE|What went wrong|Caused by|Dependency |requires|N-05|issues were found' | grep -vE '^\s+at ' | head -80)"
  {
    echo "### Gradle 실패: $*"
    echo '```'
    echo "$EXCERPT"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  ENC="$(printf '%s' "$EXCERPT" | head -c 3500 | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | sed ':a;N;$!ba;s/\n/%0A/g')"
  echo "::error title=Gradle 실패 ($*)::${ENC}"
  # 컴파일 오류가 많으면 위 주석(3.5KB)에 다 안 들어간다 → 'e: ' 줄만 경로를 줄여 여러 주석으로 나눠 남긴다(최대 8개)
  ERRS="$(grep -E '^e: ' "$LOG" | sed -e 's#file://[^ ]*/ash-reader/##' | awk '!seen[$0]++' | cut -c1-300)"
  if [ -n "$ERRS" ]; then
    printf '%s\n' "$ERRS" | split -C 3400 - /tmp/ash-errs-
    n=0
    for f in /tmp/ash-errs-*; do
      n=$((n + 1)); [ "$n" -gt 8 ] && break
      E="$(sed -e 's/%/%25/g' -e 's/\r/%0D/g' "$f" | sed ':a;N;$!ba;s/\n/%0A/g')"
      echo "::error title=컴파일 오류 ${n}::${E}"
    done
    rm -f /tmp/ash-errs-*
  fi
fi
exit "$STATUS"
