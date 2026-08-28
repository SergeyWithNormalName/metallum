#!/bin/bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=tools/gi_release_contract_guard.sh
. "$ROOT/tools/gi_release_contract_guard.sh"

expect_accept() {
    metallum_require_release_gi_off "$@" \
        || { echo "expected release GI guard acceptance for: $*" >&2; exit 1; }
}

expect_reject() {
    if metallum_require_release_gi_off "$@" >/dev/null 2>&1; then
        echo "expected release GI guard rejection for: $*" >&2
        exit 1
    fi
}

expect_accept 0 0 0 0 0
expect_accept 0 1 0 0 0
expect_accept 0 0 1 0 0
expect_accept 0 0 0 1 0
expect_accept 0 0 0 0 1
expect_accept 1 0 0 0 0

expect_reject 1 1 0 0 0
expect_reject 1 0 1 0 0
expect_reject 1 0 0 1 0
expect_reject 1 0 0 0 1
expect_reject 1 1 1 1 1
expect_reject 2 0 0 0 0
expect_reject 1 true 0 0 0
expect_reject 1 0 0 0

echo "GI release-contract environment guard passed"
