# require-javadoc

This program requires that a Javadoc comment is present on
every Java class, constructor, method, and field.
There are a few exceptions:

* methods with an `@Override` annotation.
* fields named `serialVersionUID`.
* record parameters/fields (because Javadoc
   requires `@param` tags in the Javadoc for the record).
* see command-line arguments below for further customization.

This tool makes no requirement about the Javadoc comment, beyond its existence.
For example, this tool does not require the existence
of Javadoc tags such as `@param`, `@return`, etc.
You can use Javadoc itself to enforce such a requirement,
but Javadoc before JDK 18 [does not warn](#comparison-to-javadoc--xwerror--xdoclintall)
about completely missing comments.  In JDK 18+, Javadoc's warnings about
missing comments are not as customizable as this tool is.

## Use

Example usage, to check every `.java` file in or under the current directory:

```sh
java \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -cp require-javadoc-all.jar \
  org.plumelib.javadoc.RequireJavadoc
```

Details about invoking the program:

```output
Usage: java org.plumelib.javadoc.RequireJavadoc [options] [directory-or-file ...]
  --exclude=<regex>                - Don't check files or directories whose pathname matches the regex
  --dont-require=<regex>           - Don't report problems in Java elements whose name matches the regex
  --dont-require-private=<boolean> - Don't report problems in elements with private access [default: false]
  --dont-require-noarg-constructor=<boolean> - Don't report problems in constructors with zero formal params [default: false]
  --dont-require-trivial-properties=<boolean> - Don't report problems about trivial getters and setters [default: false]
  --dont-require-type=<boolean>    - Don't report problems in type declarations [default: false]
  --dont-require-field=<boolean>   - Don't report problems in fields [default: false]
  --dont-require-method=<boolean>  - Don't report problems in methods and constructors [default: false]
  --require-package-info=<boolean> - Require package-info.java file to exist [default: false]
  --relative=<boolean>             - Report relative rather than absolute filenames [default: false]
  --verbose=<boolean>              - Print diagnostic information [default: false]
```

If an argument is a directory, each `.java` file in it or its subdirectories
will be processed.

With no arguments, `require-javadoc` processes all the `.java` files in the
current directory or any subdirectory.

The `--dont-require` regex is matched against full package names and against simple
(unqualified) names of classes, constructors, methods, and fields.

A constructor with zero arguments is sometimes called a "default constructor",
though "default constructor" actually means a no-argument constructor that the
compiler synthesized when the programmer didn't write any constructor.

All boolean options default to false, and you can omit the `=<boolean>` to set
them to true, for example just `--verbose`.

With `--dont-require-trivial-properties`, no warnings are issued for code of the
following form:

```java
public SomeType getFoo() {
    return foo;
}

public void setFoo(SomeType foo) {
    this.foo = foo;
}

public boolean isBar() {
    return bar;
}

public boolean notBar() {
    return !bar;
}

public boolean hasBaz() {
    return baz;
}
```

## Incremental use

In continuous integration job (Azure Pipelines, CircleCI, GitHub Actions, or
Travis CI), you can require Javadoc on all *changed* lines and lines adjacent to
changed lines.  This is a way to incrementally get your code documented, without
having to document it all at once.  Here are example commands.  (They obtain and
use a program
[`ci-lint-diff`](https://github.com/plume-lib/plume-scripts/blob/master/ci-lint-diff),
which is part of the [plume-scripts
package](https://github.com/plume-lib/plume-scripts).)

```sh
if [ -d "/tmp/$USER/plume-scripts" ] ; then
  git -C /tmp/$USER/plume-scripts pull -q 2>&1
else
  mkdir -p /tmp/$USER && git -C /tmp/$USER/ clone --depth=1 -q https://github.com/plume-lib/plume-scripts.git
fi
(./gradlew requireJavadoc > /tmp/warnings.txt 2>&1) || true
/tmp/$USER/plume-scripts/ci-lint-diff /tmp/warnings.txt
```

## Gradle target

To create a `requireJavadoc` target, add the following to `build.gradle`:

```gradle
configurations {
  requireJavadoc
}
dependencies {
  requireJavadoc("org.plumelib:require-javadoc:2.0.0")
}
def requireJavadoc = tasks.register("requireJavadoc", JavaExec) {
  group = "Documentation"
  description = "Ensures that Javadoc documentation exists."
  inputs.files(sourceSets.main.allJava)
  mainClass = "org.plumelib.javadoc.RequireJavadoc"
  classpath = configurations.requireJavadoc
  args(sourceSets.main.allJava.srcDirs.collect{it.getAbsolutePath()})
  jvmArgs += [
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
  ]
}
check.dependsOn(requireJavadoc)
```

You can supply other command-line arguments as well; if you do so, you must add
`*` before `sourceSets...`.  For example:

```gradle
  ...
  args(*sourceSets.main.allJava.srcDirs.collect{it.getAbsolutePath()},
       "--dont-require=WeakHasherMap|WeakIdentityHashMap")
```

## Comparison to `javadoc -Xwerror -Xdoclint:all`

Before JDK 18,
neither `require-javadoc`,
nor `javadoc -Xwerror -Xdoclint:all`,
nor `javadoc -private -Xwerror -Xdoclint:all`,
is stronger.
After JDK 18, `require-javadoc` is more configurable.
Therefore, you may want to use all three.

* `require-javadoc` requires that a Javadoc comment is present, but does not
   check the content of the comment.  For example, `require-javadoc` does not
   complain if an `@param` or `@return` tag is missing.

* Before JDK 18, Javadoc warns about problems in existing Javadoc, but does not
  warn if a method is completely undocumented.
  With `-private`, it checks private members too.
  Without `-private`, it ensures that public members don't reference private members.
  Javadoc will complain about missing `@param` and `@return` tags, but *not* if
  `@Override` is present.
  Javadoc does not warn about all Java constructs; for example, it does not
  process methods within enum constants, nor some private nested classes (even
  when `-private` is supplied).

* Starting in JDK 18, `javadoc -Xdoclint:all` produces error messages about
  missing Javadoc comments.
  This reduces the need for the `require-javadoc` program.
  The require-javadoc program is still useful for people who:
  * are using JDK 17 or earlier, or
  * desire finer-grained control over which program elements must be documented.
    `-Xdoclint` provides
    [only](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javadoc.html#additional-options-provided-by-the-standard-doclet)
    the key `-missing`, which is very coarse.

   A benefit of `require-javadoc` is that it never requires comments on a
   default constructor, which does not appear in source code, but `javadoc
   -Xdoclint:all` does, reporting "warning: use of default constructor, which
   does not provide a comment".  To avoid such warnings, you can run javadoc
   with `-Xdoclint:all,-missing` and rely on `require-javadoc` to warn about
   missing comments (but not about missing Javadoc tags such as `@param` and
   `@return`).

If you want to require all Javadoc tags to be present (a stronger requirement
than `require-javadoc` enforces), use the Javadoc tool itself.
From the command line:
```javadoc -private -Xwerror -Xdoclint:all```
(You should run with and without `-private`, since they yield different
warnings.  You may wish to adjust the [`-Xdoclint`
argument](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/javadoc.html#BEJEFABE).)

In a Gradle buildfile, use one of the following (and a similar version to check
only public members):

```gradle
// Turn Javadoc warnings into errors, use strict checking, and process private members.
javadoc {
  options.addStringOption("Xwerror", "-Xdoclint:all")
  options.addStringOption("private", "-quiet")
}
check.dependsOn(javadoc)
```

or

```gradle
task javadocStrict(type: Javadoc) {
  group = "Documentation"
  description = "Run Javadoc in strict mode: with -Xdoclint:all and -Xwerror, on all members."
  source = sourceSets.main.allJava
  classpath = sourceSets.main.runtimeClasspath
  options.addStringOption("Xwerror", "-Xdoclint:all")
  options.memberLevel = JavadocMemberLevel.PRIVATE
}
check.dependsOn(javadocStrict)
```

## Comparison to Checkstyle

Checkstyle is configurable to produce the same warnings as `require-javadoc` does.

[//]: # (Comparison is as of July 2018, but I don't think anything has changed since then.)

Checkstyle has some problems:

* Checkstyle is heavyweight to run and configure:  configuring it requires
  multiple files.
* Checkstyle is nondeterministic:  it gives different results for file `A.java`
  depending on what other files are being checked.
* Checkstyle crashes on correct code.  For example, see
  <https://github.com/checkstyle/checkstyle/issues/5989>, which the maintainers
  closed as "checkstyle does not support it by design".

By contrast to Checkstyle, `require-javadoc` is easier to use, and it actually works.
