#!/bin/sh
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Runs a cluster process: pipeforge-daemon.sh <master|worker|standalone> <start|stop|status|restart|run>
# [--set key=value]... [--labels key=value,...]. The role scripts call it.
#
#   start    starts the process in the background and waits until it runs
#   stop     stops it (SIGTERM, then SIGKILL after 60 seconds)
#   status   exit code 0 when it runs, 3 when it does not
#   restart  stop, then start
#   run      runs it in the foreground, for containers and service managers
#
# JAVA_HOME selects the Java runtime (21 or later), JAVA_OPTS adds JVM options to those of
# conf/jvm.options, PIPEFORGE_LOG_DIR moves the logs (default: logs/ of the installation).

set -e

role="$1"
command="$2"
if [ $# -ge 2 ]; then
  shift 2
fi
case "$role" in
  master|worker|standalone) ;;
  *)
    echo "usage: pipeforge-daemon.sh <master|worker|standalone> <start|stop|status|restart|run> [options]" >&2
    exit 2
    ;;
esac

# The installation directory, following symbolic links to this script.
script="$0"
while [ -h "$script" ]; do
  link=$(ls -ld "$script" | sed 's/.*-> //')
  case "$link" in
    /*) script="$link" ;;
    *) script="$(dirname "$script")/$link" ;;
  esac
done
PIPEFORGE_HOME=$(cd "$(dirname "$script")/.." && pwd)

if [ -n "$JAVA_HOME" ]; then
  java="$JAVA_HOME/bin/java"
else
  java=java
fi
log_dir="${PIPEFORGE_LOG_DIR:-$PIPEFORGE_HOME/logs}"
pid_file="$PIPEFORGE_HOME/run/$role.pid"
out_file="$log_dir/pipeforge-$role.out"

# The JVM options of this role: lines of conf/jvm.options without a prefix, and those with "<role>:".
jvm_options() {
  file="$PIPEFORGE_HOME/conf/jvm.options"
  [ -f "$file" ] || return 0
  sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$file" | while IFS= read -r line; do
    case "$line" in
      ''|'#'*) ;;
      "$role:"*) printf '%s\n' "${line#"$role":}" ;;
      master:*|worker:*|standalone:*) ;;
      *) printf '%s\n' "$line" ;;
    esac
  done | tr '\n' ' '
}

running() {
  [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

launch() {
  # shellcheck disable=SC2046 # the options are words
  exec "$java" $(jvm_options) $JAVA_OPTS \
    -Dpipeforge.home="$PIPEFORGE_HOME" \
    -Dpipeforge.role="$role" \
    -Dpipeforge.log.dir="$log_dir" \
    -Dlogback.configurationFile="$PIPEFORGE_HOME/conf/logback.xml" \
    -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
    -cp "$PIPEFORGE_HOME/lib/*:$PIPEFORGE_HOME/lib/logback/*" \
    org.devlive.pipeforge.dist.ClusterMain "$role" "$@"
}

start() {
  if running; then
    echo "The $role is running already (pid $(cat "$pid_file"))."
    return 0
  fi
  rm -f "$pid_file"
  mkdir -p "$log_dir"
  (launch "$@") >> "$out_file" 2>&1 &
  process=$!
  waited=0
  while [ ! -f "$pid_file" ]; do
    if ! kill -0 "$process" 2>/dev/null; then
      echo "The $role did not start; the end of $out_file:" >&2
      tail -n 20 "$out_file" >&2
      return 1
    fi
    if [ "$waited" -ge 120 ]; then
      echo "The $role did not start within 60 seconds; see $out_file" >&2
      return 1
    fi
    sleep 0.5
    waited=$((waited + 1))
  done
  echo "Started the $role (pid $(cat "$pid_file")); logs in $log_dir."
}

stop() {
  if ! running; then
    rm -f "$pid_file"
    echo "The $role is not running."
    return 0
  fi
  pid=$(cat "$pid_file")
  kill "$pid"
  waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge 120 ]; then
      echo "The $role did not stop within 60 seconds; killing it." >&2
      kill -9 "$pid" 2>/dev/null || true
      break
    fi
    sleep 0.5
    waited=$((waited + 1))
  done
  rm -f "$pid_file"
  echo "Stopped the $role."
}

case "$command" in
  run) launch "$@" ;;
  start) start "$@" ;;
  stop) stop ;;
  restart)
    stop
    start "$@"
    ;;
  status)
    if running; then
      echo "The $role is running (pid $(cat "$pid_file"))."
    else
      echo "The $role is not running."
      exit 3
    fi
    ;;
  *)
    echo "usage: pipeforge-$role.sh <start|stop|status|restart|run> [--set key=value]... [--labels k=v,...]" >&2
    exit 2
    ;;
esac
