#!/usr/bin/env bash
set -euo pipefail
case "$SCREENSHOT_THEME" in
  light) adb shell cmd uimode night no;;
  dark) adb shell cmd uimode night yes;;
  *) exit 1;;
esac
adb shell wm size 720x1280
adb shell wm density 320
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g -Pandroid.testInstrumentationRunnerArguments.class=jp.n624.takupoke.android.ScreenTest -Pandroid.testInstrumentationRunnerArguments.screenshots=true
mkdir -p "$RUNNER_TEMP/takupoke-screenshots"
for name in 00-setup 01-home 02-links 03-timetable 04-settings 05-materials; do
  adb exec-out run-as jp.n624.takupoke.android cat "files/screenshots/$name.png" > "$RUNNER_TEMP/takupoke-screenshots/$name.png"
done
node scripts/screenshot-transfer.mjs encode "$RUNNER_TEMP/takupoke-screenshots"
