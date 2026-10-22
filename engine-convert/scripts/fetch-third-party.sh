#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# :engine-convert 서드파티 받기. 사용: fetch-third-party.sh <출력 폴더>
# 태그를 얕게 받은 뒤 커밋 SHA 를 확인한다(태그가 옮겨지면 실패).
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
get() { # 저장소 태그(또는 브랜치) 커밋 폴더
  local url="$1" ref="$2" sha="$3" dir="$OUT/$4"
  if [ -f "$dir/.ash-sha" ] && [ "$(cat "$dir/.ash-sha")" = "$sha" ]; then return 0; fi
  rm -rf "$dir"
  if [ "$ref" = "-" ]; then
    git init -q "$dir"; git -C "$dir" fetch -q --depth 1 "$url" "$sha"; git -C "$dir" checkout -q FETCH_HEAD
  else
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$ref" "$url" "$dir"
  fi
  local got; got="$(git -C "$dir" rev-parse HEAD)"
  [ "$got" = "$sha" ] || { echo "커밋 불일치: $4 ($got != $sha)" >&2; exit 1; }
  rm -rf "$dir/.git"; echo "$sha" > "$dir/.ash-sha"
}
# libmobi 0.12 (LGPL-3.0-or-later) — MOBI·AZW3(KF8) → EPUB 재구성
get https://github.com/bfabiszewski/libmobi.git v0.12 85dcfe803fc2a21020ddcf15c3eb66b93d388add libmobi
# libmspack (LGPL-2.1) — CHM(chmd) 풀기. 태그가 오래돼(0.11alpha, 2023) CVE 수정이 든 master 커밋을 고정
get https://github.com/kyz/libmspack.git - 55d501976171397ccd5d5a7a1ca7da065b1d9a06 libmspack
touch "$OUT/.ash-ok-v1"
