# require-javadoc

A simple Javadoc doclet that requires that a Javadoc comment be present on
every Java element (class, method, and field).

It is important that every Java element be documented, but the programmer can use judgment about how extensive the comment needs to be.
This tool makes no requirement
about the Javadoc comment; for example, it does not require the existence
of any Javadoc tags such as `@param`, `@return`, etc.


## Use

To run this doclet, invoke `javadoc` with the `-doclet` command-line argument:

```
javadoc -doclet org.plumelib.javadoc.RequireJavadoc -docletpath require-javadoc-all.jar ...
```

With the `-private` command-line argument, it checks all Java elements, not just public ones.


## Incremental use

In a Travis job, you can require that some tool issues no errors on the changed lines
(and ones adjacent to them) in a pull request.  Here is example code:

```
(git diff "${TRAVIS_COMMIT_RANGE/.../..}" > /tmp/diff.txt 2>&1) || true
(./gradlew requireJavadocPrivate > /tmp/warnings.txt 2>&1) || true
[ -s /tmp/diff.txt ] || (echo "/tmp/diff.txt is empty" && false)
wget https://raw.githubusercontent.com/plume-lib/plume-scripts/master/lint-diff.py
python lint-diff.py --strip-diff=1 --strip-lint=2 /tmp/diff.txt /tmp/warnings.txt
```


## Gradle target

To create a `requireJavadoc` target, add the following to `build.gradle`:

```
configurations {
  requireJavadoc
}
dependencies {
  implementation group: 'org.plumelib', name: 'require-javadoc', version: '0.0.8'
}
task requireJavadoc(type: Javadoc) {
  description = 'Ensures that Javadoc documentation exists.'
  destinationDir.deleteDir()
  source = sourceSets.main.allJava
  classpath = project.sourceSets.main.compileClasspath
  // options.memberLevel = JavadocMemberLevel.PRIVATE
  options.docletpath = project.sourceSets.main.compileClasspath as List
  options.doclet = "org.plumelib.javadoc.RequireJavadoc"
  // options.addStringOption('skip', 'ClassNotToCheck|OtherClass')
}
```


## Alternatives

If you want to require all Javadoc tags to be present, use the Javadoc tool itself (possibly adjust the [`-Xdoclint` argument](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/javadoc.html#BEJEFABE)):
```javadoc -Xwerror -Xdoclint:all```
This project makes fewer requirements than Javadoc's linting, which requires all tags.
Requiring all tags can be pedantic and tedious; this project produces fewer warnings, and
therefore adopting it is easier for an existing project.

Here is how to enable Javadoc's stricter checking, when you are ready to do so:
```
// Turn Javadoc warnings into errors.
javadoc {
  options.addStringOption('Xwerror', '-Xdoclint:all')
  options.addStringOption('private', '-quiet')
}
```

Another alternative is Checkstyle.  Unlike Javadoc, Checkstyle is configurable to produce
the same warnings as this class.
Checkstyle is heavyweight to run and configure:  configuring it requires multiple files.
As of July 2018, Checkstyle is nondeterministic:  it gives different results for file `A.java` depending on what other files are being checked, and it crashes on correct code (for example, see https://github.com/checkstyle/checkstyle/issues/5989).
By contrast to Checkstyle, 
this project is smaller and actually works.
