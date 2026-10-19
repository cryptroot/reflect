#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
mkdir -p out/stage0
rm -rf out/stage0/classes out/stage0/test-classes
mkdir -p out/stage0/classes out/stage0/test-classes
find stage0/src -type f -name '*.java' | LC_ALL=C sort > out/stage0/sources.list
javac --release 21 -d out/stage0/classes @out/stage0/sources.list
jar --create --file out/stage0/stage0.jar \
    --main-class reflect.stage0.tools.Main -C out/stage0/classes .
find stage0/test -type f -name '*.java' | LC_ALL=C sort > out/stage0/tests.list
javac --release 21 -cp out/stage0/classes -d out/stage0/test-classes @out/stage0/tests.list
