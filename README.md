# require-javadoc

This Javadoc doclet requires that a Javadoc comment be present on
every Java element (class, method, and field).

This tool makes no requirement about the Javadoc comment, beyond its existence.
For example, this tool does not require the existence
of Javadoc tags such as `@param`, `@return`, etc.
You can use Javadoc itself to enforce such a requirement
(but Javadoc does not warn about completely missing comments).


## Use

To run this doclet, invoke `javadoc` with the `-doclet` command-line argument:

```
javadoc -doclet org.plumelib.javadoc.RequireJavadoc -docletpath require-javadoc-all.jar ...
```

With the `-private` command-line argument, it checks all Java elements, not just public ones.
(It never checks local classes (such as within a method body), however.


## Incremental use

In a continuous integration job (Azure Pipelines, CircleCI, or Travis CI)
for a pull request, you can require Javadoc on all changed lines and ones
adjacent to them.  Here is example code:

```
git -C /tmp/plume-scripts pull > /dev/null 2>&1 \
  || git -C /tmp clone --depth 1 -q https://github.com/plume-lib/plume-scripts.git
(./gradlew requireJavadocPrivate > /tmp/warnings.txt 2>&1) || true
/tmp/plume-scripts/ci-lint-diff /tmp/warnings.txt
```


## Gradle target

There are two ways to use require-javadoc.
1. You can run it every time you run Javadoc.
2. You can create a separate target that runs it.

### Running with every `javadoc` invocation

Customize the `javadoc` task:

```
dependencies {
  compileOnly group: 'org.plumelib', name: 'require-javadoc', version: '0.1.2'
}
javadoc {
  // options.memberLevel = JavadocMemberLevel.PRIVATE
  options.docletpath = project.sourceSets.main.compileClasspath as List
  options.doclet = "org.plumelib.javadoc.RequireJavadoc"
  // options.addStringOption('skip', 'ClassNotToCheck|OtherClass')
}
```

### Separate target `requireJavadoc`

To create a `requireJavadoc` target that is unrelated to the existing
`javadoc` target, add the following to `build.gradle`:

```
dependencies {
  compileOnly group: 'org.plumelib', name: 'require-javadoc', version: '0.1.2'
}
task requireJavadoc(type: Javadoc) {
  description = 'Ensures that Javadoc documentation exists.'
  destinationDir temporaryDir
  source = sourceSets.main.allJava
  classpath = project.sourceSets.main.compileClasspath
  options.docletpath = project.sourceSets.main.compileClasspath as List
  options.doclet = "org.plumelib.javadoc.RequireJavadoc"
  // options.memberLevel = JavadocMemberLevel.PRIVATE
  // options.addStringOption('skip', 'ClassNotToCheck|OtherClass')
}
```


## Comparison to `javadoc -Xwerror -Xdoclint:all`

Neither `require-javadoc`, nor `javadoc -private -Xwerror -Xdoclint:all`, is stronger.
 * `require-javadoc` requires a Javadoc comment is present, but does not check the content of the comment.
   For example, `require-javadoc` does not complain if an `@param` or `@return` tag is missing.
 * Javadoc warns about problems in existing Javadoc, but does not warn if a method is completely undocumented.
Therefore, you may want to use both.

If you want to require all Javadoc tags to be present, use the Javadoc tool itself.
From the command line:
```javadoc -private -Xwerror -Xdoclint:all```
(You might want to omit `-private` or adjust the [`-Xdoclint` argument](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/javadoc.html#BEJEFABE).)
In a Gradle buildfile:
```
// Turn Javadoc warnings into errors.
javadoc {
  options.addStringOption('Xwerror', '-Xdoclint:all')
  options.addStringOption('private', '-quiet')
}
```


## Comparison to Checkstyle

Checkstyle is configurable to produce the same warnings as `require-javadoc` does.

It has some issues (as of July 2018):
 * Checkstyle is heavyweight to run and configure:  configuring it requires multiple files.
 * Checkstyle is nondeterministic:  it gives different results for file `A.java` depending on what other files are being checked.
 * Checkstyle crashes on correct code (for example, see https://github.com/checkstyle/checkstyle/issues/5989, which the maintainers closed as "checkstyle does not support it by design").

By contrast to Checkstyle, `require-javadoc` is smaller and actually works.
