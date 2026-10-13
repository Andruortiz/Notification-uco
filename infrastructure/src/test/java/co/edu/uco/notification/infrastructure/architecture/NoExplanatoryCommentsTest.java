package co.edu.uco.notification.infrastructure.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoExplanatoryCommentsTest {

  private static final List<Path> SOURCE_ROOTS =
      List.of(
          Path.of("..", "core", "src", "main", "java"),
          Path.of("src", "main", "java"),
          Path.of("..", "utils", "src", "main", "java"));

  private static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\s*\\([^)]*\\))?");
  private static final Pattern PUBLIC_API = Pattern.compile("\\b(public|protected)\\b");

  @Test
  void productionSourcesContainNoExplanatoryComments() throws IOException {
    final List<String> violations = new ArrayList<>();
    int scannedFiles = 0;
    for (final Path root : SOURCE_ROOTS) {
      assertTrue(Files.isDirectory(root), "source root not found: " + root.toAbsolutePath());
      final List<Path> files = javaFilesUnder(root);
      scannedFiles += files.size();
      for (final Path file : files) {
        for (final String violation : violationsIn(Files.readString(file))) {
          violations.add(root.relativize(file) + ": " + violation);
        }
      }
    }
    assertTrue(scannedFiles > 0, "no production sources were scanned");
    assertEquals(List.of(), violations);
  }

  @Test
  void theScannerDetectsALineCommentAsAPositiveControl(@TempDir final Path directory)
      throws IOException {
    final Path file = directory.resolve("WithLineComment.java");
    Files.writeString(file, "class WithLineComment {\n  int value = 1; // explains the value\n}\n");

    assertEquals(1, violationsIn(Files.readString(file)).size());
  }

  @Test
  void theScannerDetectsABlockCommentAsAPositiveControl() {
    assertEquals(1, violationsIn("class A {\n  /* why */\n  int value = 1;\n}\n").size());
  }

  @Test
  void theScannerDetectsJavadocOnANonPublicMemberAsAPositiveControl() {
    assertEquals(
        1, violationsIn("class A {\n  /** hidden detail */\n  private int value = 1;\n}\n").size());
  }

  @Test
  void theScannerAcceptsJavadocOnAPublicMember() {
    assertEquals(
        List.of(),
        violationsIn(
            "class A {\n  /** public contract */\n  public int value() {\n    return 1;\n  }\n}\n"));
  }

  @Test
  void theScannerIgnoresCommentMarkersInsideLiteralsAndMultiplication() {
    final String source =
        "class A {\n"
            + "  String url = \"https://example.com/*path*/\";\n"
            + "  char slash = '/';\n"
            + "  String block = \"\"\"\n"
            + "      // not a comment\n"
            + "      \"\"\";\n"
            + "  int product = 2\n"
            + "      * 3;\n"
            + "}\n";

    assertEquals(List.of(), violationsIn(source));
  }

  private static List<Path> javaFilesUnder(final Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(path -> path.toString().endsWith(".java")).toList();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static List<String> violationsIn(final String source) {
    final List<String> violations = new ArrayList<>();
    final int length = source.length();
    int line = 1;
    int i = 0;
    while (i < length) {
      final char c = source.charAt(i);
      if (c == '\n') {
        line++;
        i++;
      } else if (source.startsWith("\"\"\"", i)) {
        final int end = source.indexOf("\"\"\"", i + 3);
        final int close = end < 0 ? length : end + 3;
        line += countNewlines(source, i, close);
        i = close;
      } else if (c == '"' || c == '\'') {
        i = skipQuoted(source, i, c);
      } else if (source.startsWith("//", i)) {
        violations.add("line " + line + ": line comment");
        final int end = source.indexOf('\n', i);
        i = end < 0 ? length : end;
      } else if (source.startsWith("/*", i)) {
        final boolean javadoc = source.startsWith("/**", i) && !source.startsWith("/**/", i);
        final int end = source.indexOf("*/", i + 2);
        final int close = end < 0 ? length : end + 2;
        if (!javadoc) {
          violations.add("line " + line + ": block comment");
        } else if (!documentsPublicApi(source, close)) {
          violations.add("line " + line + ": javadoc on a non-public member");
        }
        line += countNewlines(source, i, close);
        i = close;
      } else {
        i++;
      }
    }
    return violations;
  }

  private static boolean documentsPublicApi(final String source, final int from) {
    int end = from;
    while (end < source.length() && "{;=".indexOf(source.charAt(end)) < 0) {
      end++;
    }
    final String header = ANNOTATION.matcher(source.substring(from, end)).replaceAll(" ");
    return PUBLIC_API.matcher(header).find();
  }

  private static int skipQuoted(final String source, final int start, final char quote) {
    int i = start + 1;
    while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
      i += source.charAt(i) == '\\' ? 2 : 1;
    }
    return Math.min(i + 1, source.length());
  }

  private static int countNewlines(final String source, final int from, final int to) {
    int count = 0;
    for (int i = from; i < to; i++) {
      if (source.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }

  @Test
  void aCleanSourceHasNoViolations() {
    assertFalse(violationsIn("class A {\n  int value = 1;\n}\n").iterator().hasNext());
  }
}
