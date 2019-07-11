package org.plumelib.javadoc;

import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.SourcePosition;
import com.sun.tools.doclets.standard.Standard;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.checkerframework.checker.formatter.qual.Format;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.regex.RegexUtil;
import org.checkerframework.common.value.qual.MinLen;

/**
 * A Javadoc doclet that issues an error for any package, class, method, or field that lacks a
 * Javadoc comment.
 */
// This extends the standard doclet so that it takes the standard command-line arguments, which
// makes it easier to use in an existing build or with a build system.
public class RequireJavadoc extends Standard {

  /** All the errors this doclet will report. */
  TreeSet<String> errors = new TreeSet<>();

  /**
   * All packages that have been seen and checked so far. This helps prevent issuing multiple
   * warnings about a given package.
   */
  Set<PackageDoc> packages = new HashSet<>();

  /** If true, print filenames relative to working directory. If false, print absolute paths. */
  static boolean relativePaths = false;

  /** If non-null, matches classes where no problems should be reported. */
  static @MonotonicNonNull Pattern skip = null;

  /** The current working directory, for making relative pathnames. */
  static Path currentPath = Paths.get("").toAbsolutePath();

  private static final @Format({}) String USAGE =
      "Provided by RequireJavadoc doclet:%n"
          + "-skip <classname>      Don't report problems in the given class%n"
          + "-relative              Report relative rather than absolute filenames%n"
          + "See the documentation for more details.%n";

  /** Creates a new RequireJavadoc instance. */
  RequireJavadoc() {}

  /**
   * The entry point to this doclet. Requires documentation of all Java packages, classes, methods,
   * and fields.
   *
   * <p>{@inheritDoc}
   */
  public static boolean start(RootDoc root) {
    RequireJavadoc doclet = new RequireJavadoc();
    for (ClassDoc cd : root.classes()) {
      doclet.processClass(cd);
    }

    for (String error : doclet.errors) {
      System.err.println(error);
    }

    return doclet.errors.isEmpty();
  }

  /**
   * Require documentation of the given class and its package.
   *
   * @param cd the class to require documentation of
   */
  private void processClass(ClassDoc cd) {
    SourcePosition classPosition = cd.position();
    if (skip != null
        && (skip.matcher(cd.name()).find()
            || skip.matcher(cd.qualifiedName()).find()
            || (classPosition != null && skip.matcher(classPosition.file().toString()).find()))) {
      return;
    }
    requireCommentText(cd);
    PackageDoc pd = cd.containingPackage();
    // TODO: This does not work: it does not require documentation for packages.
    if (packages.add(pd)) {
      requireCommentText(pd);
    }
    for (ConstructorDoc constructorDoc : cd.constructors()) {
      // Don't require documentation of synthetic constructors, which don't appear in the source
      // code.
      if (!constructorDoc.isSynthetic() && !isSyntheticForNestedConstructor(constructorDoc)) {
        requireCommentText(constructorDoc);
      }
    }
    for (FieldDoc fd : cd.enumConstants()) {
      requireCommentText(fd);
    }
    for (FieldDoc fd : cd.fields()) {
      // Don't require documentation for `long serialVersionUID`.
      if (!(fd.name().equals("serialVersionUID") && (fd.type().toString().equals("long")))) {
        requireCommentText(fd);
      }
    }
    for (ClassDoc icd : cd.innerClasses()) {
      requireCommentText(icd);
    }
    for (MethodDoc md : cd.methods()) {
      // Don't require documentation of overriding or synthetic methods
      if (!isOverride(md) && !md.isSynthetic() && !isEnumValuesOrValueOf(md)) {
        requireCommentText(md);
      }
    }
  }

