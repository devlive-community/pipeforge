#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Developer Certificate of Origin check: every commit in <base>..<head> must carry a
# "Signed-off-by: Name <email>" trailer that matches its author. Merge commits are skipped.
#
# Usage: check-dco.sh <base> [<head>]

set -euo pipefail

base="${1:-}"
head="${2:-HEAD}"
[ -n "$base" ] || {
    echo "usage: check-dco.sh <base> [<head>]" >&2
    exit 2
}

failed=0
count=0
for sha in $(git rev-list --no-merges "$base..$head"); do
    count=$((count + 1))
    author="$(git log -1 --format='%an <%ae>' "$sha")"
    subject="$(git log -1 --format='%s' "$sha")"
    trailers="$(git log -1 --format='%(trailers:key=Signed-off-by,valueonly)' "$sha")"
    if printf '%s\n' "$trailers" | grep -Fxq "$author"; then
        continue
    fi
    echo "missing or mismatched Signed-off-by: ${sha:0:12} $subject (author: $author)"
    failed=$((failed + 1))
done

if [ "$failed" -gt 0 ]; then
    echo "$failed of $count commit(s) are not signed off. Use 'git commit -s' (see CONTRIBUTING.md)." >&2
    exit 1
fi
echo "DCO ok ($count commit(s) checked)"
