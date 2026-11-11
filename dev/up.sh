#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Starts the local development environment and waits until every service is healthy.
#
# Usage: dev/up.sh [--s3] [service ...]
#
#   --s3        also start MinIO (S3-compatible object storage)
#   service     start only these services (default: all services of the active profiles)

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
compose=(docker compose --project-directory "$DEV_DIR" -f "$DEV_DIR/docker-compose.yaml")
services=()
for arg in "$@"; do
    case "$arg" in
        --s3) compose+=(--profile s3) ;;
        -*)
            echo "unknown option: $arg" >&2
            exit 2
            ;;
        *) services+=("$arg") ;;
    esac
done

if ! docker info >/dev/null 2>&1; then
    echo "Docker is not running. Start Docker and try again." >&2
    exit 1
fi

"${compose[@]}" up -d --wait --wait-timeout 300 ${services[@]+"${services[@]}"}
"${compose[@]}" ps
