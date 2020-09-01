# require-javadoc

This program requires that a Javadoc comment be present on
every Java class, constructor, method, and field.
It does not require a Javadoc comment on methods with an `@Override` annotation,
nor on fields named `serialVersionUID`.

This tool makes no requirement about the Javadoc comment, beyond its existence.
For example, this tool does not require the existence
of Javadoc tags such as `@param`, `@return`, etc.
You can use Javadoc itself to enforce such a requirement,
but Javadoc [does not warn](#comparison-to-javadoc--xwerror--xdoclintall)
about completely missing comments.


## Use

Example usage, to check every `.java` file in or under the current directory:

```
java -cp require-javadoc-all.jar org.plumelib.javadoc.RequireJavadoc
```

Details about invoking the program:

```
Usage: java org.plumelib.javadoc.RequireJavadoc [options] [directory-or-file ...]
  --exclude=<regex>      - Don't check files or directories whose pathname matches the regex
  --dont-require=<regex> - Don't report problems in Java elements whose name matches the regex
  --relative=<boolean>   - Report relative rather than absolute filenames [default false]
  --verbose=<boolean>    - Print diagnostic information [default false]
```

If an argument is a directory, each `.java` file in it or its subdirectories will be processed.

With no arguments, `require-javadoc` processes all the `.java` files in the current directory
or any subdirectory.

The `--dont-require` regex is matched against full package names and against simple
(unqualified) names of classes, constructors, methods, and fields.


## Incremental use

In continuous integration job (Azure Pipelines, CircleCI, or Travis CI),
you can require Javadoc on all changed lines and ones
adjacent to them.  Here is example code:

```
if [ -d "/tmp/$USER/plume-scripts" ] ; then
  git -C /tmp/$USER/plume-scripts pull -q 2>&1
else
  mkdir -p /tmp/$USER && git -C /tmp/$USER/ clone --depth 1 -q https://github.com/plume-lib/plume-scripts.git
fi
(./gradlew requireJavadoc > /tmp/warnings.txt 2>&1) || true
/tmp/$USER/plume-scripts/ci-lint-diff /tmp/warnings.txt
```


## Gradle target

To create a `requireJavadoc` target, add the following to `build.gradle`:

```
configurations {
  requireJavadoc
}
dependencies {
  requireJavadoc "org.plumelib:require-javadoc:1.0.0"
}
task requireJavadoc(type: JavaExec) {
  description = 'Ensures that Javadoc documentation exists.'
  main = "org.plumelib.javadoc.RequireJavadoc"
  classpath = configurations.requireJavadoc
  args "src/main/java"
}
check.dependsOn requireJavadoc
```

You can supply other command-line arguments as well; for example:
```
  ...
  args "src/main/java", "--dont-require=WeakHasherMap|WeakIdentityHashMap"
```


## Comparison to `javadoc -Xwerror -Xdoclint:all`

Neither `require-javadoc`,
nor `javadoc -Xwerror -Xdoclint:all`,
nor `javadoc -private -Xwerror -Xdoclint:all`,
is stronger.
Therefore, you may want to use all three.

 * `require-javadoc` requires that a Javadoc comment is present, but does not check the content of the comment.
   For example, `require-javadoc` does not complain if an `@param` or `@return` tag is missing.
   With `-private`, it checks private members too.
   Without `-private`, it ensures that public members don't reference private members.
 * Javadoc warns about problems in existing Javadoc, but does not warn if a method is completely undocumented.
   Javadoc will complain about missing `@param` and `@return` tags, but *not* if `@Override` is present.
   Javadoc does not warn about all Java constructs; for example, it does not process methods within enum constants, nor some private nested classes (even when `-private` is supplied).

If you want to require all Javadoc tags to be present, use the Javadoc tool itself.
From the command line:
```javadoc -private -Xwerror -Xdoclint:all```
(You should run with and without `-private`, and you may wish to adjust the [`-Xdoclint` argument](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/javadoc.html#BEJEFABE).)

In a Gradle buildfile, use one of the following (and a similar version to check only public members):
```
// Turn Javadoc warnings into errors, use strict checking, and process private members.
javadoc {
  options.addStringOption('Xwerror', '-Xdoclint:all')
  options.addStringOption('private', '-quiet')
}
check.dependsOn javadoc
```
or
```
task javadocStrict(type: Javadoc) {
  description = 'Run Javadoc in strict mode: with -Xdoclint:all and -Xwerror, on all members.'
  source = sourceSets.main.allJava
  classpath = sourceSets.main.runtimeClasspath
  options.addStringOption('Xwerror', '-Xdoclint:all')
  options.memberLevel = JavadocMemberLevel.PRIVATE
}
check.dependsOn javadocStrict
```


## Comparison to Checkstyle

Checkstyle is configurable to produce the same warnings as `require-javadoc` does.

It has some issues (as of July 2018):
 * Checkstyle is heavyweight to run and configure:  configuring it requires multiple files.
 * Checkstyle is nondeterministic:  it gives different results for file `A.java` depending on what other files are being checked.
 * Checkstyle crashes on correct code (for example, see https://github.com/checkstyle/checkstyle/issues/5989, which the maintainers closed as "checkstyle does not support it by design").

By contrast to Checkstyle, `require-javadoc` is easier to use, and it actually works.
