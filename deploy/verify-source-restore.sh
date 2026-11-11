#!/usr/bin/env bash
set -euo pipefail
cd /opt/mossdoom/current
output=$(bash restore-check.sh "${1:?backup path required}")
printf '%s\n' "$output"
target=$(printf '%s\n' "$output" | awk '/^RESTORE_OK / {print $2;exit}')
test -n "$target"
python3 /tmp/mossdoom-framework-bundle/restore-source-check.py "$target"
