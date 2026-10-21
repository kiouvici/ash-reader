#!/usr/bin/env bash
# Gradle을 실행하고, 실패하면 핵심 오류 줄을 GitHub 주석(annotation)과 실행 요약에 남긴다.
# (로그 파일을 받을 수 없는 환경에서도 API로 실패 원인을 확인하기 위함)
set -uo pipefail
LOG="$(mktemp)"
./gradlew --stacktrace --console=plain "$@" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
if [ "$STATUS" -ne 0 ]; then
  EXCERPT="$(grep -E '^e: |error:|ERROR|FAILURE|What went wrong|> |Caused by|Exception|N-05' "$LOG" | grep -v '^\s*at ' | head -60)"
  {
    echo "### Gradle 실패: $*"
    echo '```'
    echo "$EXCERPT"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  ENC="$(printf '%s' "$EXCERPT" | head -c 3500 | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | sed ':a;N;$!ba;s/\n/%0A/g')"
  echo "::error title=Gradle 실패 ($*)::${ENC}"
fi
exit "$STATUS"
