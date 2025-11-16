package org.plumelib.javadoc;

import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.JCTree;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import org.plumelib.javacparse.JavacParseResult;
import org.plumelib.options.Option;
import org.plumelib.options.Options;

/**
 * A program that issues an error for any class, constructor, method, or field that lacks a Javadoc
 * comment. Does not issue a warning for methods annotated with {@code @Override}. See documentation
 * at <a
 * href="https://github.com/plume-lib/require-javadoc">https://github.com/plume-lib/require-javadoc</a>.
 */
@SuppressWarnings("PMD.TooManyFields")
public final class RequireJavadoc {

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

  /** Tree.Kind.RECORD, or OTHER if running under Java 15 or earlier. */
  private static final Tree.Kind RECORD;

  static {
    if (Runtime.version().feature() >= 16) {
      try {
        Field recordField = Tree.Kind.class.getDeclaredField("RECORD");
        @SuppressWarnings("nullness:argument") // pass null because it's a static field
        Tree.Kind recordFieldValue = (Tree.Kind) recordField.get(null);
        if (recordFieldValue == null) {
          throw new Error("Field RECORD is nill Tree.Kind");
        }
        RECORD = recordFieldValue;
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new Error("Cannot find field RECORD in Tree.Kind", e);
      }
    } else {
      RECORD = Tree.Kind.OTHER;
    }
  }

  /** All the errors this program will report. */
  private List<String> errors = new ArrayList<>();

  /** The Java files to be checked. */
  private List<Path> javaFiles = new ArrayList<>();

  /** The current working directory, for making relative pathnames. */
  private Path workingDirRelative = Paths.get("");

  /** The current working directory, for making relative pathnames. */
  private Path workingDirAbsolute = Paths.get("").toAbsolutePath();

  /** The current compilation unit. Set in {@link #main}. */
  private JCTree.JCCompilationUnit currentCompilationUnit;

  /** The visitor. Set in {@link #main}. */
  private RequireJavadocVisitor visitor;

  /** Creates a new RequireJavadoc instance. */
  @SuppressWarnings({
    "nullness:initialization.fields.uninitialized",
    "initializedfields:contracts.postcondition"
  }) // `currentCompilationUnit` and `visitor` are set in main(); TODO: refactor.
  private RequireJavadoc() {}

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
        JavacParseResult jpr = JavacParse.parseJavaFile(javaFile.toString());
        JCTree.JCCompilationUnit cu = jpr.getCompilationUnit();
        rj.currentCompilationUnit = cu;
        rj.visitor = rj.new RequireJavadocVisitor(javaFile);
        rj.visitor.visitTopLevel(cu);
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

