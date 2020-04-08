package org.plumelib.javadoc;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.plumelib.options.Option;
import org.plumelib.options.Options;

/**
 * A program that issues an error for any class, constructor, method, or field that lacks a Javadoc
 * comment. Does not issue a warning for methods annotated with {@code @Override}.
 */
public class RequireJavadoc {

  /** All the errors this doclet will report. */
  List<String> errors = new ArrayList<>();

  // /**
  //  * All packages that have been seen and checked so far. This helps prevent issuing multiple
  //  * warnings about a given package.
  //  */
  // Set<PackageDoc> packages = new HashSet<>();

  /**
   * If true, print filenames relative to working directory. Setting this only has an effect if the
   * command-line arguments were absolute pathnames, or no command-line arguments were supplied.
   */
  @Option("Report relative rather than absolute filenames")
  public boolean relative = false;

  /** Matches simple name of class/constructor/method/field where no problems should be reported. */
  @Option("Don't report problems in classes that match the given regex")
  public @MonotonicNonNull Pattern skip = null;

  /** If true, output debug information. */
  @Option("Print diagnostic information")
  public boolean verbose = false;

  /** The Java files to be checked. */
  List<Path> javaFiles = new ArrayList<Path>();

  /** The current working directory, for making relative pathnames. */
  Path workingDirRelative = Paths.get("");

  /** The current working directory, for making relative pathnames. */
  Path workingDirAbsolute = Paths.get("").toAbsolutePath();

  /**
   * The main entry point for require-javadoc.
   *
   * @param args the command-line arguments: files or directories. For a directory, each .java file
   *     in it or its subdirectories will be processed.
   */
  public static void main(String[] args) {
    RequireJavadoc rj = new RequireJavadoc();
    Options options = new Options("java RequireJavadoc [directory-or-file ...]", rj);
    String[] remainingArgs = options.parse(true, args);

    rj.setJavaFiles(remainingArgs);

    for (Path javaFile : rj.javaFiles) {
      try {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        RequireJavadocVisitor visitor = rj.new RequireJavadocVisitor(javaFile);
        visitor.visit(cu, null);
      } catch (IOException e) {
        System.out.println("Problem while reading " + javaFile + ": " + e.getMessage());
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
  @SuppressWarnings("lock:methodref.receiver.invalid") // no locking here
  private void setJavaFiles(String[] args) {
    if (args.length == 0) {
      args = new String[] {workingDirAbsolute.toString()};
    }

    for (String arg : args) {
      File f = new File(arg);
      if (!f.exists()) {
        System.out.println("File not found: " + f);
        System.exit(1);
      }
      if (f.isDirectory()) {
        try (Stream<Path> stream =
            Files.find(
                Paths.get(arg),
                999,
                (p, bfa) -> {
                  // System.out.printf("Considering %s %s%n", p, bfa);
                  // System.out.printf(
                  //     "  %s %s%n", bfa.isRegularFile(), p.toString().endsWith(".java"));
                  return bfa.isRegularFile() && p.toString().endsWith(".java");
                })) {
          stream.forEach(p -> javaFiles.add(p));
        } catch (IOException e) {
          System.out.println("Problem while processing " + arg + ": " + e.toString());
          System.exit(1);
        }
      } else {
        javaFiles.add(Paths.get(arg));
      }
    }

    javaFiles.sort(Comparator.comparing(Object::toString));
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
     * Return true if the given element should be skipped.
     *
     * @param simpleName the simple name of a Java element
     * @return true if no warnings should be issued about the element
     */
    boolean shouldSkip(String simpleName) {
      boolean result = skip != null && skip.matcher(simpleName).find();
      if (verbose) {
        System.out.printf("shouldSkip(%s) => %s%n", simpleName, result);
      }
      return result;
    }

    /**
     * Return a string stating that documentation is missing on the given construct.
     *
     * @param node a Java language construct (class, constructor, method, field)
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
    public void visit(ClassOrInterfaceDeclaration cd, Void ignore) {
      super.visit(cd, ignore);
      if (verbose) {
        System.out.printf("Visiting %s%n", cd.getName());
      }
      if (!cd.getJavadocComment().isPresent()) {
        String name = cd.getNameAsString();
        if (!shouldSkip(name)) {
          errors.add(errorString(cd, name));
        }
      }
    }

    @Override
    public void visit(ConstructorDeclaration cd, Void ignore) {
      super.visit(cd, ignore);
      if (verbose) {
        System.out.printf("Visiting %s%n", cd.getName());
      }
      if (!cd.getJavadocComment().isPresent()) {
        String name = cd.getNameAsString();
        if (!shouldSkip(name)) {
          errors.add(errorString(cd, name));
        }
      }
    }

    @Override
    public void visit(MethodDeclaration md, Void ignore) {
      super.visit(md, ignore);
      if (verbose) {
        System.out.printf("Visiting %s%n", md.getName());
      }
      if (!md.getJavadocComment().isPresent()) {
        if (!isOverride(md)) {
          String name = md.getNameAsString();
          if (!shouldSkip(name)) {
            errors.add(errorString(md, name));
          }
        }
      }
    }

    @Override
    public void visit(FieldDeclaration fd, Void ignore) {
      super.visit(fd, ignore);
      if (verbose) {
        System.out.printf("Visiting %s%n", fd.getVariables().get(0).getName());
      }
      if (!fd.getJavadocComment().isPresent()) {
        for (VariableDeclarator vd : fd.getVariables()) {
          String name = vd.getNameAsString();
          if (!shouldSkip(name)) {
            errors.add(errorString(vd, name));
          }
        }
      }
    }

    /**
     * Return true if this method is annotated with {@code @Override}.
     *
     * @param md the method to check for an {@code @Override} annotation
     * @return true if this method is annotated with {@code @Override}
     */
    private boolean isOverride(MethodDeclaration md) {
      for (AnnotationExpr anno : md.getAnnotations()) {
        if (anno.toString().equals("@Override") || anno.toString().equals("@java.lang.Override")) {
          return true;
        }
      }
      return false;
    }
  }
}
