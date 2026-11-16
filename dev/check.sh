#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Verifies that the running development environment is usable, beyond the container health
# checks: binlog and GTID on MySQL, logical decoding on PostgreSQL, the MinIO buckets, the
# Toxiproxy proxies, Prometheus and the Grafana data source; and, when the cluster profile runs, a
# job on its three workers.
#
# Usage: dev/check.sh    (MinIO and the cluster are checked when they are running)

set -euo pipefail

DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
compose=(docker compose --project-directory "$DEV_DIR" -f "$DEV_DIR/docker-compose.yaml" --profile s3 --profile cluster)
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

if [ -n "$("${compose[@]}" ps -q master 2>/dev/null)" ]; then
    echo "cluster:"
    registered=0
    for _ in $(seq 1 30); do
        registered="$("${compose[@]}" exec -T master sh -c \
            'grep -c " registered with " /opt/pipeforge/logs/pipeforge-master.log' 2>/dev/null | tr -d '\r' || true)"
        [ "$registered" = "3" ] && break
        sleep 1
    done
    expect "three workers registered" "3" "$registered"
    if output="$("$DEV_DIR/submit.sh" "$DEV_DIR/cluster/fake-to-blackhole.yaml" --timeout 3min 2>&1)"; then
        expect "fake-to-blackhole on the cluster" "SUCCESS" "$(echo "$output" | grep -o SUCCESS | head -1)"
    else
        echo "$output" | sed 's/^/        /'
        expect "fake-to-blackhole on the cluster" "SUCCESS" "failed"
    fi
    expect "rows written" "drop: 30000 in, 30000 out" "$(echo "$output" | grep -o 'drop: .*' | head -1)"
else
    echo "cluster: not running (start it with dev/up.sh --cluster)"
fi

if [ "$failed" -gt 0 ]; then
    echo "$failed check(s) failed" >&2
    exit 1
fi
echo "development environment ok"
