#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Prints the directory of the PipeForge distribution the cluster profile runs: PIPEFORGE_DIST when
# it is set, the installation around dev/ in an unpacked release, or the newest one built in
# pipeforge-dist/target/ of a checkout. Exits with 1 when there is none.

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${PIPEFORGE_DIST:-}" ]; then
    echo "$PIPEFORGE_DIST"
    exit 0
fi
if [ -x "$DEV_DIR/../bin/pipeforge-master.sh" ]; then
    (cd "$DEV_DIR/.." && pwd)
    exit 0
fi
newest=""
for dir in "$DEV_DIR"/../pipeforge-dist/target/pipeforge-*/; do
    if [ -x "${dir}bin/pipeforge-master.sh" ] && { [ -z "$newest" ] || [ "$dir" -nt "$newest" ]; }; then
        newest="$dir"
    fi
done
if [ -z "$newest" ]; then
    echo "No PipeForge distribution found; build it with ./mvnw -pl pipeforge-dist -am package -DskipTests" >&2
    exit 1
fi
(cd "$newest" && pwd)
