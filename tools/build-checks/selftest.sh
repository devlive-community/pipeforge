#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Self-test for the build checks: every fixture under fixtures/ inherits the root POM.
# "good" must build; every "bad-*" fixture must be rejected by the check it violates.
# The license header script is exercised against temporary files as well.
#
# Usage: tools/build-checks/selftest.sh [fixture ...]

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MVNW="$ROOT/mvnw"
LOG_DIR="$(mktemp -d)"
trap 'rm -rf "$LOG_DIR"' EXIT

# fixture|expected outcome|pattern that must appear in the build log
CASES=(
    "good|pass|BUILD SUCCESS"
    "bad-format|fail|Failed to execute goal com\\.diffplug\\.spotless"
    "bad-license-header|fail|Failed to execute goal com\\.diffplug\\.spotless"
    "bad-error-prone|fail|\\[DeadException\\]"
    "bad-spotbugs|fail|Failed to execute goal com\\.github\\.spotbugs"
    "bad-convergence|fail|DependencyConvergence failed"
    "bad-license|fail|Failed to execute goal org\\.codehaus\\.mojo:license-maven-plugin"
)

failures=0

report() {
    if [[ "$2" == ok ]]; then
        echo "  ok    $1"
    else
        echo "  FAIL  $1: $3"
        failures=$((failures + 1))
    fi
}

run_fixture() {
    local name="$1" expect="$2" pattern="$3" log status
    log="$LOG_DIR/$name.log"
    set +e
    "$MVNW" -B -ntp -f "$HERE/fixtures/$name/pom.xml" clean verify >"$log" 2>&1
    status=$?
    set -e
    if [[ "$expect" == pass && "$status" -ne 0 ]]; then
        report "$name" fail "expected the build to pass (log: see below)"
        tail -n 40 "$log"
        return
    fi
    if [[ "$expect" == fail && "$status" -eq 0 ]]; then
        report "$name" fail "expected the build to be rejected"
        return
    fi
    if ! grep -Eq "$pattern" "$log"; then
        report "$name" fail "build log does not mention /$pattern/"
        tail -n 40 "$log"
        return
    fi
    report "$name" ok
    rm -rf "$HERE/fixtures/$name/target"
}

license_header_cases() {
    local dir script
    dir="$(mktemp -d)"
    script="$ROOT/tools/license-header/check.sh"

    printf '#!/usr/bin/env bash\necho hi\n' >"$dir/missing.sh"
    printf '<?xml version="1.0"?>\n<a/>\n' >"$dir/missing.xml"
    printf 'select 1;\n' >"$dir/missing.sql"
    printf 'export const a = 1;\n' >"$dir/missing.ts"
    printf 'key: value\n' >"$dir/missing.yaml"

    if "$script" "$dir" >/dev/null 2>&1; then
        report "license-header detects missing headers" fail "check passed on files without headers"
    else
        report "license-header detects missing headers" ok
    fi

    "$script" --fix "$dir" >/dev/null
    if "$script" "$dir" >/dev/null 2>&1; then
        report "license-header --fix inserts headers" ok
    else
        report "license-header --fix inserts headers" fail "files still fail after --fix"
    fi

    if [[ "$(head -n 1 "$dir/missing.sh")" == '#!/usr/bin/env bash' &&
        "$(head -n 1 "$dir/missing.xml")" == '<?xml version="1.0"?>' ]]; then
        report "license-header keeps shebang and XML declaration first" ok
    else
        report "license-header keeps shebang and XML declaration first" fail "preamble moved"
    fi

    printf '/*\n * Copyright (c) 2019 devlive-community/pipeforge\n *\n * Licensed under the MIT License. See the LICENSE file in the\n * project root for full license text.\n */\n' >"$dir/old-year.ts"
    if "$script" "$dir/old-year.ts" >/dev/null 2>&1; then
        report "license-header accepts the creation year" ok
    else
        report "license-header accepts the creation year" fail "older year rejected"
    fi

    printf '/*\n * Copyright (c) 2026 someone-else\n */\n' >"$dir/wrong.css"
    if "$script" "$dir/wrong.css" >/dev/null 2>&1; then
        report "license-header rejects a different header" fail "foreign header accepted"
    else
        report "license-header rejects a different header" ok
    fi
    rm -rf "$dir"
}

echo "license header script:"
license_header_cases

echo "maven build checks:"
selected=("$@")
for entry in "${CASES[@]}"; do
    IFS='|' read -r name expect pattern <<<"$entry"
    if [[ ${#selected[@]} -gt 0 && ! " ${selected[*]} " == *" $name "* ]]; then
        continue
    fi
    run_fixture "$name" "$expect" "$pattern"
done

if [[ "$failures" -gt 0 ]]; then
    echo "$failures self-test case(s) failed" >&2
    exit 1
fi
echo "all build check self-tests passed"
