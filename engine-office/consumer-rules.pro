# SPDX-License-Identifier: AGPL-3.0-or-later
# POI(poi + scratchpad)가 참조하지만 안드로이드에 없거나 쓰지 않는 클래스. HWPF 텍스트 경로는 이들을 실행하지 않는다.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.crypto.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.ietf.jgss.**
-dontwarn org.osgi.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn java.beans.**
# POI는 리플렉션으로 레코드 클래스를 만든다(HSSF/HWPF 레코드 표).
-keep class org.apache.poi.hwpf.** { *; }
-keep class org.apache.poi.poifs.filesystem.** { *; }
