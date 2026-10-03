#!/usr/bin/env bash
set -euo pipefail
case "$SCREENSHOT_THEME" in
  light) adb shell cmd uimode night no;;
  dark) adb shell cmd uimode night yes;;
  *) exit 1;;
esac
adb shell wm size 720x1280
adb shell wm density 320
adb logcat -G 16M
adb logcat -c
mkdir -p "$RUNNER_TEMP/takupoke-screenshots"
# Stream during the test, before Gradle's device teardown can clear app data or logs.
adb logcat -v raw -s TakupokeScreenshots:I '*:S' > "$RUNNER_TEMP/takupoke-screenshots/capture.log" &
capture_log_pid=$!
trap 'kill "$capture_log_pid" 2>/dev/null || true' EXIT
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g -Pandroid.testInstrumentationRunnerArguments.class=jp.n624.takupoke.android.ScreenTest,jp.n624.takupoke.android.RecoveryScreenTest -Ptakupoke.captureScreenshots=true
kill "$capture_log_pid"
wait "$capture_log_pid" || true
trap - EXIT
node scripts/screenshot-transfer.mjs decode "$RUNNER_TEMP/takupoke-screenshots/capture.log" "$RUNNER_TEMP/takupoke-screenshots/verified"
node scripts/screenshot-transfer.mjs encode "$RUNNER_TEMP/takupoke-screenshots/verified"
