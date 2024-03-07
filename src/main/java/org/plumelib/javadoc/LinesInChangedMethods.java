package org.plumelib.javadoc;

import static com.sun.tools.javac.tree.JCTree.JCClassDecl;
import static com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import static com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import static com.sun.tools.javac.util.Log.DiscardDiagnosticHandler;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.plumelib.util.CollectionsPlume;
import org.plumelib.util.FilesPlume;

/**
 * This program takes as input a map (filename &rarr; changed lines) for lines that were changed in
 * an edit. It returns a map (filename &rarr; changed lines) for lines that implement changed
 * methods.
 *
 * <p>The input and output are in JSON files.
 *
 * <p>More specifically, do this for every edited line:
 *
 * <ul>
 *   <li>Was the edited line within a method body? (Changes in method annotations and the signature
 *       do not count. This is a bit of a hack. Fixing a warning may require changing the annotation
 *       of another method. This hack prevents {@code ci-lint-diff.py} from requiring that other
 *       method to full typecheck as well.)
 *   <li>If so, the output contains all the lines that implement changed methods. (This includes
 *       method annotations and the formal parameter list.)
 * </ul>
 */
public class LinesInChangedMethods {

  /** The log. */
  static Log log;

  /** Creates a LinesInChangedMethods. */
  public LinesInChangedMethods() {
    throw new Error("do not instantiate");
  }

  // Implementation approach:
  // For each filename:
  //   Read the file and create a map (line -> line) that maps from a line within a method body to a
  // set of lines that implement the method body.
  //   For each line in the input map, add the appropriate lines to the output map.

  /**
   * Implements the logic of the class; see class Javadoc.
   *
   * @param args command-line arguments: input filename and output filename. Each one is a JSON
   *     file.
   * @throws IOException if there is IO trouble
   */
  public static void main(String[] args)
      // temporary, for debugging
      throws IOException {

    if (args.length != 2) {
      System.err.printf(
          "LinesInChangedMethods expects two arguments: input filename and output filename.%n");
      System.err.printf(
          "LinesInChangedMethods received %d arguments: %s%n", args.length, Arrays.toString(args));
      System.exit(1);
    }

    String infileName = args[0];
    String outfileName = args[1];
    if (!new File(infileName).exists()) {
      System.err.printf("File does not exist: %s%n", infileName);
      System.exit(1);
    }

    // A map from filename to all the lines that are within the method.
    @NonNull Map<String, List<Integer>> changedLines;
    try (BufferedReader bufferedReader =
            Files.newBufferedReader(Paths.get(infileName), StandardCharsets.UTF_8);
        JsonReader jsonReader = new JsonReader(bufferedReader)) {
      Type mapType = new TypeToken<Map<String, List<Integer>>>() {}.getType();
      @SuppressWarnings("nullness") // Gson is not annotated
      @NonNull Map<String, List<Integer>> changedLinesTmp =
          new Gson().fromJson(jsonReader, mapType);
      changedLines = changedLinesTmp;
    } catch (Throwable t) {
      throw new Error("Problem reading " + infileName, t);
    }

    Map<String, List<Integer>> methodLines = new HashMap<>();
    for (Map.Entry<String, List<Integer>> entry : changedLines.entrySet()) {
      String filename = entry.getKey();
      List<Integer> fileChangedLines = entry.getValue();

      if (!new File(filename).exists()) {
        System.err.printf("File %s mentioned in %s does not exist.%n", filename, infileName);
        System.exit(1);
      }
      if (!new File(filename).canRead()) {
        System.err.printf(
            "File %s mentioned in %s exists but cannot be read.%n", filename, infileName);
        System.exit(1);
      }

      if (filename.endsWith(".java")) {
        methodLines.put(filename, changedLinesToMethodLines(filename, fileChangedLines));
      } else {
        methodLines.put(filename, fileChangedLines);
      }
    }

    // For debugging
    System.out.println(outfileName);
    System.out.println(CollectionsPlume.mapToString(changedLines));

    String json = new Gson().toJson(methodLines);

    try (Writer fw = Files.newBufferedWriter(Paths.get(outfileName), UTF_8);
        BufferedWriter writer = new BufferedWriter(fw)) {
      writer.write(json);
    } catch (Throwable t) {
      throw new Error("Problem writing " + outfileName, t);
    }
  }

