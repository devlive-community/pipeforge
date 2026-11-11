#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Unit tests for the CI scripts in .ci/. Each case feeds a changed-file list (or a commit
# history) to a script and compares the output with the expected value.
#
# Usage: .ci/tests/run.sh

set -euo pipefail

CI_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$CI_DIR/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

check() { # <name> <expected> <actual>
    if [ "$2" = "$3" ]; then
        passed=$((passed + 1))
        echo "  ok    $1"
    else
        failed=$((failed + 1))
        echo "  FAIL  $1"
        echo "        expected: $2"
        echo "        actual:   $3"
    fi
}

# ------------------------------------------------------------------ maven-modules.sh

make_reactor() {
    local r="$WORK/reactor"
    mkdir -p "$r/pipeforge-api/src/main/java" "$r/pipeforge-engine/engine-api/src" \
        "$r/pipeforge-engine/engine-worker/src" "$r/pipeforge-connectors/connector-fake/src" \
        "$r/tools/build-checks/fixtures/good" "$r/docs" "$r/.mvn"
    cat >"$r/pom.xml" <<'EOF'
<project>
    <modules>
        <module>pipeforge-api</module>
        <module>pipeforge-engine</module>
        <module>pipeforge-connectors</module>
    </modules>
</project>
EOF
    echo '<project/>' >"$r/pipeforge-api/pom.xml"
    cat >"$r/pipeforge-engine/pom.xml" <<'EOF'
<project>
    <modules>
        <module>engine-api</module>
        <module>engine-worker</module>
    </modules>
</project>
EOF
    echo '<project/>' >"$r/pipeforge-engine/engine-api/pom.xml"
    echo '<project/>' >"$r/pipeforge-engine/engine-worker/pom.xml"
    printf '<project><modules><module>connector-fake</module></modules></project>\n' \
        >"$r/pipeforge-connectors/pom.xml"
    echo '<project/>' >"$r/pipeforge-connectors/connector-fake/pom.xml"
    echo '<project/>' >"$r/tools/build-checks/fixtures/good/pom.xml"
    echo "$r"
}

modules_for() { # <reactor> <files...>
    local r="$1"
    shift
    printf '%s\n' "$@" | bash "$CI_DIR/maven-modules.sh" --root "$r"
}

echo "maven-modules.sh:"
R="$(make_reactor)"
check "single module" "maven_modules=pipeforge-api" \
    "$(modules_for "$R" pipeforge-api/src/main/java/A.java)"
check "nested module wins over its parent" "maven_modules=pipeforge-engine/engine-worker" \
    "$(modules_for "$R" pipeforge-engine/engine-worker/src/Task.java)"
check "parent POM selects the aggregator module" "maven_modules=pipeforge-engine" \
    "$(modules_for "$R" pipeforge-engine/pom.xml)"
check "several modules are sorted and de-duplicated" \
    "maven_modules=pipeforge-api,pipeforge-connectors/connector-fake" \
    "$(modules_for "$R" pipeforge-connectors/connector-fake/src/F.java pipeforge-api/pom.xml \
        pipeforge-api/src/main/java/B.java)"
check "root POM selects the full reactor" "maven_modules=:all" \
    "$(modules_for "$R" pipeforge-api/pom.xml pom.xml)"
check "wrapper config selects the full reactor" "maven_modules=:all" \
    "$(modules_for "$R" .mvn/jvm.config)"
check "shared SpotBugs filter selects the full reactor" "maven_modules=:all" \
    "$(modules_for "$R" tools/spotbugs/exclude.xml)"
check "docs-only change selects nothing" "maven_modules=" \
    "$(modules_for "$R" docs/index.mdx README.md)"
check "fixture POM outside the reactor selects nothing" "maven_modules=" \
    "$(modules_for "$R" tools/build-checks/fixtures/good/pom.xml)"
check "deleted file in a module still maps to the module" "maven_modules=pipeforge-api" \
    "$(modules_for "$R" pipeforge-api/src/main/java/Removed.java)"
check "similar prefix does not match another module" "maven_modules=" \
    "$(modules_for "$R" pipeforge-api-extra/src/X.java)"
check "empty change list selects nothing" "maven_modules=" "$(modules_for "$R" "")"

GITHUB_OUTPUT="$WORK/gh-output"
export GITHUB_OUTPUT
printf 'pipeforge-api/x\n' | bash "$CI_DIR/maven-modules.sh" --root "$R" --github-output >/dev/null
check "--github-output appends the result" "maven_modules=pipeforge-api" "$(cat "$GITHUB_OUTPUT")"
unset GITHUB_OUTPUT

# ------------------------------------------------------------------ components.conf

components_for() { # <files...> -> "name=value ..." for the components that are true
    printf '%s\n' "$@" | (cd "$ROOT" && bash .ci/changed-components.sh --files - 2>/dev/null) |
        grep -E '=true$' | grep -v '^any=' | sed 's/=true$//' | paste -sd' ' -
}

echo "components.conf:"
check "Java source runs java only" "java" "$(components_for pipeforge-api/src/main/java/A.java)"
check "root POM runs java and build-checks" "java build-checks" "$(components_for pom.xml)"
check "build-check fixture runs build-checks only" "build-checks" \
    "$(components_for tools/build-checks/fixtures/good/pom.xml)"
check "license header script runs build-checks" "build-checks" \
    "$(components_for tools/license-header/check.sh)"
check "Markdown only runs nothing" "" "$(components_for README.md CONTRIBUTING.zh-CN.md)"
check "CI workflow runs everything" "java build-checks" "$(components_for .github/workflows/ci.yml)"
check "CI scripts run everything" "java build-checks" "$(components_for .ci/maven-modules.sh)"
check "Dependabot config runs nothing" "" "$(components_for .github/dependabot.yml)"

# ------------------------------------------------------------------ check-dco.sh

echo "check-dco.sh:"
G="$WORK/dco"
mkdir -p "$G"
(
    cd "$G"
    git init -q -b main
    git config user.name "Ada Lovelace"
    git config user.email "ada@example.org"
    git commit -q --allow-empty -m "chore: base"
) >/dev/null
base="$(git -C "$G" rev-parse HEAD)"

git -C "$G" commit -q --allow-empty -s -m "feat: signed"
check "signed commits pass" "0" "$(cd "$G" && bash "$CI_DIR/check-dco.sh" "$base" >/dev/null 2>&1; echo $?)"

git -C "$G" commit -q --allow-empty -m "feat: unsigned"
check "unsigned commit fails" "1" "$(cd "$G" && bash "$CI_DIR/check-dco.sh" "$base" >/dev/null 2>&1; echo $?)"
check "failure names the commit" "1" \
    "$(cd "$G" && bash "$CI_DIR/check-dco.sh" "$base" 2>/dev/null | grep -c 'feat: unsigned')"

mid="$(git -C "$G" rev-parse HEAD)"
git -C "$G" commit -q --allow-empty -m "feat: other signer" -m "Signed-off-by: Someone Else <else@example.org>"
check "sign-off by someone other than the author fails" "1" \
    "$(cd "$G" && bash "$CI_DIR/check-dco.sh" "$mid" >/dev/null 2>&1; echo $?)"

# ------------------------------------------------------------------ summary

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
