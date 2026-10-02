#!/usr/bin/env bash
set -euo pipefail
while IFS= read -r -d '' file; do
  case "$file" in
    AGENTS.md|docs/*|internal/*|private/*|secrets/*|*.pdf|*.xlsx|*.csv|*.jks|*.keystore|*.db|*.log|*.apk|*.aab|local.properties|.env*)
      echo "File outside public source policy: $file" >&2
      exit 1;;
  esac
done < <(git ls-files -z)
if grep -RnE 'actions/cache@|cache:[[:space:]]*(gradle|maven)|cache-disabled:[[:space:]]*false|upload-artifact@' .github/workflows; then
  echo 'Persistent CI cache/artifact storage is not allowed.' >&2
  exit 1
else
  check_status=$?
  if [[ "$check_status" -ne 1 ]]; then
    echo 'Could not check the CI cache/artifact policy.' >&2
    exit "$check_status"
  fi
fi
echo 'Public file and nonpersistent cache policy: OK'