  /**
   * For each input line that is in a method, put all the method's lines in the output. Otherwise,
   * put the input line in the output directly.
   *
   * @param filename a file
   * @param changedLines a set of lines in the file
   * @return all the lines of all the methods that contain a changed line
   * @throws IOException if there is IO trouble
   */
  static List<Integer> changedLinesToMethodLines(String filename, List<Integer> changedLines)
      // temporary, for debugging
      throws IOException {

    List<Integer> result = (List<Integer>) new ArrayList<Integer>();

    Collections.sort(changedLines);

    JCCompilationUnit cu = parseJavaFile(filename);
    // TODO: I should use a visitor and override visitMethod, which will also find (for example)
    // classes nested within methods.
    for (JCTree def : cu.defs) {
      JavaFileObject jfo = cu.getSourceFile();
      DiscardDiagnosticHandler ddh = new DiscardDiagnosticHandler(null);
      DiagnosticSource ds = new DiagnosticSource(jfo, log);
      if (def.getKind() == Tree.Kind.CLASS) {
        JCClassDecl classDecl = (JCClassDecl) def;
        for (JCTree member : classDecl.getMembers()) {
          if (member.getKind() == Tree.Kind.METHOD) {
            JCMethodDecl methodDecl = (JCMethodDecl) member;
            int startLine = ds.getLineNumber(methodDecl.getStartPosition());
            // TODO: I need this here:  private final SourcePositions sourcePositions;
            // I can get it from a Trees, but how do I get that?

            int endLine = ds.getLineNumber(methodDecl.getEndPosition(cu.endPositions));

            while (changedLines.get(0) < startLine) {
              result.add(changedLines.get(0));
              changedLines.remove(0);
            }
            while (changedLines.get(0) <= endLine) {
              for (int i = startLine; i <= endLine; i++) {
                result.add(i);
              }
            }
            while (changedLines.get(0) <= endLine) {
              changedLines.remove(0);
            }
          }
        }
      }
    }
    for (int i : changedLines) {
      result.add(i);
    }
    return result;
  }

  /**
   * Parse a Java file.
   *
   * @param javaFilename the Java file to parse
   * @return the compilation unit for the file
   * @throws IOException if there is IO trouble
   */
  @SuppressWarnings("mustcall:type.arguments.not.inferred") // context.put()
  static JCCompilationUnit parseJavaFile(String javaFilename)
      // temporary, for debugging
      throws IOException {

    Context context = new Context();

    // TODO: Log has protected access.
    log = new Log(context);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
    context.put(DiagnosticListener.class, diagnostics);

    // These two variables are only used when constructing `fm`.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    @SuppressWarnings("builder:required.method.not.called") // Don't close the standard file manager
    StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
    context.put(JavaFileManager.class, fm);

    Options.instance(context).put("allowStringFolding", "false");
    Options.instance(context).put("--enable-preview", "true");

    /* The contents of the file. */
    String fileContent = FilesPlume.readFile(new File(javaFilename));
    // Cannot just call `new SimpleJavaFileObject()` because it has protected access.
    Path javaFilePath = Paths.get(javaFilename).toAbsolutePath();
    SimpleJavaFileObject source =
        new SimpleJavaFileObject(URI.create("file://" + javaFilePath), JavaFileObject.Kind.SOURCE) {

          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return fileContent;
          }
        };
    Log.instance(context).useSource(source);

    JavacParser parser =
        ParserFactory.instance(context)
            .newParser(
                javaFilename,
                /* keepDocComments= */ true,
                /* keepEndPos= */ true,
                /* keepLineMap= */ true);
    JCCompilationUnit result = parser.parseCompilationUnit();
    result.sourcefile = source;
    return result;
  }
}
