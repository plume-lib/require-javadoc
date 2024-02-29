package org.plumelib.javadoc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.signedness.qual.Signed;

/**
 * This program takes as input a map (filename &rarr; changed lines) for lines that were modified in
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

  // Implementation approach:
  // For each filename:
  //   Read the file and create a map (line -> line) that maps from a line within a method body to a
  // set of lines that implement the method body.
  //   For each line in the input map, add the appropriate lines to the output map.

  /**
   * Implements the logic of the class; see class Javadoc.
   *
   * @param args command-line arguments: input filename and output filename
   */
  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.printf(
          "LinesInChangedMethods received %d arguments: %s%n", args.length, Arrays.toString(args));
      System.err.printf(
          "LinesInChangedMethods expects two arguments: input filename and output filename.");
      System.exit(1);
    }

    String infileName = args[0];
    String outfileName = args[1];
    if (!new File(infileName).exists()) {
      System.err.printf("File does not exist: %s%n", infileName);
      System.exit(1);
    }

    @NonNull Map<String, Set<Integer>> map;
    try (BufferedReader bufferedReader =
            Files.newBufferedReader(Paths.get(infileName), StandardCharsets.UTF_8);
        JsonReader jsonReader = new JsonReader(bufferedReader)) {
      Type mapType = new TypeToken<Map<String, Set<Integer>>>() {}.getType();
      @SuppressWarnings("nullness") // Gson is not annotated
      @NonNull Map<String, Set<Integer>> son = new Gson().fromJson(jsonReader, mapType);
      map = son;
    } catch (Throwable t) {
      throw new Error("Problem reading " + infileName, t);
    }

    System.out.println(outfileName);
    System.out.println(mapToString(map));
  }

  /**
   * Convert a map to a string.
   *
   * @param map a map
   * @return the string version of the map
   */
  public static String mapToString(Map<String, @Signed ?> map) {
    String mapAsString =
        map.keySet().stream()
            .map(key -> key + "=" + map.get(key))
            .collect(Collectors.joining(", ", "{", "}"));
    return mapAsString;
  }
}
