#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# :engine-comic 서드파티 받기. 사용: fetch-third-party.sh <출력 폴더>
set -euo pipefail
OUT="$1"; mkdir -p "$OUT/dl" "$OUT/assets/realcugan"
get() { # url file sha256
  local f="$OUT/dl/$2"
  if [ ! -f "$f" ] || ! echo "$3  $f" | sha256sum -c --status; then
    curl -fsSL --retry 3 -o "$f.part" "$1"; mv "$f.part" "$f"
  fi
  echo "$3  $f" | sha256sum -c --status || { echo "SHA-256 불일치: $2" >&2; exit 1; }
}
# libarchive 3.8.10 (BSD-2)
get https://github.com/libarchive/libarchive/releases/download/v3.8.10/libarchive-3.8.10.tar.xz libarchive-3.8.10.tar.xz 56cad765459d5ee25a3b761f90c47d99379d28297fbfb72d908880d6b875fb96
rm -rf "$OUT/libarchive" && mkdir -p "$OUT/libarchive" && tar xf "$OUT/dl/libarchive-3.8.10.tar.xz" -C "$OUT/libarchive" --strip-components=1
# ncnn 은 scripts/build-ncnn.sh 가 소스에서 우리 NDK 로 빌드한다(프리빌드는 clang 21 libc++ 라 NDK r28 과 링크 불가)
# Real-CUGAN 2x 모델 (nihui/realcugan-ncnn-vulkan 395302c, MIT; 가중치 bilibili/ailab Real-CUGAN, MIT)
RC=https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee
get $RC/models/models-se/up2x-conservative.param se-up2x-conservative.param 4b3b5c5710ad1d00503c8243417aa75f13fe497723e8b4952e8b1abfc08f9b84
get $RC/models/models-se/up2x-conservative.bin se-up2x-conservative.bin 91c72c136e7ff8556323d4449c5ceeff22d9829bf8463a01137cadb8d59b84a0
get $RC/models/models-nose/up2x-no-denoise.param nose-up2x-no-denoise.param c370b70321d45301b9c682121130ad167b3dd1bc8e122c1d77c7ac44f1943708
get $RC/models/models-nose/up2x-no-denoise.bin nose-up2x-no-denoise.bin 2b6f4db8fdc04336ac68ba954b4cd3b280beb5e7b6b0bcd97f769accc512cf4a
curl -fsSL --retry 3 -o "$OUT/dl/realcugan-ncnn-vulkan-LICENSE.txt" $RC/LICENSE
for f in se-up2x-conservative.param se-up2x-conservative.bin nose-up2x-no-denoise.param nose-up2x-no-denoise.bin realcugan-ncnn-vulkan-LICENSE.txt; do cp "$OUT/dl/$f" "$OUT/assets/realcugan/$f"; done
touch "$OUT/.ash-ok-v2"
