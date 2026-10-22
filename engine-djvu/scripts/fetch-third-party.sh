#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# :engine-djvu 서드파티 받기. 사용: fetch-third-party.sh <출력 폴더>
# DjVuLibre 3.5.30 (GPL-2.0-or-later; COPYING = GPL v2, 소스 머리말 "or (at your option) any later version")
# 원 저장소(sourceforge)의 거울 github.com/barak/djvulibre 의 release.3.5.30 태그. 커밋 SHA 를 확인한다.
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
SHA=03e6abd7295c74f795ed70a7118294b25351e0f4
DIR="$OUT/djvulibre"
if [ -f "$DIR/.ash-sha" ] && [ "$(cat "$DIR/.ash-sha")" = "$SHA" ]; then exit 0; fi
rm -rf "$DIR"
git -c advice.detachedHead=false clone -q --depth 1 --branch release.3.5.30 https://github.com/barak/djvulibre.git "$DIR"
got="$(git -C "$DIR" rev-parse HEAD)"
[ "$got" = "$SHA" ] || { echo "커밋 불일치: djvulibre ($got != $SHA)" >&2; exit 1; }
rm -rf "$DIR/.git"; echo "$SHA" > "$DIR/.ash-sha"
