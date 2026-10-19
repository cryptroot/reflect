#!/bin/sh
set -eu

cd /work

if [ "$#" -ne 1 ]; then
    echo "Usage: sh docker/ci.sh {stage0|lean}" >&2
    exit 2
fi

case "$1" in
    stage0)
        sh stage0/build.sh
        ;;
    lean)
        expected="leanprover/lean4:v${REFLECT_LEAN_VERSION:?Lean version is not set}"
        actual=$(cat formal/lean-toolchain)
        if [ "$actual" != "$expected" ]; then
            echo "Lean toolchain mismatch: checkout requires $actual; image provides $expected" >&2
            exit 1
        fi
        cd formal
        lake build
        ;;
    *)
        echo "Unknown job: $1" >&2
        exit 2
        ;;
esac
