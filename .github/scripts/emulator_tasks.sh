#!/usr/bin/env bash
# Runs inside the booted tablet emulator: instrumented tests, memory report, screenshots.
set -u
OUT=emulator-results
mkdir -p "$OUT/screens"
PKG=com.aldiianshr.iphonemiror.debug
ACT=com.aldiianshr.iphonemiror.MainActivity

adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
adb shell wm size > "$OUT/display.txt"; adb shell wm density >> "$OUT/display.txt"
adb shell dumpsys media.player > /dev/null 2>&1 || true

echo "== Instrumented tests =="
./gradlew connectedDebugAndroidTest --continue > "$OUT/connected-tests.log" 2>&1
TEST_STATUS=$?
cp -r app/build/reports/androidTests "$OUT/androidTest-report" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results "$OUT/androidTest-results" 2>/dev/null || true
adb logcat -d -s PipelineTest:I TestRunner:I > "$OUT/instrumented-logcat.txt" 2>&1 || true
grep -h "MEMORY" "$OUT/instrumented-logcat.txt" > "$OUT/memory-report.txt" 2>/dev/null || true

echo "== Install debug app for screenshots =="
adb install -r -g app/build/outputs/apk/debug/iPhoneMiror-debug.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell settings put system screen_off_timeout 600000 || true
adb shell input keyevent KEYCODE_WAKEUP || true
# The emulator's own launcher sometimes ANRs under software rendering; hide system
# error dialogs so they don't cover the app screenshots.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true

shot() {
  local name=$1; shift
  adb shell am start -W -n "$PKG/$ACT" "$@" > /dev/null
  sleep 4
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/screens/$name.png"
}

adb logcat -c
shot 01-dashboard-off --es start_route DASHBOARD
shot 02-dashboard-receiver-on --es start_route DASHBOARD --ez start_receiver true
sleep 2
shot 03-connect --es start_route CONNECT
shot 04-device-manager --es start_route DEVICES
shot 05-history --es start_route HISTORY
shot 06-apple-account --es start_route ACCOUNT
shot 07-settings --es start_route SETTINGS
shot 08-about --es start_route ABOUT
shot 09-mirroring-waiting --es start_route MIRRORING

# Self-test: real AirPlay handshake + FairPlay + MediaCodec decode over localhost.
adb shell am start -W -n "$PKG/$ACT" --es start_route MIRRORING --ez start_receiver true --ez run_self_test true > /dev/null
sleep 8
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
sleep 1
adb exec-out screencap -p > "$OUT/screens/10-mirroring-self-test.png"
adb shell dumpsys meminfo $PKG > "$OUT/meminfo-during-stream.txt"
sleep 6
adb shell dumpsys meminfo $PKG > "$OUT/meminfo-after-stream.txt"
shot 11-dashboard-after-self-test --es start_route DASHBOARD
adb logcat -d > "$OUT/app-logcat.txt" 2>&1 || true
grep -E "AirPlayReceiver|VideoDecoder|SelfTest|MirrorChannel|Pairing|AndroidRuntime" "$OUT/app-logcat.txt" > "$OUT/app-logcat-filtered.txt" || true

echo "instrumented_tests_exit=$TEST_STATUS" > "$OUT/status.txt"
exit $TEST_STATUS
