#!/bin/bash
# Stop: refuse to finish a turn with Kotlin that doesn't compile. Skips when no source files changed.
input=$(cat)
[ "$(echo "$input" | jq -r '.stop_hook_active // false')" = "true" ] && exit 0
source "$(dirname "$0")/env.sh"
git status --porcelain -- '*.kt' '*.kts' '*.xml' '*.toml' 2>/dev/null | grep -q . || exit 0
out=$(./gradlew :app:compileDebugKotlin -q 2>&1) || {
    echo "Build is broken. Fix before finishing:" >&2
    echo "$out" | grep -E '^e: |error:|What went wrong' -A3 | head -40 >&2
    exit 2
}
exit 0
