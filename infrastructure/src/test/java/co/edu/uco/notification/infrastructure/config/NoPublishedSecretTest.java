package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoPublishedSecretTest {

  private static final Path RESOURCES = Path.of("src/main/resources");
  private static final String LOCAL_PROFILE_FILE = "application-local.yml";

  @Test
  void theDevelopmentSecretOnlyAppearsInTheLocalProfileFile() throws IOException {
    final List<String> offenders =
        filesContaining(RESOURCES, AuthJwtSecretGuard.DEVELOPMENT_SECRET).stream()
            .filter(path -> !path.getFileName().toString().equals(LOCAL_PROFILE_FILE))
            .map(Path::toString)
            .toList();

    assertEquals(List.of(), offenders);
  }

  @Test
  void theLocalProfileFileCarriesTheSameSecretTheGuardRecognizes() throws IOException {
    final List<Path> carriers = filesContaining(RESOURCES, AuthJwtSecretGuard.DEVELOPMENT_SECRET);

    assertTrue(
        carriers.stream()
            .anyMatch(path -> path.getFileName().toString().equals(LOCAL_PROFILE_FILE)));
  }

  @Test
  void theScanDetectsASecretOutsideTheLocalProfileFile(@TempDir final Path directory)
      throws IOException {
    Files.writeString(
        directory.resolve("application.yml"), "secret: " + AuthJwtSecretGuard.DEVELOPMENT_SECRET);

    final List<Path> carriers = filesContaining(directory, AuthJwtSecretGuard.DEVELOPMENT_SECRET);

    assertEquals(1, carriers.size());
  }

  private static List<Path> filesContaining(final Path root, final String needle)
      throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile).filter(path -> contains(path, needle)).toList();
    }
  }

  private static boolean contains(final Path path, final String needle) {
    try {
      return Files.readString(path).contains(needle);
    } catch (final IOException e) {
      return false;
    }
  }
}
