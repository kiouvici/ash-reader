#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# MuPDF 소스(태그, 필요한 서브모듈만)를 받아 ASH 패치를 적용한다.
# 사용법: fetch-mupdf.sh <버전 태그> <대상 폴더> <패치 폴더>
set -euo pipefail
VER="$1"; DEST="$2"; PATCHES="$3"
MARKER="$DEST/.ash-patched-$VER"
[ -f "$MARKER" ] && exit 0
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
git clone -q --depth 1 --branch "$VER" https://github.com/ArtifexSoftware/mupdf.git "$DEST"
cd "$DEST"
# 안드로이드 빌드(platform/java/Android.mk)가 쓰는 서브모듈만 받는다(OCR·바코드·curl·glut 제외)
git submodule update --init --depth 1 \
  thirdparty/freetype thirdparty/harfbuzz thirdparty/gumbo-parser thirdparty/jbig2dec \
  thirdparty/lcms2 thirdparty/libjpeg thirdparty/openjpeg thirdparty/zlib thirdparty/mujs \
  thirdparty/extract thirdparty/brotli thirdparty/cmark-gfm
for p in "$PATCHES"/*.patch; do
  echo "패치 적용: $(basename "$p")"
  git apply --whitespace=nowarn "$p"
done
# 패치가 css-properties.h(gperf 산출물)를 함께 바꾸므로 gperf가 다시 돌지 않게 시각을 맞춘다
touch source/html/css-properties.h
# platform/java 의 Java 바인딩 일부(생성 소스)가 필요하면 make generate 로 만든다(폰트·하이픈 리소스)
make -s generate >/dev/null
echo "$VER $(git rev-parse HEAD)" > "$MARKER"
