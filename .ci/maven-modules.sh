#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Module impact analysis: maps a list of changed files to the Maven reactor modules that
# must be built. The java CI job passes the result to "mvnw -pl <modules> -am -amd", so the
# changed modules, everything they need and everything that depends on them are verified.
#
# Usage:
#   maven-modules.sh [--root <dir>] [--files <file>|-] [--github-output]
#
# The changed files are read from --files ("-" = stdin, the default). Output:
#   maven_modules=<comma-separated module paths>   build only these (plus -am -amd)
#   maven_modules=:all                             build the full reactor
#   maven_modules=                                 no Maven input changed
#
# Rules:
#   - a file belongs to the nearest enclosing reactor module (modules are read from the
#     <modules> sections of the POMs, recursively, so fixtures and stray POMs are ignored);
#   - the root POM, the wrapper, .mvn/, .java-version and shared build configuration under
#     tools/spotbugs/ affect every module, so they select the full reactor;
#   - root-level files that are not build inputs (README, docs, CI files) select nothing.
#
# Compatible with bash 3.2 (macOS). Needs only sed and grep.

set -euo pipefail

root="."
files_from="-"
gh_output=0

die() {
    echo "error: $*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --root)
            root="${2:-}"
            shift 2
            ;;
        --files)
            files_from="${2:-}"
            shift 2
            ;;
        --github-output)
            gh_output=1
            shift
            ;;
        -h | --help)
            sed -n '8,27p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "unknown option: $1" ;;
    esac
done

[ -f "$root/pom.xml" ] || die "no pom.xml in $root"

# Prints the <module> entries of one POM, one per line.
modules_of() {
    sed -n 's:.*<module>[[:space:]]*\([^<[:space:]]*\)[[:space:]]*</module>.*:\1:p' "$1"
}

# Prints every reactor module directory relative to the root, depth first.
reactor_modules() {
    local dir="$1" m child
    for m in $(modules_of "$root/$dir/pom.xml"); do
        if [ "$dir" = "." ]; then child="$m"; else child="$dir/$m"; fi
        child="${child%/}"
        [ -f "$root/$child/pom.xml" ] || continue
        echo "$child"
        reactor_modules "$child"
    done
}

is_global_input() {
    case "$1" in
        pom.xml | mvnw | mvnw.cmd | .java-version | .mvn/* | tools/spotbugs/*) return 0 ;;
    esac
    return 1
}

modules="$(reactor_modules .)"

# Nearest enclosing reactor module of a path, or nothing.
owner_of() {
    local f="$1" best="" m
    while IFS= read -r m; do
        [ -z "$m" ] && continue
        case "$f" in
            "$m"/*)
                if [ ${#m} -gt ${#best} ]; then best="$m"; fi
                ;;
        esac
    done <<EOF
$modules
EOF
    echo "$best"
}

if [ "$files_from" = "-" ]; then changed="$(cat)"; else changed="$(cat "$files_from")"; fi

selected=""
all=0
while IFS= read -r f; do
    [ -z "$f" ] && continue
    if is_global_input "$f"; then
        all=1
        break
    fi
    owner="$(owner_of "$f")"
    [ -z "$owner" ] && continue
    case ",$selected," in
        *",$owner,"*) ;;
        *) selected="$selected${selected:+,}$owner" ;;
    esac
done <<EOF
$changed
EOF

if [ "$all" -eq 1 ]; then
    result=":all"
else
    # Stable order: sort the selection.
    result="$(printf '%s\n' "$selected" | tr ',' '\n' | sed '/^$/d' | sort | paste -sd, -)"
fi

out="maven_modules=$result"
echo "$out"
if [ "$gh_output" -eq 1 ]; then
    [ -n "${GITHUB_OUTPUT:-}" ] || die "--github-output used but GITHUB_OUTPUT is not set"
    echo "$out" >>"$GITHUB_OUTPUT"
fi
