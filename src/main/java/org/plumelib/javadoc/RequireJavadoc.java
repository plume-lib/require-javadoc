package org.plumelib.javadoc;

import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.JCTree;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.plumelib.javacparse.JavacParse;
import org.plumelib.options.Option;
import org.plumelib.options.Options;

/**
 * A program that issues an error for any class, constructor, method, or field that lacks a Javadoc
 * comment. Does not issue a warning for methods annotated with {@code @Override}. See documentation
 * at <a
 * href="https://github.com/plume-lib/require-javadoc">https://github.com/plume-lib/require-javadoc</a>.
 */
public class RequireJavadoc {

  /** Matches name of file or directory where no problems should be reported. */
  @Option("Don't check files or directories whose pathname matches the regex")
  public @MonotonicNonNull Pattern exclude = null;

  // TODO: It would be nice to support matching fully-qualified class names, but matching
  // packages will have to do for now.
  /**
   * Matches simple name of class/constructor/method/field, or full package name, where no problems
   * should be reported.
   */
  @Option("Don't report problems in Java elements whose name matches the regex")
  public @MonotonicNonNull Pattern dont_require = null;

  /** If true, don't check elements with private access. */
  @Option("Don't report problems in elements with private access")
  public boolean dont_require_private;

  /**
   * If true, don't check constructors with zero formal parameters. These are sometimes called
   * "default constructors", though that term means a no-argument constructor that the compiler
   * synthesized when the programmer didn't write any constructor.
   */
  @Option("Don't report problems in constructors with zero formal parameters")
  public boolean dont_require_noarg_constructor;

  /**
   * If true, don't check trivial getters and setters.
   *
   * <p>Trivial getters and setters are of the form:
   *
   * <pre>{@code
   * SomeType getFoo() {
   *   return foo;
   * }
   *
   * SomeType foo() {
   *   return foo;
   * }
   *
   * void setFoo(SomeType foo) {
   *   this.foo = foo;
   * }
   *
   * boolean hasFoo() {
   *   return foo;
   * }
   *
   * boolean isFoo() {
   *   return foo;
   * }
   *
   * boolean notFoo() {
   *   return !foo;
   * }
   * }</pre>
   */
  @Option("Don't report problems in trivial getters and setters")
  public boolean dont_require_trivial_properties;

  /** If true, don't check type declarations: classes, interfaces, enums, annotations, records. */
  @Option("Don't report problems in type declarations")
  public boolean dont_require_type;

  /** If true, don't check fields. */
  @Option("Don't report problems in fields")
  public boolean dont_require_field;

  /** If true, don't check methods, constructors, and annotation members. */
  @Option("Don't report problems in methods and constructors")
  public boolean dont_require_method;

  /** If true, warn if any package lacks a package-info.java file. */
  @Option("Require package-info.java file to exist")
  public boolean require_package_info;

  /**
   * If true, print filenames relative to working directory. Setting this only has an effect if the
   * command-line arguments were absolute pathnames, or no command-line arguments were supplied.
   */
  @Option("Report relative rather than absolute filenames")
  public boolean relative = false;

  /** If true, output debug information. */
  @Option("Print diagnostic information")
  public boolean verbose = false;

  /** All the errors this program will report. */
  private List<String> errors = new ArrayList<>();

  /** The Java files to be checked. */
  private List<Path> javaFiles = new ArrayList<Path>();

  /** The current working directory, for making relative pathnames. */
  private Path workingDirRelative = Paths.get("");

  /** The current working directory, for making relative pathnames. */
  private Path workingDirAbsolute = Paths.get("").toAbsolutePath();

  /** The current compilation unit. */
  private JCTree.JCCompilationUnit currentCompilationUnit;

