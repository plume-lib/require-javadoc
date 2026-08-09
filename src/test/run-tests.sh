#!/bin/sh

set -e

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"

cd "${SCRIPT_DIR}" || exit 1

(cd ../.. && ./gradlew -q assemble)
sleep .1

# The Gradle task "runShellTests" sets REQUIRE_JAVADOC_JAVA_HOME to the JVM that
# `-PtestJavaVersion` names, so that these tests run under the same JVM as the JUnit tests.
# When the variable is unset, as when a developer runs this script directly, use the PATH's `java`.
if [ -n "${REQUIRE_JAVADOC_JAVA_HOME}" ]; then
  java_cmd="${REQUIRE_JAVADOC_JAVA_HOME}/bin/java"
else
  java_cmd="java"
fi

cmd_base="${java_cmd} \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -cp ${SCRIPT_DIR}/../../build/libs/require-javadoc-all.jar:${SCRIPT_DIR}/../../build/libs/require-javadoc-3.0.0-all.jar \
  org.plumelib.javadoc.RequireJavadoc"

# This may have non-zero status, so add `|| true` at the end when using it.
cmd="${cmd_base} --relative --dont-require-trivial-properties --dont-require-noarg-constructor"

cd tests11
${cmd} > out.txt || true
cd -
diff -u tests11/expected.txt tests11/out.txt

JAVA_VER=$(${java_cmd} -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^1\./s///' | cut -d'.' -f1 | sed 's/-ea//') \
  && if [ "$JAVA_VER" -ge 16 ]; then
    cd tests17
    ${cmd} > out.txt || true
    cd -
    diff -u tests17/expected.txt tests17/out.txt
  fi

# Check that the usage message in README.md is up to date.  RequireJavadoc has no
# `--help` option, so pass an unrecognized option, which makes it print its usage
# message; discard the leading diagnostic about the unrecognized option.
usage_expected="${SCRIPT_DIR}/../../build/usage-expected.txt"
usage_actual="${SCRIPT_DIR}/../../build/usage-actual.txt"
${cmd_base} --zzz-unrecognized-option 2>&1 | sed -n '/^Usage: /,$p' > "${usage_actual}"
# In the next command, each `$` is a sed anchor, not a variable reference.
# shellcheck disable=SC2016
sed -n '/^```output$/,/^```$/p' "${SCRIPT_DIR}/../../README.md" | sed '1d;$d' > "${usage_expected}"
diff -u "${usage_expected}" "${usage_actual}"
