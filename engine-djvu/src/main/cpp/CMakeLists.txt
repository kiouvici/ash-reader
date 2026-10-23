# SPDX-License-Identifier: AGPL-3.0-or-later
# libash_djvu.so = JNI + DjVuLibre 3.5.30 libdjvu(GPL-2.0-or-later, 정적). D-001 A안: AGPL-3.0-or-later 앱과 함께 배포('or later').
# DjVuLibre 는 autotools 전용이라 Makefile.am 의 소스 목록을 그대로 옮기고 config.h 대신 djvu_config.h 를 쓴다(기술 설계서 R-16).
cmake_minimum_required(VERSION 3.22.1)
project(ash_djvu CXX)

set(CMAKE_CXX_STANDARD 11)
set(DJ ${ASH_THIRD_PARTY}/djvulibre/libdjvu)
set(DJVU_SOURCES
  Arrays.cpp BSByteStream.cpp BSEncodeByteStream.cpp ByteStream.cpp DataPool.cpp DjVmDir.cpp
  DjVmDir0.cpp DjVmDoc.cpp DjVmNav.cpp DjVuAnno.cpp DjVuDocEditor.cpp
  DjVuDocument.cpp DjVuDumpHelper.cpp DjVuErrorList.cpp DjVuFile.cpp
  DjVuFileCache.cpp DjVuGlobal.cpp DjVuGlobalMemory.cpp DjVuImage.cpp
  DjVuInfo.cpp DjVuMessage.cpp DjVuMessageLite.cpp DjVuNavDir.cpp
  DjVuPalette.cpp DjVuPort.cpp DjVuText.cpp DjVuToPS.cpp GBitmap.cpp
  GContainer.cpp GException.cpp GIFFManager.cpp GMapAreas.cpp GOS.cpp
  GPixmap.cpp GRect.cpp GScaler.cpp GSmartPointer.cpp GString.cpp
  GThreads.cpp GURL.cpp GUnicode.cpp IFFByteStream.cpp
  IW44EncodeCodec.cpp IW44Image.cpp JB2EncodeCodec.cpp JB2Image.cpp
  JPEGDecoder.cpp MMRDecoder.cpp MMX.cpp UnicodeByteStream.cpp
  XMLParser.cpp XMLTags.cpp ZPCodec.cpp atomic.cpp ddjvuapi.cpp
  debug.cpp miniexp.cpp)
list(TRANSFORM DJVU_SOURCES PREPEND ${DJ}/)
add_library(djvu_static STATIC ${DJVU_SOURCES})
target_compile_definitions(djvu_static PRIVATE HAVE_CONFIG_H DIR_DATADIR="/nonexistent")
# config.h 를 찾는 #include "config.h" 가 우리 설정을 읽도록 이 폴더에 config.h 를 만든다
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/cfg/config.h "#include \"${CMAKE_CURRENT_SOURCE_DIR}/djvu_config.h\"\n")
target_include_directories(djvu_static PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/cfg PUBLIC ${DJ})
target_compile_options(djvu_static PRIVATE -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -w -pthread)

add_library(ash_djvu SHARED ash_djvu_jni.cpp)
target_compile_definitions(ash_djvu PRIVATE HAVE_CONFIG_H)
target_include_directories(ash_djvu PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/cfg)
target_link_libraries(ash_djvu djvu_static)
if(ANDROID)
  target_link_libraries(ash_djvu jnigraphics)
endif()
target_compile_options(ash_djvu PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(ash_djvu PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
