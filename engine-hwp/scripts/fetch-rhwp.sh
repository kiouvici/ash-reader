#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# rhwp v0.8.7(1a76570) 을 라이브러리 빌드에 필요한 경로만 sparse checkout 하고 ASH 패치(engine-hwp/patches)를 적용한다
# (전체 저장소 8.7GB). 사용법: fetch-rhwp.sh <대상 폴더> [패치 폴더]
set -euo pipefail
DEST="$1"; PATCHES="${2:-$(cd "$(dirname "$0")/../patches" && pwd)}"
TAG=v0.8.7; COMMIT=1a76570e833917d15817415a53c09ad61ab3203f
# 표지 파일 이름에 패치 내용 해시를 넣어, 패치가 바뀌면 다시 받아 적용한다
PH=$(cat "$PATCHES"/*.patch 2>/dev/null | sha256sum | cut -c1-12)
MARKER=".ash-$COMMIT-$PH"
[ -f "$DEST/$MARKER" ] && exit 0
rm -rf "$DEST"
git clone -q --filter=blob:none --no-checkout --depth 1 --branch "$TAG" https://github.com/edwardkim/rhwp.git "$DEST"
cd "$DEST"
[ "$(git rev-parse HEAD)" = "$COMMIT" ] || { echo "rhwp 커밋 불일치"; exit 1; }
git sparse-checkout init --no-cone
printf '/*\n!/*/\n/src/\n/crates/\n/vendor/\n/saved/\n/assets/logo/\n/ttfs/opensource/NotoSansKR-Regular.ttf\n/mydocs/manual/\n/mydocs/tech/agent_roadmap/atlas_r1_r200.md\n/tools/rhwp-subsecond/\n/tools/batch-convert/\n/tools/llm_verifier/\n/bindings/Native/\n' > .git/info/sparse-checkout
git checkout -q "$TAG"
# rhwp 고정 툴체인(1.93.1) 파일 제거: 설치된 stable 사용(1.97에서 빌드 확인)
rm -f rust-toolchain.toml
for p in "$PATCHES"/*.patch; do
  [ -e "$p" ] || continue
  echo "패치 적용: $(basename "$p")"
  git apply --whitespace=nowarn "$p"
done
touch "$MARKER"