  /**
   * The main entry point for the require-javadoc program. See documentation at <a
   * href="https://github.com/plume-lib/require-javadoc">https://github.com/plume-lib/require-javadoc</a>.
   *
   * @param args the command-line arguments; see the README.md file
   */
  public static void main(String[] args) {
    RequireJavadoc rj = new RequireJavadoc();
    Options options =
        new Options(
            "java org.plumelib.javadoc.RequireJavadoc [options] [directory-or-file ...]", rj);
    String[] remainingArgs = options.parse(true, args);

    rj.setJavaFiles(remainingArgs);

    List<String> exceptionsThrown = new ArrayList<>();

    for (Path javaFile : rj.javaFiles) {
      if (rj.verbose) {
        System.out.println("Checking " + javaFile);
      }
      try {
        JCTree.JCCompilationUnit cu = JavacParse.parseJavaFile(javaFile.toString());
        rj.currentCompilationUnit = cu;
        RequireJavadocVisitor visitor = rj.new RequireJavadocVisitor(javaFile);
        visitor.visitTopLevel(cu);
      } catch (IOException e) {
        exceptionsThrown.add("Problem while reading " + javaFile + ": " + e.getMessage());
      }
    }
    for (String error : rj.errors) {
      System.out.println(error);
    }

    if (!exceptionsThrown.isEmpty()) {
      for (String exceptionThrown : exceptionsThrown) {
        System.out.println(exceptionThrown);
      }
      System.exit(2);
    }

    System.exit(rj.errors.isEmpty() ? 0 : 1);
  }

  /** Creates a new RequireJavadoc instance. */
  private RequireJavadoc() {}

  /**
   * Set the Java files to be processed from the command-line arguments.
   *
   * @param args the directories and files listed on the command line
   */
  @SuppressWarnings({
    "lock:unneeded.suppression", // TEMPORARY, until a CF release is made
    "lock:methodref.receiver", // Comparator.comparing
    "lock:type.arguments.not.inferred" // Comparator.comparing
  })
  private void setJavaFiles(String[] args) {
    if (args.length == 0) {
      args = new String[] {workingDirAbsolute.toString()};
    }

    FileVisitor<Path> walker = new JavaFilesVisitor();

    for (String arg : args) {
      if (shouldExclude(arg)) {
        continue;
      }
      Path p = Paths.get(arg);
      File f = p.toFile();
      if (!f.exists()) {
        System.out.println("File not found: " + f);
        System.exit(2);
      }
      if (f.isDirectory()) {
        try {
          Files.walkFileTree(p, walker);
        } catch (IOException e) {
          System.out.println("Problem while reading " + f + ": " + e.getMessage());
          System.exit(2);
        }
      } else {
        javaFiles.add(Paths.get(arg));
      }
    }

    javaFiles.sort(Comparator.comparing(Object::toString));

    Set<Path> missingPackageInfoFiles = new LinkedHashSet<>();
    if (require_package_info) {
      for (Path javaFile : javaFiles) {
        @SuppressWarnings("nullness:assignment") // the file is not "/", so getParent() is non-null
        @NonNull Path javaFileParent = javaFile.getParent();
        // Java 11 has Path.of() instead of creating a new File.
        Path packageInfo = javaFileParent.resolve(new File("package-info.java").toPath());
        if (!javaFiles.contains(packageInfo)) {
          missingPackageInfoFiles.add(packageInfo);
        }
      }
      for (Path packageInfo : missingPackageInfoFiles) {
        errors.add("missing package documentation: no file " + packageInfo);
      }
    }
  }

  /** Collects files into the {@link #javaFiles} variable. */
  private class JavaFilesVisitor extends SimpleFileVisitor<Path> {

