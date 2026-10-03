#!/usr/bin/env bash
set -euo pipefail
adb logcat -G 4M
adb logcat -c
finish_device_test() {
  test_status=$?
  trap - EXIT
  report_status=0
  bash scripts/report-ocr-observations.sh || report_status=$?
  if (( test_status != 0 )); then exit "$test_status"; fi
  exit "$report_status"
}
trap finish_device_test EXIT
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g
