#!/bin/sh

set -e

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"

cd "${SCRIPT_DIR}" || exit 1

(cd ../.. && ./gradlew -q assemble)
sleep .1

# This may have non-zero status, so add `|| true` at the end when using it.
cmd="java \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -cp ${SCRIPT_DIR}/../../build/libs/require-javadoc-all.jar:${SCRIPT_DIR}/../../build/libs/require-javadoc-2.0.0-all.jar \
  org.plumelib.javadoc.RequireJavadoc \
  --relative --dont-require-trivial-properties --dont-require-noarg-constructor"

cd tests11
${cmd} > out.txt || true
cd -
diff -u tests11/expected.txt tests11/out.txt

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^1\./s///' | cut -d'.' -f1 | sed 's/-ea//') \
  && if [ "$JAVA_VER" -ge 16 ]; then
    cd tests17
    ${cmd} > out.txt || true
    cd -
    diff -u tests17/expected.txt tests17/out.txt
  fi