    /** Create a new JavaFilesVisitor. */
    public JavaFilesVisitor() {}

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
      if (attr.isRegularFile() && file.toString().endsWith(".java")) {
        if (!shouldExclude(file)) {
          javaFiles.add(file);
        }
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) {
      if (shouldExclude(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (exc != null) {
        System.out.println("Problem visiting " + dir + ": " + exc.getMessage());
        System.exit(2);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      if (exc != null) {
        System.out.println("Problem visiting " + file + ": " + exc.getMessage());
        System.exit(2);
      }
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * Return true if the given Java element should not be checked, based on the {@code
   * --dont-require} command-line argument.
   *
   * @param name the name of a Java element. It is a simple name, except for packages.
   * @return true if no warnings should be issued about the element
   */
  private boolean shouldNotRequire(String name) {
    if (dont_require == null) {
      return false;
    }
    boolean result = dont_require.matcher(name).find();
    if (verbose) {
      System.out.printf("shouldNotRequire(%s) => %s%n", name, result);
    }
    return result;
  }

  /**
   * Return true if the given file or directory should be skipped, based on the {@code --exclude}
   * command-line argument.
   *
   * @param fileName the name of a Java file or directory
   * @return true if the file or directory should be skipped
   */
  private boolean shouldExclude(String fileName) {
    if (exclude == null) {
      return false;
    }
    boolean result = exclude.matcher(fileName).find();
    if (verbose) {
      System.out.printf("shouldExclude(%s) => %s%n", fileName, result);
    }
    return result;
  }

  /**
   * Return true if the given file or directory should be skipped, based on the {@code --exclude}
   * command-line argument.
   *
   * @param path a Java file or directory
   * @return true if the file or directory should be skipped
   */
  private boolean shouldExclude(Path path) {
    return shouldExclude(path.toString());
  }

  /** A property method's return type. */
  private enum ReturnType {
    /** The return type is void. */
    VOID,
    /** The return type is boolean. */
    BOOLEAN,
    /** The return type is non-void. (It might be boolean.) */
    NON_VOID;
  }

  /** The type of property method: a getter or setter. */
  private enum PropertyKind {
    /** A method of the form {@code SomeType getFoo()}. */
    GETTER("get", 0, ReturnType.NON_VOID),
    /** A method of the form {@code SomeType foo()}. */
    GETTER_NO_PREFIX("", 0, ReturnType.NON_VOID),
    /** A method of the form {@code boolean hasFoo()}. */
    GETTER_HAS("has", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code boolean isFoo()}. */
    GETTER_IS("is", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code boolean notFoo()}. */
    GETTER_NOT("not", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code void setFoo(SomeType arg)}. */
    SETTER("set", 1, ReturnType.VOID),
    /** Not a getter or setter. */
    NOT_PROPERTY("", -1, ReturnType.VOID);

    /** The prefix for the method name: "get", "", "has", "is", "not", or "set". */
    final String prefix;

    /** The number of required formal parameters: 0 or 1. */
    final int requiredParams;

    /** The return type. */
    final ReturnType returnType;

    /**
     * Create a new PropertyKind.
     *
     * @param prefix the prefix for the method name: "get", "has", "is", "not", or "set"
     * @param requiredParams the number of required formal parameters: 0 or 1
     * @param returnType the return type
     */
    PropertyKind(String prefix, int requiredParams, ReturnType returnType) {
      this.prefix = prefix;
      this.requiredParams = requiredParams;
      this.returnType = returnType;
    }

    /**
     * Returns true if this is a getter.
     *
     * @return true if this is a getter
     */
    boolean isGetter() {
      return this != SETTER;
    }

    /**
     * Return the PropertyKind for the given method, or null if it isn't a property accessor method.
     *
     * @param md the method to check
     * @return the PropertyKind for the given method, or null
     */
    static PropertyKind fromMethodDeclaration(JCTree.JCMethodDecl md) {
      String methodName = md.getName().toString();
      if (methodName.startsWith("get")) {
        return GETTER;
      } else if (methodName.startsWith("has")) {
        return GETTER_HAS;
      } else if (methodName.startsWith("is")) {
        return GETTER_IS;
      } else if (methodName.startsWith("not")) {
        return GETTER_NOT;
      } else if (methodName.startsWith("set")) {
        return SETTER;
      } else {
        return GETTER_NO_PREFIX;
      }
    }
  }

  /**
   * Return true if this method declaration is a trivial getter or setter.
   *
   * <ul>
   *   <li>A trivial getter is named {@code getFoo}, {@code foo}, {@code hasFoo}, {@code isFoo}, or
   *       {@code notFoo}, has no formal parameters, and has a body of the form {@code return foo}
   *       or {@code return this.foo} (except for {@code notFoo}, in which case the body is
   *       negated).
   *   <li>A trivial setter is named {@code setFoo}, has one formal parameter named {@code foo}, and
   *       has a body of the form {@code this.foo = foo}.
   * </ul>
   *
   * @param md the method to check
   * @return true if this method is a trivial getter or setter
   */
  private boolean isTrivialGetterOrSetter(JCTree.JCMethodDecl md) {
    PropertyKind kind = PropertyKind.fromMethodDeclaration(md);
    if (kind != PropertyKind.GETTER_NO_PREFIX) {
      if (isTrivialGetterOrSetter(md, kind)) {
        return true;
      }
    }
    return isTrivialGetterOrSetter(md, PropertyKind.GETTER_NO_PREFIX);
  }

  /**
   * Return true if this method declaration is a trivial getter or setter of the given kind.
   *
   * @see #isTrivialGetterOrSetter(JCTree.JCMethodDecl)
   * @param md the method to check
   * @param propertyKind the kind of property
   * @return true if this method is a trivial getter or setter
   */
  private boolean isTrivialGetterOrSetter(JCTree.JCMethodDecl md, PropertyKind propertyKind) {
    String propertyName = propertyName(md, propertyKind);
    return propertyName != null
        && hasCorrectSignature(md, propertyKind, propertyName)
        && hasCorrectBody(md, propertyKind, propertyName);
  }

  /**
   * Returns the name of the property, if the method is a getter or setter of the given kind.
   * Otherwise returns null.
   *
   * <p>Examines the method's name, but not its signature or body. Also does not check that the
   * given property name corresponds to an existing field.
   *
   * @param md the method to test
   * @param propertyKind the type of property method
   * @return the name of the property, or null
   */
  private @Nullable String propertyName(JCTree.JCMethodDecl md, PropertyKind propertyKind) {
    String methodName = md.getName().toString();
    assert methodName.startsWith(propertyKind.prefix);
    @SuppressWarnings("index") // https://github.com/typetools/checker-framework/issues/5201
    String upperCamelCaseProperty = methodName.substring(propertyKind.prefix.length());
    if (upperCamelCaseProperty.length() == 0) {
      return null;
    }
    if (propertyKind == PropertyKind.GETTER_NO_PREFIX) {
      return upperCamelCaseProperty;
    } else if (!Character.isUpperCase(upperCamelCaseProperty.charAt(0))) {
      return null;
    } else {
      return ""
          + Character.toLowerCase(upperCamelCaseProperty.charAt(0))
          + upperCamelCaseProperty.substring(1);
    }
  }

  /**
   * Returns true if the signature of the given method is a property accessor of the given kind.
   *
   * @param md the method
   * @param propertyKind the kind of property
   * @param propertyName the name of the property
   * @return true if the body of the given method is a property accessor
   */
  private boolean hasCorrectSignature(
      JCTree.JCMethodDecl md, PropertyKind propertyKind, String propertyName) {
    List<JCTree.JCVariableDecl> parameters = md.getParameters();
    if (parameters.size() != propertyKind.requiredParams) {
      return false;
    }
    if (parameters.size() == 1) {
      JCTree.JCVariableDecl parameter = parameters.get(0);
      if (!parameter.getName().toString().equals(propertyName)) {
        return false;
      }
    }
    // Check presence/absence of return type. (The Java compiler will verify
    // that the type is consistent with the method body.)
    JCTree returnType = md.getReturnType();
    switch (propertyKind.returnType) {
      case VOID:
        return isTypeWithKind(returnType, TypeKind.VOID);
      case BOOLEAN:
        return isTypeWithKind(returnType, TypeKind.BOOLEAN);
      case NON_VOID:
        return !isTypeWithKind(returnType, TypeKind.VOID);
      default:
        throw new Error("Unexpected enum value " + propertyKind.returnType);
    }
  }

  /**
   * Returns true if a given tree is a primitive or void type of the given kind.
   *
   * @param tree a tree
   * @param typeKind a primitive or void type
   * @return true if the tree is a type of the given kind
   */
  private boolean isTypeWithKind(JCTree tree, TypeKind typeKind) {
    return tree instanceof JCTree.JCPrimitiveTypeTree
        && typeKind == ((JCTree.JCPrimitiveTypeTree) tree).getPrimitiveTypeKind();
  }

  /**
   * Returns true if the body of the given method is a property accessor of the given kind.
   *
   * @param md the method
   * @param propertyKind the kind of property
   * @param propertyName the name of the property
   * @return true if the body of the given method is a property accessor
   */
  private boolean hasCorrectBody(
      JCTree.JCMethodDecl md, PropertyKind propertyKind, String propertyName) {
    JCTree.JCStatement statement = getOnlyStatement(md);
    if (propertyKind.isGetter()) {
      if (!(statement instanceof JCTree.JCReturn)) {
        return false;
      }
      JCTree.JCExpression returnExpr = ((JCTree.JCReturn) statement).getExpression();
      if (returnExpr == null) {
        return false;
      }

      // Does not handle parentheses.
      if (propertyKind == PropertyKind.GETTER_NOT) {
        if (!(returnExpr instanceof JCTree.JCUnary)) {
          return false;
        }
        JCTree.JCUnary unary = (JCTree.JCUnary) returnExpr;
        if (unary.getTag() != JCTree.Tag.NOT) {
          return false;
        }
        returnExpr = unary.getExpression();
      }
      String returnName = asFieldName(returnExpr);
      return returnName != null && returnName.equals(propertyName);
    } else if (propertyKind == PropertyKind.SETTER) {
      if (!(statement instanceof JCTree.JCExpressionStatement)) {
        return false;
      }
      JCTree.JCExpression expr = ((JCTree.JCExpressionStatement) statement).getExpression();
      if (!(expr instanceof JCTree.JCAssign)) {
        return false;
      }
      JCTree.JCAssign assignExpr = (JCTree.JCAssign) expr;
      JCTree.JCExpression assignLhs = assignExpr.getVariable();
      String lhsName = asFieldName(assignLhs);
      if (lhsName == null || !lhsName.equals(propertyName)) {
        return false;
      }
      JCTree.JCExpression assignRhs = assignExpr.getExpression();
      if (!(assignRhs instanceof JCTree.JCIdent
          && ((JCTree.JCIdent) assignRhs).getName().toString().equals(propertyName))) {
        return false;
      }
      return true;
    } else {
      throw new Error("unexpected PropertyKind " + propertyKind);
    }
  }

  /**
   * If the expression is an identifier or "this.identifier", return the name of the identifier.
   *
   * @param expr an expression
   * @return the name of the identifier, if it is one
   */
  private String asFieldName(JCTree.JCExpression expr) {
    // TODO: handle parentheses.
    if (expr instanceof JCTree.JCIdent) {
      return ((JCTree.JCIdent) expr).getName().toString();
    } else if (expr instanceof JCTree.JCFieldAccess) {
      JCTree.JCFieldAccess fa = (JCTree.JCFieldAccess) expr;
      // Can expr be a field access with null expression and identifier "this"?
      // Or can this case just be omitted?
      JCTree.JCExpression receiver = fa.getExpression();
      if (!(receiver == null
          || (receiver instanceof JCTree.JCIdent
              && ((JCTree.JCIdent) receiver).getName().toString().equals("this")))) {
        return null;
      }
      JCTree.JCExpression field = fa.getExpression();
      if (!(field instanceof JCTree.JCIdent)) {
        return null;
      }
      return ((JCTree.JCIdent) field).getName().toString();
    } else {
      return null;
    }
  }

  /**
   * If the body contains exactly one statement, returns it. Otherwise, returns null.
   *
   * @param md a method declaration
   * @return its sole statement, or null
   */
  private JCTree.@Nullable JCStatement getOnlyStatement(JCTree.JCMethodDecl md) {
    JCTree.JCBlock body = md.getBody();
    if (body == null) {
      return null;
    }
    List<JCTree.JCStatement> statements = body.getStatements();
    if (statements.size() != 1) {
      return null;
    }
    return statements.get(0);
  }

  /** Visits an AST and collects warnings about missing Javadoc. */
  private class RequireJavadocVisitor extends JCTree.Visitor {

    /** The file being visited. Used for constructing error messages. */
    private Path filename;

    /**
     * Create a new RequireJavadocVisitor.
     *
     * @param filename the file being visited; used for diagnostic messages
     */
    public RequireJavadocVisitor(Path filename) {
      this.filename = filename;
    }

    /**
     * Return a string stating that documentation is missing on the given construct.
     *
     * @param node a Java language construct (class, constructor, method, field, etc.)
     * @param simpleName the construct's simple name, used in diagnostic messages
     * @return an error message for the given construct
     */
    private String errorString(JCTree tree, String simpleName) {
      int pos = tree.getPreferredPosition();
      if (pos == Diagnostic.NOPOS) {
        return "missing documentation for " + simpleName;
      }
      Path path =
          (relative
              ? (filename.isAbsolute() ? workingDirAbsolute : workingDirRelative)
                  .relativize(filename)
              : filename);
      // TODO: convert position to line & column.
      return String.format(
          "%s:%d:%d: missing documentation for %s", path, pos.line, pos.column, simpleName);
    }

    @Override
    public void visitTopLevel(JCTree.JCCompilationUnit cu) {
      JCTree.JCPackageDecl pd = cu.getPackage();
      String packageName = null;
      if (pd != null) {
        packageName = pd.getPackageName().toString();
        if (shouldNotRequire(packageName)) {
          return;
        }
      }
      String fileName = cu.getSourceFile().getName();
      if (!(fileName.endsWith("package-info.java") || fileName.endsWith("module-info.java"))) {
        if (!hasJavadocComment(pd) && !hasJavadocComment(cu)) {
          errors.add(errorString(pd, packageName));
        }
      }
      if (verbose) {
        System.out.printf("Visiting compilation unit%n");
      }
      // This will recursively visit all the defs (JCTrees) in the compilation unit.
      super.visitTopLevel(cu);
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl cd) {
      if (dont_require_private && cd.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
        return;
      }
      String name = cd.getSimpleName().toString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting type %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }

      // TODO:
      // Don't warn about record parameters, because Javadoc requires @param for them in the record
      // declaration itself.

      super.visitClassDef(cd);
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl md) {
      // Can this test be improved, or is it good enough?
      String methodName = md.getName().toString();
      String resType = md.getReturnType().toString();
      boolean isConstructor = methodName.equals(resType);

      if (dont_require_private && md.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
        return;
      }
      if (dont_require_trivial_properties && isTrivialGetterOrSetter(md)) {
        if (verbose) {
          System.out.printf("skipping trivial property method %s%n", md.getName().toString());
        }
        return;
      }
      String name = md.getName().toString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting method %s%n", md.getName());
      }
      if (!dont_require_method && !isOverride(md) && !hasJavadocComment(md)) {
        errors.add(errorString(md, name));
      }
      super.visitMethodDef(md);
    }

    @Override
    public void visitVarDef(JCTree.JCVariableDecl fd) {
      if (dont_require_private && fd.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
        return;
      }
      // True if shouldNotRequire is false for at least one of the fields
      String name = fd.getName().toString();
      if (verbose) {
        System.out.printf("Visiting field %s%n", name);
      }
      // TODO: Also check the type of the serialVersionUID variable.
      if (name.equals("serialVersionUID")) {
        return;
      }
      if (shouldNotRequire(name)) {
        return;
      }
      if (!dont_require_field && !hasJavadocComment(fd)) {
        errors.add(errorString(fd, name));
      }
      super.visitVarDef(fd);
    }

    @Override
    public void visitEnumConstantDeclaration(JCTree.JCVariableDecl ecd) {
      String name = ecd.getName().toString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting enum constant %s%n", name);
      }
      if (!dont_require_field && !hasJavadocComment(ecd)) {
        errors.add(errorString(ecd, name));
      }
      super.visitVarDef(ecd);
    }

    @Override
    public void visitAnnotationMemberDeclaration(JCTree.JCVariableDecl amd) {
      String name = amd.getName().toString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting annotation member %s%n", name);
      }
      if (!dont_require_method && !hasJavadocComment(amd)) {
        errors.add(errorString(amd, name));
      }
      super.visitVarDef(amd);
    }

    /**
     * Return true if this method is annotated with {@code @Override}.
     *
     * @param md the method to check for an {@code @Override} annotation
     * @return true if this method is annotated with {@code @Override}
     */
    private boolean isOverride(JCTree.JCMethodDecl md) {
      for (JCTree.JCAnnotation anno : md.getModifiers().getAnnotations()) {
        String annoName = anno.getAnnotationType().toString();
        if (annoName.equals("Override") || annoName.equals("java.lang.Override")) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Return true if this tree has a Javadoc comment.
   *
   * @param n the tree to check for a Javadoc comment
   * @return true if this tree has a Javadoc comment
   */
  private boolean hasJavadocComment(JCTree t) {
    DocCommentTable docComments = currentCompilationUnit.docComments;
    return docComments != null && docComments.hasComment(t);
  }
}
