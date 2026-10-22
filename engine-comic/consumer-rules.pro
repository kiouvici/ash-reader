# SPDX-License-Identifier: AGPL-3.0-or-later
# JNI 가 이름으로 찾는 클래스·메서드 유지
-keep class kr.vici.ashreader.engine.comic.NativeComic { *; }
# 전체 풀기 진행 콜백: 네이티브가 Progress.onEntry(int, String) 를 이름으로 부른다
-keep interface kr.vici.ashreader.engine.comic.NativeComic$Progress { *; }
-keep class * implements kr.vici.ashreader.engine.comic.NativeComic$Progress { void onEntry(int, java.lang.String); }
