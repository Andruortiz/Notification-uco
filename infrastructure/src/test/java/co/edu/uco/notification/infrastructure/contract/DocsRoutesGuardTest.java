package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DocsRoutesGuardTest {

  private static final Pattern VERSION_ONE_ROUTE =
      Pattern.compile(
          "/(notifications|channels|providers|attachment-uploads|recipients)(/\\{[A-Za-z]+\\})?(:[a-z][A-Za-z]+)"
              + "|/(notifications|attachment-uploads)/\\{[A-Za-z]+\\}"
              + "|/recipients/\\{[A-Za-z]+\\}");

  private static List<String> offenders(final String text) {
    return VERSION_ONE_ROUTE.matcher(text).results().map(match -> match.group()).toList();
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("README.md"))) {
      current = current.getParent();
    }
    return current;
  }

  private static List<Path> documents() throws IOException {
    final Path root = repositoryRoot();
    final java.util.ArrayList<Path> documents = new java.util.ArrayList<>();
    documents.add(root.resolve("README.md"));
    final Path docs = root.resolve("docs");
    if (Files.isDirectory(docs)) {
      try (Stream<Path> walk = Files.walk(docs)) {
        walk.filter(Files::isRegularFile)
            .filter(
                file -> {
                  final String name = file.getFileName().toString();
                  return name.endsWith(".md") || name.endsWith(".html") || name.endsWith(".drawio");
                })
            .forEach(documents::add);
      }
    }
    return documents;
  }

  @Test
  void theDetectorFlagsEveryVersionOneShape() {
    assertEquals(List.of("/notifications:sendBatch"), offenders("POST /notifications:sendBatch"));
    assertFalse(offenders("GET /notifications/{id}").isEmpty());
    assertFalse(offenders("POST /attachment-uploads/{uploadId}:complete").isEmpty());
    assertFalse(offenders("GET /recipients/{recipientId}/preferences").isEmpty());
    assertTrue(offenders("GET /notifications/detail?notificationId=abc").isEmpty());
    assertTrue(offenders("POST /attachment-upload-completions").isEmpty());
  }

  @Test
  void theReadmeAndTheCurrentDocumentsCiteOnlyVersionTwoRoutes() throws IOException {
    for (final Path document : documents()) {
      final String text = Files.readString(document, StandardCharsets.UTF_8);
      assertEquals(List.of(), offenders(text), document.toString());
    }
  }
}
