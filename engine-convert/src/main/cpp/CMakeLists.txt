# SPDX-License-Identifier: AGPL-3.0-or-later
# libash_convert.so = JNI + libmobi 0.12(LGPL-3.0-or-later, 정적) + libmspack chmd(LGPL-2.1, 정적)
# 두 라이브러리 모두 자체 빌드 스크립트 대신 필요한 소스만 직접 나열한다(외부 의존 없음: libxml2·zlib 대신 내장 xmlwriter·miniz).
cmake_minimum_required(VERSION 3.22.1)
project(ash_convert C)

set(CMAKE_C_STANDARD 99)
set(TP ${ASH_THIRD_PARTY})

# --- libmobi: 암호 해제(USE_ENCRYPTION) 끔 — DRM 책은 안내만(D-002) ---
set(MOBI ${TP}/libmobi/src)
add_library(mobi_static STATIC
  ${MOBI}/buffer.c ${MOBI}/compression.c ${MOBI}/debug.c ${MOBI}/index.c ${MOBI}/memory.c
  ${MOBI}/meta.c ${MOBI}/parse_rawml.c ${MOBI}/read.c ${MOBI}/structure.c ${MOBI}/util.c
  ${MOBI}/write.c ${MOBI}/xmlwriter.c ${MOBI}/opf.c ${MOBI}/miniz.c)
target_compile_definitions(mobi_static PRIVATE
  USE_XMLWRITER USE_MINIZ HAVE_UNISTD_H HAVE_STRDUP MOBI_INLINE=inline HAVE_ATTRIBUTE_NORETURN
  PACKAGE_VERSION="0.12"
  MINIZ_NO_STDIO MINIZ_NO_ZLIB_COMPATIBLE_NAMES MINIZ_NO_TIME MINIZ_NO_ARCHIVE_APIS MINIZ_NO_ARCHIVE_WRITING_APIS
  _POSIX_C_SOURCE=200112L)
target_compile_options(mobi_static PRIVATE -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -w)
target_include_directories(mobi_static PUBLIC ${MOBI})

# --- libmspack: CHM 읽기(chmd)에 필요한 것만 ---
set(MSPACK ${TP}/libmspack/libmspack/mspack)
add_library(mspack_static STATIC ${MSPACK}/system.c ${MSPACK}/chmd.c ${MSPACK}/lzxd.c)
target_compile_definitions(mspack_static PRIVATE HAVE_FSEEKO=1 HAVE_TOWLOWER=1 _FILE_OFFSET_BITS=64)
target_compile_options(mspack_static PRIVATE -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -w)
target_include_directories(mspack_static PUBLIC ${MSPACK})

add_library(ash_convert SHARED ash_convert_jni.c)
target_link_libraries(ash_convert mobi_static mspack_static)
target_compile_options(ash_convert PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
# 16KB 페이지 정렬(NDK r28 기본값이지만 명시) + 사용하지 않는 코드 제거
target_link_options(ash_convert PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
