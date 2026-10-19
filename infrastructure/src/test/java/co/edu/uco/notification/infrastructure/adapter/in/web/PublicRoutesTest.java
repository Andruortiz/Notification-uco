package co.edu.uco.notification.infrastructure.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicRoutesTest {

  @Test
  void aLiteralPatternMatchesOnlyThatExactPath() {
    final PublicRoutes routes = new PublicRoutes(List.of("/openapi/api-notificaciones.yaml"));

    assertTrue(routes.matches("/openapi/api-notificaciones.yaml"));
    assertFalse(routes.matches("/openapi"));
    assertFalse(routes.matches("/openapi/otro.yaml"));
    assertFalse(routes.matches("/openapi/api-notificaciones.yaml/extra"));
  }

  @Test
  void aTrailingDoubleStarCoversTheSubtree() {
    final PublicRoutes routes = new PublicRoutes(List.of("/swagger-ui/**"));

    assertTrue(routes.matches("/swagger-ui/index.html"));
    assertTrue(routes.matches("/swagger-ui/a/b.css"));
    assertFalse(routes.matches("/swagger-uiX"));
  }

  @Test
  void actuatorEntriesAreLeftToTheManagementContext() {
    final PublicRoutes routes =
        new PublicRoutes(List.of("/actuator/health", "/actuator", "/v3/api-docs"));

    assertFalse(routes.matches("/actuator/health"));
    assertFalse(routes.matches("/actuator"));
    assertTrue(routes.matches("/v3/api-docs"));
  }

  @Test
  void blankEntriesAreIgnoredAndAnEmptyListExemptsNothing() {
    final PublicRoutes routes = new PublicRoutes(List.of(" ", ""));

    assertFalse(routes.matches("/v3/api-docs"));
    assertFalse(routes.matches("/"));
  }

  @Test
  void entriesAreTrimmed() {
    assertTrue(new PublicRoutes(List.of(" /v3/api-docs ")).matches("/v3/api-docs"));
  }

  @Test
  void theDocumentationDefaultsCoverTheContractAndTheInterface() {
    final PublicRoutes routes = PublicRoutes.documentation();

    assertTrue(routes.matches("/openapi/api-notificaciones.yaml"));
    assertTrue(routes.matches("/v3/api-docs"));
    assertTrue(routes.matches("/v3/api-docs/swagger-config"));
    assertTrue(routes.matches("/swagger-ui.html"));
    assertTrue(routes.matches("/swagger-ui/index.html"));
    assertFalse(routes.matches("/notifications"));
  }
}
