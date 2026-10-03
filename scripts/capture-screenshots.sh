#!/usr/bin/env bash
set -euo pipefail
case "$SCREENSHOT_THEME" in
  light) screenshot_night_mode=no;;
  dark) screenshot_night_mode=yes;;
  *) exit 1;;
esac
# Android 10's default emulator locks night-mode changes to privileged callers.
# This disposable google_apis image permits adbd root; the app remains its own UID.
screenshot_sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$screenshot_sdk" == 29 ]]; then
  adb root
  adb wait-for-device
fi
adb shell cmd uimode night "$screenshot_night_mode"
# Android 10 can defer a system night-mode change until the next screen-off.
# Apply the real OS configuration before the test activity starts.
adb shell input keyevent KEYCODE_SLEEP
sleep 0.5
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell dumpsys uimode
screenshot_actual_night="$(adb shell cmd uimode night)"
printf '%s\n' "$screenshot_actual_night"
if [[ "$screenshot_actual_night" != *"Night mode: $screenshot_night_mode"* ]]; then
  printf 'Requested screenshot theme was not applied by the real OS.\n' >&2
  exit 1
fi
adb shell wm size 720x1280
adb shell wm density 320
adb logcat -G 16M
adb logcat -c
mkdir -p "$RUNNER_TEMP/takupoke-screenshots"
# Stream during the test, before Gradle's device teardown can clear app data or logs.
adb logcat -v raw -s TakupokeScreenshots:I '*:S' > "$RUNNER_TEMP/takupoke-screenshots/capture.log" &
capture_log_pid=$!
trap 'kill "$capture_log_pid" 2>/dev/null || true' EXIT
if ./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g -Pandroid.testInstrumentationRunnerArguments.class=jp.n624.takupoke.android.ScreenTest,jp.n624.takupoke.android.RecoveryScreenTest -Ptakupoke.captureScreenshots=true; then
  :
else
  capture_test_status=$?
  # This emulator contains only invented offline fixtures. A crashed test process
  # can leave an empty JUnit failure; collect only its runtime error channel.
  adb logcat -d -v brief -s AndroidRuntime:E '*:S' | tail -n 160 || true
  exit "$capture_test_status"
fi
kill "$capture_log_pid"
wait "$capture_log_pid" || true
trap - EXIT
node scripts/screenshot-transfer.mjs decode "$RUNNER_TEMP/takupoke-screenshots/capture.log" "$RUNNER_TEMP/takupoke-screenshots/verified"
node scripts/screenshot-transfer.mjs encode "$RUNNER_TEMP/takupoke-screenshots/verified"
