#!/bin/bash
# Shared fail-closed guard for release-profile benchmark runs.

metallum_require_release_gi_off() {
    if [ "$#" -ne 5 ]; then
        echo "release GI guard requires: release_candidate g2 g3 g4 g5" >&2
        return 2
    fi

    local release_candidate=$1
    local gi_g2=$2
    local gi_g3=$3
    local gi_g4=$4
    local gi_g5=$5
    local value
    for value in "$release_candidate" "$gi_g2" "$gi_g3" "$gi_g4" "$gi_g5"; do
        case "$value" in
            0|1) ;;
            *)
                echo "release GI guard accepts only normalized 0/1 values" >&2
                return 2
                ;;
        esac
    done

    if [ "$release_candidate" -eq 1 ] \
        && { [ "$gi_g2" -ne 0 ] || [ "$gi_g3" -ne 0 ] \
            || [ "$gi_g4" -ne 0 ] || [ "$gi_g5" -ne 0 ]; }; then
        echo "release-contract profile rejects every G2/G3/G4/G5 diagnostic environment" >&2
        return 1
    fi
}