  /**
   * Set the Java files to be processed from the command-line arguments.
   *
   * @param args the directories and files listed on the command line
   */
  @SuppressWarnings({
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
   * Returns true if the given Java element should not be checked, based on the {@code
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
   * Returns true if the given file or directory should be skipped, based on the {@code --exclude}
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
   * Returns true if the given file or directory should be skipped, based on the {@code --exclude}
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
     * Returns the PropertyKind for the given method, or null if it isn't a property accessor
     * method.
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
   * Returns true if this method declaration is a trivial getter or setter.
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
   * Returns true if this method declaration is a trivial getter or setter of the given kind.
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
      return Character.toLowerCase(upperCamelCaseProperty.charAt(0))
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
    if (statement == null) {
      return false;
    }
    if (propertyKind.isGetter()) {
      if (!(statement instanceof JCTree.JCReturn)) {
        return false;
      }
      JCTree.JCExpression returnExpr = ((JCTree.JCReturn) statement).getExpression();
      if (returnExpr == null) {
        return false;
      }

      // TODO: remove enclosing parentheses.
      if (propertyKind == PropertyKind.GETTER_NOT) {
        if (!(returnExpr instanceof JCTree.JCUnary)) {
          return false;
        }
        JCTree.JCUnary unary = (JCTree.JCUnary) returnExpr;
        if (unary.getTag() != JCTree.Tag.NOT) {
          return false;
        }
        // TODO: remove enclosing parentheses.
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
      // TODO: remove enclosing parentheses.
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
   * @return the name of the identifier, if it is one; null otherwise
   */
  private @Nullable String asFieldName(JCTree.JCExpression expr) {
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
      return fa.getIdentifier().toString();
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
     * The compilation unit being visited. Used for constructing error messages. Set by {@link
     * #visitTopLevel}.
     */
    private JCTree.JCCompilationUnit cu;

    /** The name of the class being visited (and others that contain it). */
    private Deque<String> classNames = new ArrayDeque<>();

    /**
     * Create a new RequireJavadocVisitor.
     *
     * @param filename the file being visited; used for diagnostic messages
     */
    @SuppressWarnings({
      "nullness:initialization.fields.uninitialized",
      "initializedfields:contracts.postcondition"
    }) // `visitTopLevel()` sets `cu`
    public RequireJavadocVisitor(Path filename) {
      this.filename = filename;
    }

    /**
     * Returns a string stating that documentation is missing on the given construct.
     *
     * @param tree a Java language construct (class, constructor, method, field, etc.)
     * @param simpleName the construct's simple name, used in diagnostic messages
     * @return an error message for the given construct
     */
    private String errorString(JCTree tree, String simpleName) {
      int pos = tree.getStartPosition();
      if (pos == Diagnostic.NOPOS) {
        return "missing documentation for " + simpleName;
      }
      Path path =
          (relative
              ? (filename.isAbsolute() ? workingDirAbsolute : workingDirRelative)
                  .relativize(filename)
              : filename);
      LineMap lineMap = cu.getLineMap();
      return String.format(
          "%s:%d:%d: missing documentation for %s",
          path, lineMap.getLineNumber(pos), lineMap.getColumnNumber(pos), simpleName);
    }

    // Default behavior; in superclass, is `Assert.error()`.
    @Override
    public void visitTree(JCTree that) {
      // Don't require anything on an arbitrary JCTree.
    }

    @Override
    public void visitTopLevel(JCTree.JCCompilationUnit cu) {
      this.cu = cu;

      String fileName = cu.getSourceFile().getName();
      if (fileName.endsWith("package-info.java")) {
        // Check for comment at top of file, which is on the `package` declaration in the Javadoc.
        // A "module-info.java" file has no package declaration
        JCTree.JCPackageDecl pd = cu.getPackage();
        if (pd != null) {
          String packageName = pd.getPackageName().toString();
          if (shouldNotRequire(packageName)) {
            return;
          }
          if (!hasJavadocComment(pd)) {
            errors.add(errorString(pd, packageName));
          }
        }
      }

      for (JCTree def : cu.defs) {
        def.accept(this);
      }
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
      classNames.addFirst(name);

      if (!dont_require_type && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }

      if (cd.getKind() == RECORD) {
        // Don't warn about record parameters, because Javadoc requires @param for them in the
        // record declaration itself.
      } else {
        for (JCTree def : cd.defs) {
          def.accept(this);
        }
      }

      classNames.removeFirst();
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl md) {
      if (dont_require_private && md.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
        return;
      }
      if (dont_require_trivial_properties && isTrivialGetterOrSetter(md)) {
        if (verbose) {
          System.out.printf("skipping trivial property method %s%n", md.getName().toString());
        }
        return;
      }

      @NonNull String name = md.getName().toString();
      if (name.equals("<init>")) {
        @SuppressWarnings("nullness:assignment") // the stack is not empty
        @NonNull String tmpName = classNames.peekFirst();
        name = tmpName;
      }

      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting method %s%n", md.getName());
      }
      if (!dont_require_method && !isOverride(md) && !hasJavadocComment(md)) {
        errors.add(errorString(md, name));
      }
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
      if (name.equals("serialVersionUID") && isTypeWithKind(fd.getType(), TypeKind.LONG)) {
        return;
      }
      if (shouldNotRequire(name)) {
        return;
      }
      if (!dont_require_field && !hasJavadocComment(fd)) {
        errors.add(errorString(fd, name));
      }
    }

    /**
     * Returns true if this method is annotated with {@code @Override}.
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
   * Returns true if this tree has a Javadoc comment.
   *
   * @param t the tree to check for a Javadoc comment
   * @return true if this tree has a Javadoc comment
   */
  private boolean hasJavadocComment(JCTree t) {
    DocCommentTable docComments = currentCompilationUnit.docComments;
    return docComments != null && docComments.hasComment(t);
  }
}
