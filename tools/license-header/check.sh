#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Checks (or, with --fix, inserts) the license header in every source file.
# Spotless also enforces the header for Java; this script covers every file type so that the
# whole repository follows one rule.
#
# Usage:
#   tools/license-header/check.sh [--fix] [path ...]
#
# Without paths, every file tracked by git (plus untracked, non-ignored files) is checked.
# A path may be a file or a directory. Exit code 1 means at least one file is missing the
# header (or, with --fix, could not be fixed).

set -euo pipefail

FIX=false
PATHS=()
for arg in "$@"; do
    case "$arg" in
        --fix) FIX=true ;;
        -h | --help)
            sed -n '8,17p' "$0"
            exit 0
            ;;
        *) PATHS+=("$arg") ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
YEAR="$(date +%Y)"

LINE1='Copyright (c) YEAR devlive-community/pipeforge'
LINE3='Licensed under the MIT License. See the LICENSE file in the'
LINE4='project root for full license text.'

# Prints the comment style for a file, or nothing when the file is not checked.
style_of() {
    local file="$1" base
    base="$(basename "$file")"
    case "$file" in
        */node_modules/* | */target/* | */.next/* | */out/* | */dist/* | */generated/*) return ;;
        */src/test/resources/* | */src/it/* | */fixtures/* | */testdata/* | */__fixtures__/*) return ;;
        .claude/* | */.claude/* | .idea/* | */.idea/* | .mvn/wrapper/*) return ;;
    esac
    case "$base" in
        mvnw | mvnw.cmd | pnpm-lock.yaml | package-lock.json | next-env.d.ts) return ;;
        Dockerfile | Dockerfile.*) echo hash; return ;;
    esac
    case "$base" in
        *.java | *.ts | *.tsx | *.js | *.jsx | *.mjs | *.cjs | *.css | *.proto) echo block ;;
        *.sh | *.bash | *.yaml | *.yml | *.properties | *.toml) echo hash ;;
        *.xml | *.xsd) echo xml ;;
        *.sql) echo sql ;;
    esac
}

# Prints the expected header for a style; YEAR is a placeholder.
expected_header() {
    case "$1" in
        block) printf '/*\n * %s\n *\n * %s\n * %s\n */\n' "$LINE1" "$LINE3" "$LINE4" ;;
        hash) printf '#\n# %s\n#\n# %s\n# %s\n#\n' "$LINE1" "$LINE3" "$LINE4" ;;
        xml) printf '<!--\n  %s\n\n  %s\n  %s\n-->\n' "$LINE1" "$LINE3" "$LINE4" ;;
        sql) printf -- '--\n-- %s\n--\n-- %s\n-- %s\n--\n' "$LINE1" "$LINE3" "$LINE4" ;;
    esac
}

# Number of leading lines that must stay above the header (shebang or XML declaration).
preamble_lines() {
    local file="$1" style="$2" first
    first="$(head -n 1 "$file" 2>/dev/null || true)"
    case "$style" in
        hash) [[ "$first" == '#!'* ]] && echo 1 && return ;;
        block) [[ "$first" == '#!'* ]] && echo 1 && return ;;
        xml) [[ "$first" == '<?xml'* ]] && echo 1 && return ;;
    esac
    echo 0
}

has_header() {
    local file="$1" style="$2" skip expected count actual
    skip="$(preamble_lines "$file" "$style")"
    expected="$(expected_header "$style")"
    count="$(printf '%s\n' "$expected" | wc -l | tr -d ' ')"
    actual="$(tail -n +"$((skip + 1))" "$file" | head -n "$count" |
        sed -E 's/Copyright \(c\) [0-9]{4} /Copyright (c) YEAR /')"
    [[ "$actual" == "$expected" ]]
}

add_header() {
    local file="$1" style="$2" skip tmp
    skip="$(preamble_lines "$file" "$style")"
    tmp="$(mktemp)"
    {
        if [[ "$skip" -gt 0 ]]; then head -n "$skip" "$file"; fi
        expected_header "$style" | sed "s/YEAR/$YEAR/"
        tail -n +"$((skip + 1))" "$file"
    } >"$tmp"
    cat "$tmp" >"$file"
    rm -f "$tmp"
}

list_files() {
    if [[ ${#PATHS[@]} -eq 0 ]]; then
        (cd "$ROOT" && git ls-files -co --exclude-standard)
        return
    fi
    local p
    for p in "${PATHS[@]}"; do
        if [[ -d "$p" ]]; then
            find "$p" -type f
        else
            echo "$p"
        fi
    done
}

missing=0
checked=0
while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    if [[ ${#PATHS[@]} -eq 0 ]]; then
        path="$ROOT/$file"
    else
        path="$file"
    fi
    [[ -f "$path" ]] || continue
    style="$(style_of "$file")"
    [[ -z "$style" ]] && continue
    checked=$((checked + 1))
    if has_header "$path" "$style"; then
        continue
    fi
    if $FIX; then
        add_header "$path" "$style"
        echo "fixed: $file"
    else
        echo "missing license header: $file"
        missing=$((missing + 1))
    fi
done < <(list_files)

if [[ "$missing" -gt 0 ]]; then
    echo "$missing of $checked file(s) lack the license header. Run tools/license-header/check.sh --fix" >&2
    exit 1
fi
echo "license headers ok ($checked file(s) checked)"
