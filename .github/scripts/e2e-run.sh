#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# 7D 종단(E2E) UI 시험: 에뮬레이터에 앱·시험 APK 를 깔고, 시험 파일을 넣고, 시험 클래스를 하나씩 돌린다.
# 클래스마다 따로 'am instrument' 를 돌려 한 클래스에서 앱이 죽어도 다음 클래스는 돈다.
# 끝나면 화면(단계마다 찍은 PNG)·logcat·결과를 $OUT 에 모으고, 실패·앱 죽음(FATAL EXCEPTION)·ANR 이 있으면 실패로 끝낸다.
#
# 사용법: e2e-run.sh <묶음> <방향>
#   묶음: a(첫 실행·서재·형식) | b(읽기·만화·설정·인텐트·복원 + 프로세스 종료 복원) | portrait(읽기·만화·인텐트) | smoke(API 26)
#   방향: natural | landscape | portrait
# 환경: APK_DIR(app-debug.apk, app-debug-androidTest.apk), FILES_DIR(합성 파일), VIEW_DIR_LOCAL(인텐트용 합성 파일), OUT
set -u
SUITE="${1:-a}"
ORIENT="${2:-natural}"
OUT="${OUT:-e2e-out}"
APK_DIR="${APK_DIR:-apks}"
PKG=kr.vici.ashreader
TPKG=kr.vici.ashreader.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
E=kr.vici.ashreader.e2e
mkdir -p "$OUT/instrument" "$OUT/shots"
FAILED=()
summary() { echo "$*" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"; }
note() { echo "::notice title=e2e::$*"; }

adb wait-for-device
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
note "API $SDK · 묶음 $SUITE · 방향 $ORIENT · $(adb shell wm size | tr -d '\r' | tail -1) · $(adb shell wm density | tr -d '\r' | tail -1)"

# ── 화면 방향 고정 ──
adb shell settings put system accelerometer_rotation 0
if [ "$ORIENT" != natural ]; then
  read -r W H < <(adb shell wm size | tr -d '\r' | tail -1 | sed -E 's/.*: ([0-9]+)x([0-9]+)/\1 \2/')
  NAT=portrait; [ "$W" -gt "$H" ] && NAT=landscape
  ROT=0; [ "$ORIENT" != "$NAT" ] && ROT=1
else
  ROT=0
fi
adb shell settings put system user_rotation $ROT
# Android 12+: 설정값만으로는 큰 화면에서 회전이 안 먹을 때가 있어 wm 으로도 잠근다
[ "$SDK" -ge 31 ] && adb shell wm user-rotation lock $ROT >/dev/null 2>&1
sleep 2
note "회전 $ROT → $(adb shell dumpsys window displays 2>/dev/null | grep -oE 'cur=[0-9]+x[0-9]+' | head -1)"
adb shell settings put global always_finish_activities 0
# 에뮬레이터 시스템 앱(런처 등)의 'isn't responding' 창이 앱을 가리지 않게(우리 앱 ANR 은 logcat 'ANR in' 으로 본다)
adb shell settings put global hide_error_dialogs 1
adb shell svc power stayon true
adb shell input keyevent 82 || true

# ── 설치(권한은 주지 않음: 첫 실행 시험이 직접 허용한다) ──
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb uninstall "$TPKG" >/dev/null 2>&1 || true
adb install -r "$APK_DIR/app-debug.apk" || { echo "::error title=e2e::앱 설치 실패"; exit 1; }
adb install -r "$APK_DIR/app-debug-androidTest.apk" || { echo "::error title=e2e::시험 APK 설치 실패"; exit 1; }

# ── 시험 파일 넣기 ──
adb shell rm -rf /sdcard/Download/ash-e2e /sdcard/Documents/ash-e2e-view
adb shell mkdir -p /sdcard/Download/ash-e2e /sdcard/Documents/ash-e2e-view
find test-files/public -type f ! -name '*.md' -print0 | while IFS= read -r -d '' f; do adb push "$f" /sdcard/Download/ash-e2e/ >/dev/null; done
for f in "${FILES_DIR:-e2e-files}"/*; do adb push "$f" /sdcard/Download/ash-e2e/ >/dev/null; done
for f in "${VIEW_DIR_LOCAL:-e2e-files-view}"/*; do adb push "$f" /sdcard/Documents/ash-e2e-view/ >/dev/null; done
adb shell ls -la /sdcard/Download/ash-e2e /sdcard/Documents/ash-e2e-view | tee "$OUT/pushed-files.txt"

# ── logcat 전체 기록 ──
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

grant() {
  if [ "$SDK" -ge 30 ]; then adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow; else adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE; fi
  [ "$SDK" -ge 33 ] && adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
  true
}

# run <클래스[#메서드]> <시간 제한 초>
run() {
  local target="$1" limit="${2:-900}" name log start dur
  name="${target//[#.]/_}"
  log="$OUT/instrument/$name.txt"
  start=$(date +%s)
  echo "=== $target"
  # 회전이 풀려 있으면 다시 고정(태블릿 세로에서 런처 ANR 뒤 가로로 돌아간 일이 있었다)
  adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation $ROT
  [ "$SDK" -ge 31 ] && adb shell wm user-rotation lock $ROT >/dev/null 2>&1
  timeout "$limit" adb shell am instrument -w -r -e class "$E.$target" "$TPKG/$RUNNER" > "$log" 2>&1
  local rc=$?
  dur=$(( $(date +%s) - start ))
  local ok=1
  [ $rc -ne 0 ] && ok=0
  grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_CODE: 0|shortMsg=" "$log" && ok=0
  grep -qE "^OK \(" "$log" || ok=0
  local tests fails
  tests=$(grep -cE "INSTRUMENTATION_STATUS_CODE: 1$" "$log" || true)
  fails=$(grep -cE "INSTRUMENTATION_STATUS_CODE: -2$" "$log" || true)
  if [ $ok -eq 1 ]; then
    summary "| ✅ | \`$target\` | $tests | 0 | ${dur}s |"
  else
    [ $rc -eq 124 ] && echo "::error title=e2e $target::시간 초과(${limit}s)"
    summary "| ❌ | \`$target\` | $tests | $fails | ${dur}s |"
    FAILED+=("$target")
    # 실패한 시험 이름과 첫 줄(원인)을 주석으로
    awk '/INSTRUMENTATION_STATUS: test=/{t=$0} /INSTRUMENTATION_STATUS: stack=/{s=$0; getline n; print t " :: " s " " n}' "$log" | sed -e 's/INSTRUMENTATION_STATUS: //g' | head -8 | while IFS= read -r x; do
      echo "::error title=e2e $target::${x:0:900}"
    done
    grep -E "shortMsg=|longMsg=" "$log" | head -3 | while IFS= read -r x; do echo "::error title=e2e $target::$x"; done
  fi
  # 시험 사이에 앱을 정리하지 않는다(데이터 유지). 키보드·창만 닫기
  adb shell input keyevent 4 >/dev/null 2>&1 || true
}

summary "### E2E — API $SDK · $SUITE · $ORIENT"
summary ""
summary "| | 시험 | 통과 | 실패 | 시간 |"
summary "|---|---|---|---|---|"

# 프로세스 종료 뒤 복원(시험 프로세스 밖): 앱을 띄워 책을 열고 넘긴 뒤 홈 → am kill → 런처로 다시 열기 → 읽던 책이어야 한다
process_death() {
  local start dump title="e2e-epub 소설"
  start=$(date +%s)
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  ui() {
    local i
    for i in 1 2 3; do
      adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
      adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "<hierarchy" && { adb shell cat /sdcard/ui.xml; return; }
      sleep 1
    done
  }
  # $1 = 속성 이름(content-desc|text|class), $2 = 값 앞부분 정규식 → 가운데 "x y"
  center() {
    ui | tr '>' '\n' | grep -E "$1=\"$2" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | awk 'NF==4{print int(($1+$3)/2), int(($2+$4)/2)}'
  }
  # 검색으로 책 찾기
  local xy
  xy=$(center content-desc "검색")
  [ -n "$xy" ] && adb shell input tap $xy && sleep 1.5
  xy=$(center class "android.widget.EditText")
  [ -n "$xy" ] && adb shell input tap $xy && sleep 0.5
  adb shell input text "e2e-epub" && sleep 2
  xy=$(center content-desc "$title, ")
  if [ -z "$xy" ]; then
    adb exec-out screencap -p > "$OUT/shots/process-death-0-nobook.png"
    summary "| ❌ | \`process-death\` | 0 | 1 | $(( $(date +%s) - start ))s |"; FAILED+=("process-death"); echo "::error title=e2e process-death::서재에서 책을 찾지 못함"; return
  fi
  adb shell input tap $xy; sleep 7
  xy=$(center text "알겠어요"); [ -n "$xy" ] && adb shell input tap $xy && sleep 1
  read -r W H < <(adb shell wm size | tr -d '\r' | tail -1 | sed -E 's/.*: ([0-9]+)x([0-9]+)/\1 \2/')
  local rot; rot=$(adb shell settings get system user_rotation | tr -d '\r')
  if [ "$rot" = 1 ] || [ "$rot" = 3 ]; then local t=$W; W=$H; H=$t; fi
  for _ in 1 2 3; do adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 1.5; done
  local before; before=$(ui | grep -oE "content-desc=\"$title [0-9]+쪽\"" | head -1)
  adb exec-out screencap -p > "$OUT/shots/process-death-1-before.png"
  # 진단 로그(AshAnchor)의 마지막 'doc n=' = 그때 배치의 전체 쪽 수
  docn() { adb logcat -d -s AshAnchor:I 2>/dev/null | grep -oE 'doc n=[0-9]+' | tail -1 | grep -oE '[0-9]+'; }
  local n1; n1=$(docn)
  adb shell input keyevent 3; sleep 2
  adb shell am kill "$PKG"; sleep 2
  local alive; alive=$(adb shell pidof "$PKG" | tr -d '\r')
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 8
  local after; after=$(ui | grep -oE "content-desc=\"$title [0-9]+쪽\"" | head -1)
  adb exec-out screencap -p > "$OUT/shots/process-death-2-after.png"
  local n2; n2=$(docn)
  echo "프로세스 종료 복원: 전=[$before] 후=[$after] 전체 쪽 수 $n1→$n2 (kill 뒤 pid='$alive')" | tee "$OUT/process-death.txt"
  # 판정(작업 11): 같은 배치면 같은 쪽. 배치가 달라졌으면(쪽 수 흔들림 = 작업 12) 비율로 옮긴 쪽 ±1 안
  local ok=0 p1 p2
  p1=$(printf '%s' "$before" | grep -oE '[0-9]+쪽' | grep -oE '[0-9]+')
  p2=$(printf '%s' "$after" | grep -oE '[0-9]+쪽' | grep -oE '[0-9]+')
  if [ -n "$p1" ] && [ -n "$p2" ]; then
    if [ -z "$n1" ] || [ -z "$n2" ] || [ "$n1" = "$n2" ]; then
      [ "$p1" = "$p2" ] && ok=1
    else
      local est=$(( (p1 * n2 + n1 / 2) / n1 )) d
      d=$(( p2 - est )); [ "$d" -lt 0 ] && d=$(( -d ))
      [ "$d" -le 1 ] && ok=1
      echo "  배치가 달라짐 → 비율 짐작 ${est}쪽, 차이 $d (±1 안이면 통과)" | tee -a "$OUT/process-death.txt"
    fi
  fi
  if [ "$ok" = 1 ]; then
    summary "| ✅ | \`process-death (am kill → 런처)\` | 1 | 0 | $(( $(date +%s) - start ))s |"
  else
    summary "| ❌ | \`process-death (am kill → 런처)\` | 0 | 1 | $(( $(date +%s) - start ))s |"
    FAILED+=("process-death")
    echo "::error title=e2e process-death::프로세스 종료 뒤 읽던 쪽으로 돌아오지 않음: 전=[$before] 후=[$after] 쪽 수 $n1→$n2"
  fi
}

case "$SUITE" in
  a)
    run A01OnboardingTest 300; grant
    run B02LibraryTest 600
    run C03FormatsTest 1200
    ;;
  b)
    grant
    run D04ReadingTest 1200
    run E05ComicTest 900
    run F06SettingsTest 600
    run G07IntentTest 600
    run H08RestoreTest 400
    process_death
    ;;
  portrait)
    grant
    run D04ReadingTest 1200
    run E05ComicTest 900
    run G07IntentTest 600
    ;;
  smoke)
    run A01OnboardingTest 300; grant
    run "C03FormatsTest#epub" 300
    run "C03FormatsTest#hwp03" 300
    run "G07IntentTest#sameFileTwice_whileReading" 300
    ;;
  *) echo "모르는 묶음: $SUITE"; exit 2 ;;
esac

# ── 화면 꺼내기(앱 내부 files/e2e-shots, 디버그 APK 라 run-as) ──
for f in $(adb shell run-as "$PKG" find files/e2e-shots -name '*.png' 2>/dev/null | tr -d '\r'); do
  mkdir -p "$OUT/shots/$(dirname "${f#files/e2e-shots/}")"
  adb exec-out run-as "$PKG" cat "$f" > "$OUT/shots/${f#files/e2e-shots/}"
done
echo "화면 $(find "$OUT/shots" -name '*.png' | wc -l)장"

sleep 1
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

# 작업 11 진단: 읽던 자리 로그(AshAnchor, 숫자만)를 주석으로(산출물 없이도 읽게). 주석 하나 3.5KB, 최대 8개
if grep -q "AshAnchor" "$OUT/logcat.txt"; then
  grep "AshAnchor" "$OUT/logcat.txt" | sed -E 's/^[0-9-]+ ([0-9:.]+) +[0-9]+ +[0-9]+ [A-Z] AshAnchor: /\1 /' > "$OUT/anchor-log.txt"
  split -C 3400 "$OUT/anchor-log.txt" /tmp/ash-anchor-
  n=0
  for f in /tmp/ash-anchor-*; do
    n=$((n + 1)); [ "$n" -gt 8 ] && break
    echo "::notice title=AshAnchor $SUITE $n::$(sed -e 's/%/%25/g' "$f" | sed ':a;N;$!ba;s/\n/%0A/g')"
  done
  rm -f /tmp/ash-anchor-*
fi

# ── 앱 죽음·ANR 검사 ──
# (다른 앱의 FATAL EXCEPTION 은 빼고, 'Process: kr.vici.ashreader' 가 붙은 것만)
CRASH=$( { grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -E "Process: $PKG(,|$| )"; grep -E "ANR in $PKG|>>> $PKG <<<|Fatal signal [0-9]+ .*\($PKG\)" "$OUT/logcat.txt"; } | head -20)
if [ -n "$CRASH" ]; then
  echo "$CRASH" > "$OUT/crashes.txt"
  # 죽은 곳 앞뒤를 주석으로
  grep -nE -A14 "FATAL EXCEPTION|ANR in $PKG|>>> $PKG <<<" "$OUT/logcat.txt" | head -80 | sed -e 's/%/%25/g' > "$OUT/crash-excerpt.txt"
  ENC="$(head -c 3500 "$OUT/crash-excerpt.txt" | sed ':a;N;$!ba;s/\n/%0A/g')"
  echo "::error title=e2e 앱 죽음/ANR::${ENC}"
  summary ""
  summary "**앱 죽음/ANR 이 logcat 에 있어요** (crashes.txt)"
  FAILED+=("crash-or-anr")
fi

if [ ${#FAILED[@]} -gt 0 ]; then
  summary ""
  summary "실패: ${FAILED[*]}"
  echo "::error title=e2e::실패 ${FAILED[*]}"
  exit 1
fi
summary ""
summary "모두 통과"
exit 0
