package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiContractLiteralPathsTest {

  private static List<String> violations(final Collection<String> paths) {
    return paths.stream().filter(path -> !path.matches(ApiContract.PATH_REGEX)).toList();
  }

  @Test
  void validatorRejectsEveryForbiddenShape() {
    final List<String> forbidden =
        List.of(
            "/notifications:sendBatch",
            "/notifications/{id}",
            "/notifications/{id}:retry",
            "/notifications/*",
            "/notifications//batches",
            "/notifications/ batches",
            "/notifications?x=1",
            "/notifications#top",
            "notifications",
            "/notifications/");

    assertEquals(forbidden, violations(forbidden));
  }

  @Test
  void validatorAcceptsLiteralPaths() {
    assertTrue(
        violations(List.of("/notifications", "/notifications/stream/tickets", "/a.b_c~d-e"))
            .isEmpty());
  }

  @Test
  void everyContractPathIsLiteral() {
    final List<String> paths = List.copyOf(ApiContract.paths().keySet());

    assertFalse(paths.isEmpty());
    assertEquals(List.of(), violations(paths));
  }

  @Test
  void contractDeclaresSeventeenOperationsOnThirteenPaths() {
    assertEquals(17, ApiContract.operations().size());
    assertEquals(13, ApiContract.paths().size());
  }
}
