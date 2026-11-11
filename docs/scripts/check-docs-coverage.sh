#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Documentation coverage check (see docs-map.yaml and the docs guide): every feat, perf or
# breaking commit in <base>..<head> that changes mapped code must also change the mapped
# pages in every language, unless it says "Docs-Not-Needed: <reason>"; every module must be
# registered in docs-map.yaml. Needs the docs dependencies (pnpm install).
#
# Usage: docs/scripts/check-docs-coverage.sh <base> [<head>]

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
exec node "$HERE/docs-coverage.mjs" "$@"
