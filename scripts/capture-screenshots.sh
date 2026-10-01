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
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g -Pandroid.testInstrumentationRunnerArguments.class=jp.n624.takupoke.android.ScreenTest -Ptakupoke.captureScreenshots=true
mkdir -p "$RUNNER_TEMP/takupoke-screenshots"
adb logcat -d -v raw -s TakupokeScreenshots:I '*:S' > "$RUNNER_TEMP/takupoke-screenshots/capture.log"
node scripts/screenshot-transfer.mjs decode "$RUNNER_TEMP/takupoke-screenshots/capture.log" "$RUNNER_TEMP/takupoke-screenshots/verified"
node scripts/screenshot-transfer.mjs encode "$RUNNER_TEMP/takupoke-screenshots/verified"
