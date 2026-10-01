#!/usr/bin/env bash
set -euo pipefail
while IFS= read -r -d '' file; do
  case "$file" in
    AGENTS.md|docs/*|internal/*|private/*|secrets/*|*.pdf|*.xlsx|*.csv|*.jks|*.keystore|*.db|*.log|*.apk|*.aab|local.properties|.env*)
      echo "File outside public source policy: $file" >&2
      exit 1;;
  esac
done < <(git ls-files -z)
if rg -n 'actions/cache@|cache:[[:space:]]*(gradle|maven)|cache-disabled:[[:space:]]*false|upload-artifact@' .github/workflows; then
  echo 'Persistent CI cache/artifact storage is not allowed.' >&2
  exit 1
fi
echo 'Public file and nonpersistent cache policy: OK'
