#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# The M1 acceptance benchmark: builds the distribution, then copies the given number of rows from
# MySQL to PostgreSQL on a cluster of three workers and writes a report to
# pipeforge-e2e/target/benchmark/ (latest.md, latest.json). See development/benchmarks.
#
#   tools/benchmark/m1.sh [--rows N] [--parallelism N] [--slots-per-worker N] [--split-count N]
#                         [--enforce-target] [--skip-build]
#
# External databases come from the environment, else MySQL and PostgreSQL run in Docker:
#   PIPEFORGE_BENCH_MYSQL_URL, PIPEFORGE_BENCH_MYSQL_USER, PIPEFORGE_BENCH_MYSQL_PASSWORD
#   PIPEFORGE_BENCH_POSTGRES_URL, PIPEFORGE_BENCH_POSTGRES_USER, PIPEFORGE_BENCH_POSTGRES_PASSWORD
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
properties=()
build=true

usage() {
  sed -n '8,17p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --rows|--parallelism|--slots-per-worker|--split-count)
      [ $# -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }
      case "$2" in
        ''|*[!0-9]*) echo "$1 needs a whole number, not '$2'" >&2; exit 2 ;;
      esac
      properties+=("-Dpipeforge.benchmark.${1#--}=$2")
      shift 2
      ;;
    --enforce-target) properties+=("-Dpipeforge.benchmark.enforce-target=true"); shift ;;
    --skip-build) build=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

add() {
  # $1: environment variable, $2: setting
  if [ -n "${!1:-}" ]; then
    properties+=("-Dpipeforge.benchmark.$2=${!1}")
  fi
}
add PIPEFORGE_BENCH_MYSQL_URL mysql.url
add PIPEFORGE_BENCH_MYSQL_USER mysql.user
add PIPEFORGE_BENCH_MYSQL_PASSWORD mysql.password
add PIPEFORGE_BENCH_POSTGRES_URL postgres.url
add PIPEFORGE_BENCH_POSTGRES_USER postgres.user
add PIPEFORGE_BENCH_POSTGRES_PASSWORD postgres.password
commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
properties+=("-Dpipeforge.benchmark.commit=$commit")

cd "$root"
if [ "$build" = true ]; then
  ./mvnw -B -ntp install -pl pipeforge-dist -am -DskipTests -Djacoco.skip=true
fi
./mvnw -B -ntp verify -pl pipeforge-e2e -Pbenchmark -Djacoco.skip=true "${properties[@]}"
