#!/bin/sh

SCRIPTDIR="$(cd "$(dirname "$0")" && pwd -P)"

cd ${SCRIPTDIR}

(cd ../.. && ./gradlew assemble) && sleep .1 && java -cp ../../build/libs/require-javadoc-1.0.9-all.jar org.plumelib.javadoc.RequireJavadoc --relative --dont-require-trivial-properties --dont-require-noarg-constructor > out.txt 

diff -u expected.txt out.txt
