#!/usr/bin/env bash
set -euo pipefail
destination="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
{
  echo '### Android verification'
  echo
  rg --no-filename -o '<testsuite[^>]*>' core/build/test-results app/build/test-results app/build/outputs/androidTest-results 2>/dev/null || true
  echo
  echo 'School files and production credentials are not used in tests. No Actions dependency/AVD caches or build artifacts are retained.'
} >> "$destination"
