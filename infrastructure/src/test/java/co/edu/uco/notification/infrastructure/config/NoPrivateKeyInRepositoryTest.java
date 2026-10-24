package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoPrivateKeyInRepositoryTest {

  private static final String MARKER = "BEGIN " + "PRIVATE KEY";
  private static final List<String> SCOPES =
      List.of("core/src", "utils/src", "infrastructure/src", "scripts", ".github", ".env.example");

  @Test
  void noVersionedSourceResourceOrScriptContainsAPrivateKey() throws Exception {
    final Path root = repositoryRoot();
    final List<String> offenders = new ArrayList<>();
    final List<String> files = versionedFiles(root);
    assertTrue(files.size() > 50, "git ls-files returned too few files");
    for (final String file : files) {
      final Path path = root.resolve(file);
      if (Files.isRegularFile(path)
          && new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains(MARKER)) {
        offenders.add(file);
      }
    }

    assertEquals(List.of(), offenders);
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve(".git"))) {
      current = current.getParent();
    }
    assertTrue(current != null, "repository root not found");
    return current;
  }

  private static List<String> versionedFiles(final Path root)
      throws IOException, InterruptedException {
    final List<String> command = new ArrayList<>(List.of("git", "ls-files", "--"));
    command.addAll(SCOPES);
    final Process process = new ProcessBuilder(command).directory(root.toFile()).start();
    final List<String> files =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            .lines()
            .filter(line -> !line.isBlank())
            .toList();
    assertEquals(0, process.waitFor());
    return files;
  }
}
