# Require-Javadoc change log

## 1.0.1 (forthcoming)

Warn about missing package documentation in file package-info.java.

Support new command-line options:
 * --dont-require-private
 * --dont-require-type
 * --dont-require-field
 * --dont-require-method
 * --require-package-info

## 1.0.0

- Release 1.0.0.

## 0.3.1

- Checks enums and annotations, which were ignored before.

## 0.3.0

- require-javadoc is now a regular Java application, not a Javadoc doclet.
  Usage instructions have changed, and several limitations are removed.

## 0.1.3

- Add `-verbose` command-line argument, for debugging.

## 0.1.2

- Reduce dependencies.

## 0.1.1

- Don't use Error Prone, which pulls in checker-qual 2.5.4.

## 0.1.0

- Update minor version number

## 0.0.9

- Don't warn about `values()` or `valueOf()` in enum classes.
