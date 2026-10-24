#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# ncnn 20261020 을 공식 full-source(zip, glslang 포함)에서 우리 NDK 로 정적 빌드 (Vulkan 켬).
# 사용: build-ncnn.sh <출력 폴더> <NDK 경로> <ABI...>
# 결과: <출력>/<ABI>/lib/cmake/ncnn (find_package 용), 이미 있으면 건너뜀(CI 캐시).
set -euo pipefail
OUT="$1"; NDK="$2"; shift 2
TAG=20261020
SHA=699d9acc67489eee0993a551ca1ebc247bbf0f4e33babe1dec810e6a9a52b988
mkdir -p "$OUT"
SRC="$OUT/src-$TAG"
if [ ! -f "$SRC/CMakeLists.txt" ]; then
  Z="$OUT/ncnn-$TAG-full-source.zip"
  [ -f "$Z" ] || curl -fsSL --retry 3 -o "$Z" "https://github.com/Tencent/ncnn/releases/download/$TAG/ncnn-$TAG-full-source.zip"
  echo "$SHA  $Z" | sha256sum -c --status || { echo "ncnn 소스 SHA-256 불일치" >&2; exit 1; }
  rm -rf "$SRC" && mkdir -p "$SRC" && unzip -q "$Z" -d "$SRC" && rm -f "$Z"
fi
# OpenMP 는 ncnn 자체 simpleomp 를 쓴다. NDK libomp 는 첫 병렬 구간에서 CPU 친화도를 초기화하다가, 그 사이 기기가
# 스레드 CPU 묶음을 바꾸면(MTK 태블릿 TB375FC) 단정 실패로 앱을 abort 시켰다(작업 04, __kmp_create_affinity_none_places).
# simpleomp 는 친화도 초기화가 없다. 표식(.ash-simpleomp)이 없는 옛 결과는 지우고 다시 빌드한다.
for ABI in "$@"; do
  if [ -f "$OUT/$ABI/.ash-simpleomp" ]; then echo "ncnn $ABI: 캐시 사용"; continue; fi
  rm -rf "$OUT/$ABI"
  B="$OUT/build-$ABI"; rm -rf "$B"
  cmake -S "$SRC" -B "$B" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$OUT/$ABI" \
    -DNCNN_VULKAN=ON -DNCNN_SIMPLEVK=ON -DNCNN_SHARED_LIB=OFF -DNCNN_OPENMP=ON -DNCNN_SIMPLEOMP=ON \
    -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_BUILD_TESTS=OFF \
    -DNCNN_PYTHON=OFF -DNCNN_DISABLE_RTTI=ON -DNCNN_DISABLE_EXCEPTION=ON > "$B.cfg.log"
  cmake --build "$B" -j"$(nproc)" > "$B.build.log" || { tail -40 "$B.build.log"; exit 1; }
  cmake --install "$B" > /dev/null
  rm -rf "$B"
  touch "$OUT/$ABI/.ash-simpleomp"
  echo "ncnn $ABI: 빌드 완료"
done
