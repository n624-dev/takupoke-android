#!/usr/bin/env bash
set -euo pipefail
# This disposable OfflineRunner device contains invented fixtures only. Never
# emit general app logs; only this androidTest-owned OCR observation is read.
ocr_log=$(mktemp)
trap 'rm -f -- "$ocr_log"' EXIT
adb logcat -d -v raw -s TakupokeOcrEvaluation:I '*:S' > "$ocr_log"
node scripts/report-ocr-observations.mjs < "$ocr_log"