  /**
   * Return true if this method is annotated with {@code @Override}.
   *
   * @param md the method to check for an {@code @Override} annotation
   * @return true if this method is annotated with {@code @Override}
   */
  private boolean isOverride(MethodDoc md) {
    for (AnnotationDesc anno : md.annotations()) {
      // TODO: A String comparison is a bit gross.
      if (anno.toString().equals("@java.lang.Override")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Return true if this method is a synthetic default constructor. The <a
   * href="https://docs.oracle.com/javase/8/docs/jdk/api/javadoc/doclet/com/sun/javadoc/MemberDoc.html">isSynthetic</a>
   * documentation says "Returns true if this member was synthesized by the compiler.", but it
   * returns false for a synthesized default constructor for a nested class. This handles that case.
   *
   * @param cd the constructor to check
   * @return true if {@code cd} is a synthetic constructor
   */
  private boolean isSyntheticForNestedConstructor(ConstructorDoc cd) {
    SourcePosition constructorPosition = cd.position();
    if (constructorPosition == null) {
      return false;
    }
    @SuppressWarnings("nullness:assignment.type.incompatible") // constructor has a containing class
    @NonNull ClassDoc containingClass = cd.containingClass();
    SourcePosition classPosition = containingClass.position();
    assert classPosition != null : "@AssumeAssertion(nullness): a class has a position";
    return classPosition.file().equals(constructorPosition.file())
        && classPosition.line() == constructorPosition.line();
  }

  /**
   * Return true if the method is {@code values} or {@code valueOf} for an enum.
   *
   * @param md the MethodDoc to test
   * @return true if the method is {@code values} or {@code valueOf} for an enum
   */
  @SuppressWarnings({
    "index:array.access.unsafe.high.constant", // MethodDoc.parameters() needs @Pure annotation
    "nullness:dereference.of.nullable" // md.containingClass() is non-null for a MethodDoc
    // (MethodDoc needs annotation)
  })
  private boolean isEnumValuesOrValueOf(MethodDoc md) {
    return md.containingClass().isEnum()
        && ((md.name().equals("values") && md.parameters().length == 0)
            || md.name().equals("valueOf")
                && md.parameters().length == 1
                // String comparison is gross, but this is a Javadoc type rather than a
                // reflection type.
                && md.parameters()[0].type().qualifiedTypeName().equals("java.lang.String"));
  }

  /**
   * Require a documentation string for the given Java element. If there is no documentation, this
   * doclet will print a warning message and will return an error status.
   *
   * @param d any Java element
   */
  private void requireCommentText(Doc d) {
    if (skip != null && skip.matcher(d.name()).find()) {
      return;
    }
    String text = d.getRawCommentText();
    if (text.isEmpty()) {
      if (!d.name().equals("")) { // don't warn about the unnamed package
        errors.add(errorString(d));
      }
    }
  }

  /**
   * Return a string stating that documentation is missing on the given construct.
   *
   * @param d a Java language construct (package, class, constructor, method, field)
   * @return an error message for the given construct
   */
  private String errorString(Doc d) {
    SourcePosition position = d.position();
    if (position == null) {
      return "missing documentation for " + d.name();
    }
    File file = position.file();
    // Making the pathname relative shortens it, but can confuse tools.
    Path path = (relativePaths ? currentPath.relativize(file.toPath()) : file.toPath());
    return String.format("%s:%d: missing documentation for %s", path, position.line(), d.name());
  }

  /**
   * Given a command-line option of this doclet, returns the number of arguments you must specify on
   * the command line for the given option. Returns 0 if the argument is not recognized. This method
   * is automatically invoked by Javadoc.
   *
   * @param option the command-line option
   * @return the number of command-line arguments needed when using the option
   * @see <a
   *     href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">Doclet
   *     overview</a>
   */
  public static int optionLength(String option) {
    switch (option) {
      case "-relative":
        return 1;
      case "-skip":
        return 2;
      default:
        return Standard.optionLength(option);
    }
  }

  /**
   * Tests the validity of command-line arguments passed to this doclet. Returns true if the option
   * usage is valid, and false otherwise. This method is automatically invoked by Javadoc.
   *
   * <p>Also sets fields from the command-line arguments.
   *
   * @param options the command-line options to be checked: an array of 1- or 2-element arrays,
   *     where the length depends on {@link #optionLength} applied to the first element
   * @param reporter where to report errors
   * @return true iff the command-line options are valid
   * @see <a
   *     href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">Doclet
   *     overview</a>
   */
  public static boolean validOptions(String[] @MinLen(1) [] options, DocErrorReporter reporter) {
    List<String[]> remaining = new ArrayList<>();
    for (int oi = 0; oi < options.length; oi++) {
      String[] os = options[oi];
      String opt = os[0].toLowerCase();
      switch (opt) {
        case "-relative":
          relativePaths = true;
          break;
        case "-skip":
          assert os.length == 2 : "@AssumeAssertion(value): dependent: optionLength(\"-skip\")==2";
          if (!RegexUtil.isRegex(os[1])) {
            System.err.printf("Error parsing regex %s %s%n", os[1], RegexUtil.regexError(os[1]));
            System.exit(2);
          }
          skip = Pattern.compile(os[1]);
          break;
        case "-help":
          System.out.printf(USAGE);
          return false;
        default:
          remaining.add(os);
          break;
      }
    }
    return Standard.validOptions((String[][]) remaining.toArray(new String[0][]), reporter);
  }
}
