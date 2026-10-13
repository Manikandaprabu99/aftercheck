#!/usr/bin/env bash
# Runs the instrumented tests on the connected emulator (CI), streaming per-test results and
# capturing logcat and screenshots under build/device/ for the workflow artifacts.
set -uo pipefail
mkdir -p build/device build/screens/device
adb logcat -c || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb logcat -v threadtime > build/device/logcat.txt 2>&1 &
LOGCAT=$!
./gradlew :app:connectedDebugAndroidTest --info \
  -Pandroid.testInstrumentationRunnerArguments.liveChessCom=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true 2>&1 \
  | tee build/device/gradle.log \
  | grep -E --line-buffered '(SUCCESS|FAILED|SKIPPED|Starting [0-9]+ tests|Finished [0-9]+ tests|> Task :app:connected|BUILD )'
STATUS=${PIPESTATUS[0]}
kill "$LOGCAT" 2>/dev/null || true
adb pull /sdcard/Android/data/app.aftercheck.debug/files/screens build/screens/device >/dev/null 2>&1 || true
exit "$STATUS"
