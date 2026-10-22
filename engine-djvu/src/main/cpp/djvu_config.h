/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* DjVuLibre config.h 대신 쓰는 안드로이드(NDK, bionic, clang)용 설정. autotools 없이 CMake 로 빌드하려고 직접 적는다.
 * JPEG(BGjp/FGjp 조각)·TIFF·iconv 는 끈다(드문 형식, 외부 라이브러리 없이). */
#ifndef ASH_DJVU_CONFIG_H
#define ASH_DJVU_CONFIG_H
#define AUTOCONF 1
#define DJVULIBRE_VERSION "3.5.30"
#define PACKAGE_VERSION "3.5.30"
#define HAVE_STDINCLUDES 1
#define HAVE_NAMESPACES 1
#define HAVE_TYPENAME 1
#define HAVE_BOOL 1
#define HAVE_EXCEPTIONS 1
#define HAVE_MEMBER_TEMPLATES 1
#define HAVE_PTHREAD 1
#define HAVE_INTEL_ATOMIC_BUILTINS 1
#define HAVE_WCHAR_H 1
#define HAVE_WCTYPE_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_NEW_H 1
#define HAVE_SCHED_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_DIRENT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDDEF_H 1
#define HAVE_UNISTD_H 1
#define HAVE_MBSTATE_T 1
#define HAVE_WCRTOMB 1
#define HAVE_ISWSPACE 1
#define HAVE_SETENV 1
#define HAVE_STRERROR 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_STRFTIME 1
#define HAVE_MKSTEMP 1
#define HAVE_SCHED_YIELD 1
#define HAVE_SIGACTION 1
#define HAVE_LONG_LONG_INT 1
#define UNIX 1
#define HAS_WCHAR 1
#define HAS_WCTYPE 1
#define HAS_MBSTATE 1
#endif
