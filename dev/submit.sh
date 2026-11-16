#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Submits a job file to the master of the cluster profile and waits for its end; exits with 0 when
# the run succeeds. Until the server and pipeforge run submit jobs to a cluster, this is how jobs
# reach it.
#
# Usage: dev/submit.sh <job.yaml> [--timeout 5min]

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
dist="$("$DEV_DIR/distribution.sh")"
if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
else
    java=java
fi
export PIPEFORGE_CLUSTER_TOKEN="${PIPEFORGE_CLUSTER_TOKEN:-pipeforge-dev-token}"
exec "$java" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$dist/lib/*" \
    org.devlive.pipeforge.dist.SubmitMain "$@" --master "localhost:${PIPEFORGE_MASTER_PORT:-36123}"
