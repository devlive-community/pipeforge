#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Stops the local development environment.
#
# Usage: dev/down.sh [--volumes]     --volumes also deletes the data volumes

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
args=()
for arg in "$@"; do
    case "$arg" in
        -v | --volumes) args+=(--volumes) ;;
        *)
            echo "unknown option: $arg" >&2
            exit 2
            ;;
    esac
done

docker compose --project-directory "$DEV_DIR" -f "$DEV_DIR/docker-compose.yaml" --profile s3 down --remove-orphans ${args[@]+"${args[@]}"}
