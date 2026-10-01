#!/bin/bash
# PostToolUse: run Spotless (ktlint) after Kotlin or Gradle script edits, so formatting never needs review.
f=$(jq -r '.tool_input.file_path // empty')
case "$f" in *.kt|*.kts) ;; *) exit 0 ;; esac
source "$(dirname "$0")/env.sh"
out=$(./gradlew spotlessApply -q 2>&1) || { echo "spotlessApply failed (likely a syntax error):" >&2; echo "$out" | tail -20 >&2; exit 2; }
exit 0
