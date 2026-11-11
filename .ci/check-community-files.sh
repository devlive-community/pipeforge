#!/usr/bin/env bash
#
# Copyright (c) 2026 devlive-community/pipeforge
#
# Licensed under the MIT License. See the LICENSE file in the
# project root for full license text.
#
# Validates the community and governance files:
#   - the required files exist and the English and Chinese contribution guides have the
#     same section structure;
#   - every issue form has name, description and body, unique field ids, and only uses
#     labels defined in .github/labels.yml;
#   - .github/labels.yml has unique names and valid colors; .github/labeler.yml only adds
#     defined labels;
#   - every CODEOWNERS rule is "<pattern> <owner>..." and every owner is listed in
#     MAINTAINERS.md;
#   - the pull request template keeps the mandatory checklist items;
#   - docs-map.yaml entries all have both "paths" and "docs".
#
# Usage: check-community-files.sh [--root <dir>]

set -euo pipefail

root="."
if [ "${1:-}" = "--root" ]; then root="${2:?}"; fi

errors=0
fail() {
    echo "error: $*"
    errors=$((errors + 1))
}

required=(CONTRIBUTING.md CONTRIBUTING.zh-CN.md CODE_OF_CONDUCT.md SECURITY.md GOVERNANCE.md
    MAINTAINERS.md docs-map.yaml .github/CODEOWNERS .github/PULL_REQUEST_TEMPLATE.md
    .github/labels.yml .github/labeler.yml .github/ISSUE_TEMPLATE/config.yml)
for f in "${required[@]}"; do
    [ -f "$root/$f" ] || fail "missing $f"
done
[ "$errors" -eq 0 ] || {
    echo "$errors problem(s) found"
    exit 1
}

# ---------------------------------------------------------------- contribution guides
en_sections="$(grep -c '^## ' "$root/CONTRIBUTING.md" || true)"
zh_sections="$(grep -c '^## ' "$root/CONTRIBUTING.zh-CN.md" || true)"
[ "$en_sections" = "$zh_sections" ] ||
    fail "CONTRIBUTING.md has $en_sections sections but CONTRIBUTING.zh-CN.md has $zh_sections"

# ---------------------------------------------------------------- labels
labels="$(sed -n 's/^- name: *//p' "$root/.github/labels.yml" | sed 's/^"\(.*\)"$/\1/')"
dups="$(printf '%s\n' "$labels" | sort | uniq -d)"
[ -z "$dups" ] || fail ".github/labels.yml defines duplicate labels: $dups"
while IFS= read -r color; do
    printf '%s' "$color" | grep -Eq '^[0-9a-fA-F]{6}$' || fail ".github/labels.yml: invalid color '$color'"
done < <(sed -n 's/^ *color: *//p' "$root/.github/labels.yml" | tr -d "\"'")

is_label() { printf '%s\n' "$labels" | grep -Fxq "$1"; }

while IFS= read -r label; do
    [ -z "$label" ] && continue
    is_label "$label" || fail ".github/labeler.yml uses undefined label '$label'"
done < <(sed -n 's/^\([^ #][^:]*\):[[:space:]]*$/\1/p' "$root/.github/labeler.yml")

# ---------------------------------------------------------------- issue forms
grep -q '^blank_issues_enabled:' "$root/.github/ISSUE_TEMPLATE/config.yml" ||
    fail "ISSUE_TEMPLATE/config.yml must set blank_issues_enabled"
for form in "$root"/.github/ISSUE_TEMPLATE/*.yml; do
    name="$(basename "$form")"
    [ "$name" = config.yml ] && continue
    for key in name description body; do
        grep -q "^$key:" "$form" || fail "$name: missing top-level '$key'"
    done
    ids="$(sed -n 's/^ *id: *//p' "$form")"
    dup_ids="$(printf '%s\n' "$ids" | sed '/^$/d' | sort | uniq -d)"
    [ -z "$dup_ids" ] || fail "$name: duplicate field ids: $dup_ids"
    grep -q 'required: true' "$form" || fail "$name: has no required field"
    form_labels="$(sed -n 's/^labels: *\[\(.*\)\]/\1/p' "$form" | tr ',' '\n' |
        sed 's/^ *"\{0,1\}//; s/"\{0,1\} *$//')"
    while IFS= read -r label; do
        [ -z "$label" ] && continue
        is_label "$label" || fail "$name: undefined label '$label'"
    done <<EOF
$form_labels
EOF
done

# ---------------------------------------------------------------- CODEOWNERS
maintainers="$(grep -oE '@[A-Za-z0-9][A-Za-z0-9-]*' "$root/MAINTAINERS.md" | sort -u)"
line_no=0
while IFS= read -r line || [ -n "$line" ]; do
    line_no=$((line_no + 1))
    line="${line%%#*}"
    [ -z "$(printf '%s' "$line" | tr -d '[:space:]')" ] && continue
    # shellcheck disable=SC2086 # split the rule into pattern and owners, without globbing
    set -f
    set -- $line
    set +f
    if [ $# -lt 2 ]; then
        fail "CODEOWNERS:$line_no: rule without an owner"
        continue
    fi
    shift
    for owner in "$@"; do
        if ! printf '%s' "$owner" | grep -Eq '^@[A-Za-z0-9][A-Za-z0-9-]*(/[A-Za-z0-9._-]+)?$'; then
            fail "CODEOWNERS:$line_no: invalid owner '$owner'"
            continue
        fi
        case "$owner" in
            */*) ;; # team owners are managed on GitHub
            *) printf '%s\n' "$maintainers" | grep -Fxq "$owner" ||
                fail "CODEOWNERS:$line_no: $owner is not listed in MAINTAINERS.md" ;;
        esac
    done
done <"$root/.github/CODEOWNERS"

# ---------------------------------------------------------------- pull request template
for item in 'Conventional Commits' 'Docs-Not-Needed' 'error codes' 'license header'; do
    grep -Fq "$item" "$root/.github/PULL_REQUEST_TEMPLATE.md" ||
        fail "PULL_REQUEST_TEMPLATE.md lost the checklist item about '$item'"
done

# ---------------------------------------------------------------- docs-map.yaml
entries="$(grep -cE '^- +paths:' "$root/docs-map.yaml" || true)"
docs="$(grep -cE '^ +docs:' "$root/docs-map.yaml" || true)"
[ "$entries" = "$docs" ] || fail "docs-map.yaml: $entries entries with paths but $docs with docs"

if [ "$errors" -gt 0 ]; then
    echo "$errors problem(s) found"
    exit 1
fi
echo "community files ok"
