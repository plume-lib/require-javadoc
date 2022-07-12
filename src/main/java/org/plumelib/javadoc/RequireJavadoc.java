package org.plumelib.javadoc;

import static com.github.javaparser.utils.PositionUtils.sortByBeginPosition;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.plumelib.options.Option;
import org.plumelib.options.Options;

/**
 * A program that issues an error for any class, constructor, method, or field that lacks a Javadoc
 * comment. Does not issue a warning for methods annotated with {@code @Override}. See documentation
 * at <a
 * href="https://github.com/plume-lib/require-javadoc#readme">https://github.com/plume-lib/require-javadoc#readme</a>.
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
   * "default constructors", though officially that term only refers to ones that the compiler
   * synthesizes when the programmer didn't write one.
   */
  @Option("Don't report problems in constructors with zero formal parameters")
  public boolean dont_require_noarg_constructor;

  /**
   * If true, don't check trivial getters and setters.
   *
   * <p>Trivial getters and setters are of the form:
   *
   * <pre>{@code
   * Foo getFoo() {
   *   return this.foo;
   * }
   *
   * void setFoo(Foo foo) {
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
   * }</pre>
   */
  @Option("Don't report problems in trivial getters and setters")
  public boolean dont_require_trivial_properties;

  /** If true, don't check type declarations: classes, interfaces, enums, annotations. */
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
  List<String> errors = new ArrayList<>();

  /** The Java files to be checked. */
  List<Path> javaFiles = new ArrayList<Path>();

  /** The current working directory, for making relative pathnames. */
  Path workingDirRelative = Paths.get("");

  /** The current working directory, for making relative pathnames. */
  Path workingDirAbsolute = Paths.get("").toAbsolutePath();

  /**
   * The main entry point for the require-javadoc program. See documentation at <a
   * href="https://github.com/plume-lib/require-javadoc#readme">https://github.com/plume-lib/require-javadoc#readme</a>.
   *
   * @param args the command-line arguments; see the README file.
   */
  public static void main(String[] args) {
    RequireJavadoc rj = new RequireJavadoc();
    Options options =
        new Options(
            "java org.plumelib.javadoc.RequireJavadoc [options] [directory-or-file ...]", rj);
    String[] remainingArgs = options.parse(true, args);

    rj.setJavaFiles(remainingArgs);

    for (Path javaFile : rj.javaFiles) {
      if (rj.verbose) {
        System.out.println("Checking " + javaFile);
      }
      try {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        RequireJavadocVisitor visitor = rj.new RequireJavadocVisitor(javaFile);
        visitor.visit(cu, null);
      } catch (IOException e) {
        System.out.println("Problem while reading " + javaFile + ": " + e.getMessage());
        System.exit(2);
      } catch (ParseProblemException e) {
        System.out.println("Problem while parsing " + javaFile + ": " + e.getMessage());
        System.exit(2);
      }
    }
    for (String error : rj.errors) {
      System.out.println(error);
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
  @SuppressWarnings("lock:methodref.receiver") // no locking here
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
  class JavaFilesVisitor extends SimpleFileVisitor<Path> {

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
  boolean shouldNotRequire(String name) {
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
  boolean shouldExclude(String fileName) {
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
  boolean shouldExclude(Path path) {
    return shouldExclude(path.toString());
  }

  /** The type of property method: a getter or setter. */
  enum PropertyType {
    /** A method of the form {@code int getFoo()}. */
    GETTER("get", 0, false),
    /** A method of the form {@code boolean hasFoo()}. */
    GETTER_HAS("has", 0, false),
    /** A method of the form {@code boolean isFoo()}. */
    GETTER_IS("is", 0, false),
    /** A method of the form {@code void setFoo(int arg)}. */
    SETTER("set", 1, true);

    /** The prefix for the method name: "get", "has", "is", or "set". */
    final String prefix;

    /** The number of required formal parameters: 0 or 1. */
    final int requiredParams;

    /** Whether the return type is void. */
    final boolean voidReturn;

    /**
     * Create a new PropertyType.
     *
     * @param prefix the prefix for the method name: "get", "is", or "set"
     * @param requiredParams the number of required formal parameters: 0 or 1
     * @param voidReturn whether the return type is void
     */
    PropertyType(String prefix, int requiredParams, boolean voidReturn) {
      this.prefix = prefix;
      this.requiredParams = requiredParams;
      this.voidReturn = voidReturn;
    }
  }

  /**
   * Return true if this method declaration is a trivial getter or setter.
   *
   * <ul>
   *   <li>A trivial getter is named "getFoo", "hasFoo", or "isFoo", has no formal parameters, and
   *       has a body of the form "return foo" or "return this.foo".
   *   <li>A trivial setter is named "setFoo", has one formal parameter named "foo", and has a body
   *       of the form "this.foo = foo".
   * </ul>
   *
   * @param md the method to check
   * @return true if this method is a trivial getter on setter
   */
  boolean isTrivialGetterOrSetter(MethodDeclaration md) {
    String methodName = md.getNameAsString();
    if (methodName.startsWith("get")
        || methodName.startsWith("has")
        || methodName.startsWith("is")) {
      PropertyType getterType =
          methodName.startsWith("get")
              ? PropertyType.GETTER
              : methodName.startsWith("has") ? PropertyType.GETTER_HAS : PropertyType.GETTER_IS;
      String propertyName = propertyName(md, getterType);
      if (propertyName == null) {
        return false;
      }
      if (getterType == PropertyType.GETTER_HAS || getterType == PropertyType.GETTER_IS) {
        if (!md.getType().toString().equals("boolean")) {
          return false;
        }
      }
      Statement statement = getOnlyStatement(md);
      if (!(statement instanceof ReturnStmt)) {
        return false;
      }
      Optional<Expression> oReturnExpr = ((ReturnStmt) statement).getExpression();
      if (!oReturnExpr.isPresent()) {
        return false;
      }
      Expression returnExpr = oReturnExpr.get();
      String returnName;
      if (returnExpr instanceof NameExpr) {
        returnName = ((NameExpr) returnExpr).getNameAsString();
      } else if (returnExpr instanceof FieldAccessExpr) {
        FieldAccessExpr fa = (FieldAccessExpr) returnExpr;
        Expression receiver = fa.getScope();
        if (!(receiver instanceof ThisExpr)) {
          return false;
        }
        returnName = fa.getNameAsString();
      } else {
        return false;
      }
      if (!returnName.equals(propertyName)) {
        return false;
      }
      return true;
    } else if (methodName.startsWith("set")) {
      String propertyName = propertyName(md, PropertyType.SETTER);
      if (propertyName == null) {
        return false;
      }
      Statement statement = getOnlyStatement(md);
      if (!(statement instanceof ExpressionStmt)) {
        return false;
      }
      Expression expr = ((ExpressionStmt) statement).getExpression();
      if (!(expr instanceof AssignExpr)) {
        return false;
      }
      AssignExpr assignExpr = (AssignExpr) expr;
      Expression target = assignExpr.getTarget();
      AssignExpr.Operator op = assignExpr.getOperator();
      Expression value = assignExpr.getValue();
      if (!(target instanceof FieldAccessExpr)) {
        return false;
      }
      FieldAccessExpr fa = (FieldAccessExpr) target;
      Expression receiver = fa.getScope();
      if (!(receiver instanceof ThisExpr)) {
        return false;
      }
      if (!fa.getNameAsString().equals(propertyName)) {
        return false;
      }
      if (op != AssignExpr.Operator.ASSIGN) {
        return false;
      }
      if (!(value instanceof NameExpr
          && ((NameExpr) value).getNameAsString().equals(propertyName))) {
        return false;
      }
      return true;
    }
    return false;
  }

  /**
   * Returns the lower-cased name of the property, if the method is a getter or setter. Otherwise
   * returns null.
   *
   * @param md the method to test
   * @param propertyType the type of property method
   * @return the lower-cased names of the property, or null
   */
  @Nullable String propertyName(MethodDeclaration md, PropertyType propertyType) {
    String methodName = md.getNameAsString();
    if (!methodName.startsWith(propertyType.prefix)) {
      return null;
    }
    @SuppressWarnings("index") // https://github.com/typetools/checker-framework/issues/5201
    String upperCamelCaseProperty = methodName.substring(propertyType.prefix.length());
    if (upperCamelCaseProperty.length() == 0) {
      return null;
    }
    if (!Character.isUpperCase(upperCamelCaseProperty.charAt(0))) {
      return null;
    }
    String lowerCamelCaseProperty =
        ""
            + Character.toLowerCase(upperCamelCaseProperty.charAt(0))
            + upperCamelCaseProperty.substring(1);
    NodeList<Parameter> parameters = md.getParameters();
    if (parameters.size() != propertyType.requiredParams) {
      return null;
    }
    if (parameters.size() == 1) {
      Parameter parameter = parameters.get(0);
      if (!parameter.getNameAsString().equals(lowerCamelCaseProperty)) {
        return null;
      }
    }
    // Check presence/absence of return type. (The Java compiler will verify that the type is
    // correct, except that "isFoo()" accessors should have boolean return type, which is verified
    // elsewhere.)
    Type returnType = md.getType();
    if (propertyType.voidReturn != returnType.isVoidType()) {
      return null;
    }
    return lowerCamelCaseProperty;
  }

  /**
   * If the body contains exactly one statement, returns it. Otherwise, returns null.
   *
   * @param md a method declaration
   * @return its sole statement, or null
   */
  @Nullable Statement getOnlyStatement(MethodDeclaration md) {
    Optional<BlockStmt> body = md.getBody();
    if (!body.isPresent()) {
      return null;
    }
    NodeList<Statement> statements = body.get().getStatements();
    if (statements.size() != 1) {
      return null;
    }
    return statements.get(0);
  }

  /** Visits an AST and collects warnings about missing Javadoc. */
  private class RequireJavadocVisitor extends VoidVisitorAdapter<Void> {

    /** The file being visited. Used for constructing error messages. */
    Path filename;

    /**
     * Create a new RequireJavadocVisitor.
     *
     * @param filename the file being visited; used for diagnostic messages
     */
    RequireJavadocVisitor(Path filename) {
      this.filename = filename;
    }

    /**
     * Return a string stating that documentation is missing on the given construct.
     *
     * @param node a Java language construct (class, constructor, method, field, etc.)
     * @param simpleName the construct's simple name, used in diagnostic messages
     * @return an error message for the given construct
     */
    private String errorString(Node node, String simpleName) {
      Optional<Range> range = node.getRange();
      if (range.isPresent()) {
        Position begin = range.get().begin;
        Path path =
            (relative
                ? (filename.isAbsolute() ? workingDirAbsolute : workingDirRelative)
                    .relativize(filename)
                : filename);
        return String.format(
            "%s:%d:%d: missing documentation for %s", path, begin.line, begin.column, simpleName);
      } else {
        return "missing documentation for " + simpleName;
      }
    }

    @Override
    public void visit(CompilationUnit cu, Void ignore) {
      Optional<PackageDeclaration> opd = cu.getPackageDeclaration();
      if (opd.isPresent()) {
        String packageName = opd.get().getName().asString();
        if (shouldNotRequire(packageName)) {
          return;
        }
        Optional<String> oTypeName = cu.getPrimaryTypeName();
        if (oTypeName.isPresent()
            && oTypeName.get().equals("package-info")
            && !hasJavadocComment(opd.get())
            && !hasJavadocComment(cu)) {
          errors.add(errorString(opd.get(), packageName));
        }
      }
      if (verbose) {
        System.out.printf("Visiting compilation unit%n");
      }
      super.visit(cu, ignore);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cd, Void ignore) {
      if (dont_require_private && cd.isPrivate()) {
        return;
      }
      String name = cd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting type %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }
      super.visit(cd, ignore);
    }

    @Override
    public void visit(ConstructorDeclaration cd, Void ignore) {
      if (dont_require_private && cd.isPrivate()) {
        return;
      }
      if (dont_require_noarg_constructor && cd.getParameters().size() == 0) {
        return;
      }
      String name = cd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting constructor %s%n", name);
      }
      if (!dont_require_method && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }
      super.visit(cd, ignore);
    }

    @Override
    public void visit(MethodDeclaration md, Void ignore) {
      if (dont_require_private && md.isPrivate()) {
        return;
      }
      if (dont_require_trivial_properties && isTrivialGetterOrSetter(md)) {
        if (verbose) {
          System.out.printf("skipping trivial property method %s%n", md.getNameAsString());
        }
        return;
      }
      String name = md.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting method %s%n", md.getName());
      }
      if (!dont_require_method && !isOverride(md) && !hasJavadocComment(md)) {
        errors.add(errorString(md, name));
      }
      super.visit(md, ignore);
    }

    @Override
    public void visit(FieldDeclaration fd, Void ignore) {
      if (dont_require_private && fd.isPrivate()) {
        return;
      }
      // True if shouldNotRequire is false for at least one of the fields
      boolean shouldRequire = false;
      if (verbose) {
        System.out.printf("Visiting field %s%n", fd.getVariables().get(0).getName());
      }
      boolean hasJavadocComment = hasJavadocComment(fd);
      for (VariableDeclarator vd : fd.getVariables()) {
        String name = vd.getNameAsString();
        // TODO: Also check the type of the serialVersionUID variable.
        if (name.equals("serialVersionUID")) {
          continue;
        }
        if (shouldNotRequire(name)) {
          continue;
        }
        shouldRequire = true;
        if (!dont_require_field && !hasJavadocComment) {
          errors.add(errorString(vd, name));
        }
      }
      if (shouldRequire) {
        super.visit(fd, ignore);
      }
    }

    @Override
    public void visit(EnumDeclaration ed, Void ignore) {
      if (dont_require_private && ed.isPrivate()) {
        return;
      }
      String name = ed.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting enum %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(ed)) {
        errors.add(errorString(ed, name));
      }
      super.visit(ed, ignore);
    }

    @Override
    public void visit(EnumConstantDeclaration ecd, Void ignore) {
      String name = ecd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting enum constant %s%n", name);
      }
      if (!dont_require_field && !hasJavadocComment(ecd)) {
        errors.add(errorString(ecd, name));
      }
      super.visit(ecd, ignore);
    }

    @Override
    public void visit(AnnotationDeclaration ad, Void ignore) {
      if (dont_require_private && ad.isPrivate()) {
        return;
      }
      String name = ad.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting annotation %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(ad)) {
        errors.add(errorString(ad, name));
      }
      super.visit(ad, ignore);
    }

    @Override
    public void visit(AnnotationMemberDeclaration amd, Void ignore) {
      String name = amd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting annotation member %s%n", name);
      }
      if (!dont_require_method && !hasJavadocComment(amd)) {
        errors.add(errorString(amd, name));
      }
      super.visit(amd, ignore);
    }

    /**
     * Return true if this method is annotated with {@code @Override}.
     *
     * @param md the method to check for an {@code @Override} annotation
     * @return true if this method is annotated with {@code @Override}
     */
    private boolean isOverride(MethodDeclaration md) {
      for (AnnotationExpr anno : md.getAnnotations()) {
        String annoName = anno.getName().toString();
        if (annoName.equals("Override") || annoName.equals("java.lang.Override")) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Return true if this node has a Javadoc comment.
   *
   * @param n the node to check for a Javadoc comment
   * @return true if this node has a Javadoc comment
   */
  boolean hasJavadocComment(Node n) {
    if (n instanceof NodeWithJavadoc && ((NodeWithJavadoc<?>) n).hasJavaDocComment()) {
      return true;
    }
    List<Comment> orphans = new ArrayList<>();
    getOrphanCommentsBeforeThisChildNode(n, orphans);
    for (Comment orphan : orphans) {
      if (orphan.isJavadocComment()) {
        return true;
      }
    }
    Optional<Comment> oc = n.getComment();
    if (oc.isPresent()
        && (oc.get().isJavadocComment() || oc.get().getContent().startsWith("/**"))) {
      return true;
    }
    return false;
  }

  /**
   * Get "orphan comments": comments before the comment before this node. For example, in
   *
   * <pre>{@code
   * /** ... *}{@code /
   * // text 1
   * // text 2
   * void m() { ... }
   * }</pre>
   *
   * the Javadoc comment and {@code // text 1} are an orphan comment, and only {@code // text2} is
   * associated with the method.
   *
   * @param node the node whose orphan comments to collect
   * @param result the list to add orphan comments to. Is side-effected by this method. The
   *     implementation uses this to minimize the diffs against upstream.
   */
  @SuppressWarnings({
    "JdkObsolete", // for LinkedList
    "interning:not.interned", // element of a list
    "ReferenceEquality",
  })
  // This implementation is from Randoop's `Minimize.java` file, and before that from JavaParser's
  // PrettyPrintVisitor.printOrphanCommentsBeforeThisChildNode.  The JavaParser maintainers refuse
  // to provide such functionality in JavaParser proper.
  private static void getOrphanCommentsBeforeThisChildNode(final Node node, List<Comment> result) {
    if (node instanceof Comment) {
      return;
    }

    Node parent = node.getParentNode().orElse(null);
    if (parent == null) {
      return;
    }
    List<Node> everything = new LinkedList<>(parent.getChildNodes());
    sortByBeginPosition(everything);
    int positionOfTheChild = -1;
    for (int i = 0; i < everything.size(); i++) {
      if (everything.get(i) == node) positionOfTheChild = i;
    }
    if (positionOfTheChild == -1) {
      throw new AssertionError("I am not a child of my parent.");
    }
    int positionOfPreviousChild = -1;
    for (int i = positionOfTheChild - 1; i >= 0 && positionOfPreviousChild == -1; i--) {
      if (!(everything.get(i) instanceof Comment)) positionOfPreviousChild = i;
    }
    for (int i = positionOfPreviousChild + 1; i < positionOfTheChild; i++) {
      Node nodeToPrint = everything.get(i);
      if (!(nodeToPrint instanceof Comment))
        throw new RuntimeException(
            "Expected comment, instead "
                + nodeToPrint.getClass()
                + ". Position of previous child: "
                + positionOfPreviousChild
                + ", position of child "
                + positionOfTheChild);
      result.add((Comment) nodeToPrint);
    }
  }
}
