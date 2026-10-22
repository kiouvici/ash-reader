# SPDX-License-Identifier: AGPL-3.0-or-later
# libash_comic.so = JNI(만화 압축 읽기 + Real-CUGAN 화질 향상) + libarchive(정적) + ncnn(정적, Vulkan)
cmake_minimum_required(VERSION 3.22.1)
project(ash_comic C CXX)

set(TP ${ASH_THIRD_PARTY})

# --- libarchive 3.8.10: RAR/RAR5/ZIP 읽기만, 외부 압축 라이브러리·도구·시험 모두 끔 ---
foreach(opt ENABLE_TEST ENABLE_TAR ENABLE_CPIO ENABLE_CAT ENABLE_UNZIP ENABLE_INSTALL ENABLE_ACL ENABLE_XATTR
            ENABLE_ICONV ENABLE_OPENSSL ENABLE_MBEDTLS ENABLE_NETTLE ENABLE_MD ENABLE_LIBB2 ENABLE_LZ4 ENABLE_LZO
            ENABLE_LZMA ENABLE_ZSTD ENABLE_ZLIB ENABLE_BZip2 ENABLE_LIBXML2 ENABLE_EXPAT ENABLE_PCREPOSIX
            ENABLE_PCRE2POSIX ENABLE_LIBGCC ENABLE_CNG ENABLE_WIN32_XMLLITE ENABLE_BSDXML ENABLE_WERROR)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/libarchive ${CMAKE_BINARY_DIR}/libarchive EXCLUDE_FROM_ALL)

# --- ncnn 20261020 프리빌드 (ABI별) ---
set(ncnn_DIR ${TP}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(ash_comic SHARED ash_archive_jni.cpp ash_upscale_jni.cpp)
target_include_directories(ash_comic PRIVATE ${TP}/libarchive/libarchive)
target_link_libraries(ash_comic archive_static ncnn jnigraphics android log)
target_compile_options(ash_comic PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
# 16KB 페이지 정렬(NDK r28 기본값이지만 명시) + 사용하지 않는 코드 제거
target_link_options(ash_comic PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
