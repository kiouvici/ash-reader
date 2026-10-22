#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# API 26 x86 에뮬레이터를 직접 띄워 계측 시험을 돌린다(단계마다 notice/error 주석).
# 사용법: emulator-run.sh <모듈> <로그태그> <로그파일>
set -u
M="$1"; T="$2"; L="$3"
note(){ echo "::notice title=emu::$*"; }
err(){ echo "::error title=emu::$*"; }
SDK="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
SM=$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)
AVDM=$(ls "$SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)
ADB="$SDK/platform-tools/adb"
IMG="system-images;android-26;google_apis;x86"
note "sdkmanager=$SM"
yes | "$SM" --install emulator "platforms;android-26" "$IMG" > sdk.log 2>&1 || { err "sdk install 실패: $(tail -3 sdk.log | tr '\n' ' ')"; exit 1; }
note "image: $(ls "$SDK/system-images/android-26/" 2>&1 | tr '\n' ' ')"
echo no | "$AVDM" create avd -n t26 -k "$IMG" --force > avd.log 2>&1 || { err "avd 실패: $(tail -3 avd.log | tr '\n' ' ')"; exit 1; }
note "kvm: $(ls -l /dev/kvm 2>&1)"
"$SDK/emulator/emulator" -avd t26 -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -memory 2048 > emu.log 2>&1 &
EP=$!
b=""
for i in $(seq 1 200); do
  b=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$b" = 1 ] && break
  if ! kill -0 $EP 2>/dev/null; then err "에뮬레이터 종료: $(tail -8 emu.log | tr '\n' ' ')"; exit 1; fi
  sleep 5
done
[ "$b" = 1 ] || { err "부팅 시간 초과: $(tail -8 emu.log | tr '\n' ' ')"; exit 1; }
note "boot ok after $((i*5))s"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" shell settings put global $k 0; done
"$ADB" logcat -c
./gradlew --console=plain ":$M:connectedDebugAndroidTest" > gradle-test.log 2>&1; rc=$?
"$ADB" logcat -d -s "$T:*" AndroidRuntime:E TestRunner:* > "$L" || true
if [ $rc -ne 0 ]; then
  grep -E "FAILED|Exception|What went wrong|error:" -A2 gradle-test.log | head -30 | while IFS= read -r x; do err "gradle: $x"; done
  cat "$M"/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null | grep -A4 "<failure" | head -20 | while IFS= read -r x; do err "junit: $x"; done
  grep -E "FATAL|Exception|UnsatisfiedLink" "$L" | head -15 | while IFS= read -r x; do err "logcat: $x"; done
fi
grep -o "$T|.*" "$L" | while IFS= read -r x; do note "$x"; done
kill $EP 2>/dev/null
exit $rc
