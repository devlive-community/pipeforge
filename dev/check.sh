#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Verifies that the running development environment is usable, beyond the container health
# checks: binlog and GTID on MySQL, logical decoding on PostgreSQL, the MinIO buckets, the
# Toxiproxy proxies, Prometheus and the Grafana data source.
#
# Usage: dev/check.sh    (MinIO is checked when it is running)

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
compose=(docker compose --project-directory "$DEV_DIR" -f "$DEV_DIR/docker-compose.yaml" --profile s3)
failed=0

expect() { # <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        echo "  ok    $1"
    else
        echo "  FAIL  $1 (expected '$2', got '$3')"
        failed=$((failed + 1))
    fi
}

mysql_query() { # <host> <port> <sql>
    "${compose[@]}" exec -T mysql mysql -N -upipeforge -ppipeforge -h"$1" -P"$2" pipeforge -e "$3" \
        2>/dev/null | tr -d '\r'
}
pg_query() {
    "${compose[@]}" exec -T postgres psql -U pipeforge -d pipeforge -tAc "$1" | tr -d '\r'
}

echo "mysql:"
tab="$(printf '\t')"
expect "binlog enabled" "log_bin${tab}ON" "$(mysql_query 127.0.0.1 3306 "SHOW VARIABLES LIKE 'log_bin'")"
expect "row-based binlog" "binlog_format${tab}ROW" \
    "$(mysql_query 127.0.0.1 3306 "SHOW VARIABLES LIKE 'binlog_format'")"
expect "GTID mode" "gtid_mode${tab}ON" "$(mysql_query 127.0.0.1 3306 "SHOW VARIABLES LIKE 'gtid_mode'")"
expect "sample orders" "6" "$(mysql_query 127.0.0.1 3306 'SELECT COUNT(*) FROM orders')"

echo "postgres:"
expect "logical WAL" "logical" "$(pg_query 'SHOW wal_level')"
expect "replication role" "t" "$(pg_query "SELECT rolreplication FROM pg_roles WHERE rolname = 'pipeforge'")"
expect "sample products" "4" "$(pg_query 'SELECT COUNT(*) FROM inventory.products')"

if [ -n "$("${compose[@]}" ps -q minio 2>/dev/null)" ]; then
    echo "minio:"
    buckets="$("${compose[@]}" exec -T minio /bin/sh -c \
        'mc alias set local http://localhost:9000 pipeforge pipeforge-secret >/dev/null && mc ls local' \
        2>/dev/null | awk '{print $NF}' | tr -d '/\r' | sort | paste -sd, -)"
    expect "buckets" "pipeforge,pipeforge-checkpoints" "$buckets"
else
    echo "minio: not running (start it with dev/up.sh --s3)"
fi

echo "toxiproxy:"
proxies="$("${compose[@]}" exec -T toxiproxy /toxiproxy-cli list 2>/dev/null |
    awk '$1 == "mysql" || $1 == "postgres" {print $1}' | sort | paste -sd, -)"
expect "proxies" "mysql,postgres" "$proxies"
expect "mysql through the proxy" "6" "$(mysql_query toxiproxy 23306 'SELECT COUNT(*) FROM orders')"

echo "prometheus:"
expect "ready" "0" "$("${compose[@]}" exec -T prometheus wget -qO- http://localhost:9090/-/ready >/dev/null 2>&1
    echo $?)"

echo "grafana:"
datasource="$("${compose[@]}" exec -T grafana wget -qO- http://admin:pipeforge@localhost:3000/api/datasources \
    2>/dev/null | grep -o '"name":"Prometheus"' || true)"
expect "Prometheus data source" '"name":"Prometheus"' "$datasource"

if [ "$failed" -gt 0 ]; then
    echo "$failed check(s) failed" >&2
    exit 1
fi
echo "development environment ok"
