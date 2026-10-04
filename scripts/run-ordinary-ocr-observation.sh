#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_SHA:-}" != ""
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Ptakupoke.ordinaryOcrObservation=true --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.cpu.abi
log="$RUNNER_TEMP/ordinary-ocr-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.log"
set +e
timeout 900 adb shell am instrument -w -r -e class jp.n624.takupoke.android.OrdinaryOriginalOcrObservationTest#acquisitionOnly -e takupoke.sourceCommit "$GITHUB_SHA" jp.n624.takupoke.android.test/jp.n624.takupoke.android.OfflineRunner 2>&1 | tee "$log"
native_status=${PIPESTATUS[0]}
set -e
collect_status=0
python3 scripts/collect-ordinary-ocr-observation.py "$log" --output "$RUNNER_TEMP/ordinary-ocr-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.json" || collect_status=$?
test "$native_status" -eq 0
test "$collect_status" -eq 0
rg -q '^OK \(1 test\)' "$log"
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$log"
