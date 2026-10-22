#!/bin/sh
set -eu

cd /work

if [ "$#" -ne 1 ]; then
    echo "Usage: sh docker/ci.sh {stage0|unit|bytecode|lean|all|shell}" >&2
    exit 2
fi

case "$1" in
    stage0)
        sh stage0/build.sh
        ;;
    unit|all)
        sh stage0/build.sh
        java -cp out/stage0/classes:out/stage0/test-classes \
            reflect.stage0.test.TestRunner out/stage0/test-classes
        if [ "$1" = all ]; then
            java -jar out/stage0/stage0.jar test tests/bytecode
            sh docker/ci.sh lean
        fi
        ;;
    bytecode)
        sh stage0/build.sh
        java -jar out/stage0/stage0.jar test tests/bytecode
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
    shell)
        exec sh
        ;;
    *)
        echo "Unknown job: $1" >&2
        exit 2
        ;;
esac
