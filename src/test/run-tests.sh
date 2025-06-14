#!/bin/sh

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"

cd "${SCRIPT_DIR}" || exit 1

(cd ../.. && ./gradlew assemble) && sleep .1 && java -cp ../../build/libs/require-javadoc-1.0.9-all.jar org.plumelib.javadoc.RequireJavadoc --relative --dont-require-trivial-properties --dont-require-noarg-constructor > out.txt 

diff -u expected.txt out.txt
