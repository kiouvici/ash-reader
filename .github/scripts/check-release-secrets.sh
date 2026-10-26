#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# 작업 09 정식 서명 잠금(대표님 결정 2026-10-26): 정식 키 시크릿(ASH_KEYSTORE_*·ASH_KEY_*)은
# build.yml 의 'release' 작업(v* 태그 + Environment release 승인)에서만 쓴다.
# 다른 작업·다른 워크플로·워크플로 전체 env 에 정식 키 시크릿 이름이 나오면 실패한다.
# 'release' 작업에는 environment: release 와 v* 태그 조건이 있어야 한다.
# 사용법: check-release-secrets.sh [.github/workflows]
set -euo pipefail
DIR="${1:-.github/workflows}"
PAT='secrets[.]ASH_(KEYSTORE|KEY)_'
fail=0
for f in "$DIR"/*.yml "$DIR"/*.yaml; do
  [ -f "$f" ] || continue
  # 줄마다 "작업 이름<TAB>줄" — jobs: 아래 두 칸 들여쓴 키가 작업 이름, jobs: 앞은 '(전체)'
  bad="$(awk -v pat="$PAT" '
    /^jobs:[[:space:]]*$/ { injobs = 1; job = ""; next }
    injobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { job = $1; sub(":", "", job); next }
    /^[^[:space:]#]/ && !/^jobs:/ { injobs = 0; job = "" }
    $0 ~ pat && $0 !~ /^[[:space:]]*#/ {
      j = (job == "" ? "(전체)" : job)
      if (!(FILENAME ~ /build\.ya?ml$/ && j == "release")) print FILENAME ":" FNR ": [" j "] " $0
    }' "$f")"
  if [ -n "$bad" ]; then
    echo "$bad"
    echo "::error title=정식 키 시크릿 사용 금지::$(basename "$f") 의 release 작업 밖에서 정식 키 시크릿을 씁니다(위 줄)"
    fail=1
  fi
done

B="$DIR/build.yml"
rel="$(awk '/^  release:[[:space:]]*$/ { on = 1; next } on && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { on = 0 } on' "$B")"
if [ -n "$rel" ]; then
  grep -qE '^    environment:[[:space:]]*release[[:space:]]*$' <<<"$rel" \
    || { echo "::error title=release 작업::environment: release 가 없습니다(대표님 승인 버튼)"; fail=1; }
  grep -qE "^    if:.*startsWith\(github\.ref, 'refs/tags/v'\)" <<<"$rel" \
    || { echo "::error title=release 작업::v* 태그 조건(if: startsWith(github.ref, 'refs/tags/v'))이 없습니다"; fail=1; }
fi
[ "$fail" = 0 ] && echo "정식 키 시크릿: release 작업에서만 씀 — 통과"
exit "$fail"
