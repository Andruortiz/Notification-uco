package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityDocumentationContractTest {

  private static final String RESPONSE_PREFIX = "#/components/responses/";

  private static Map<String, Object> resolve(final Map<String, Object> response) {
    final Object ref = response.get("$ref");
    if (ref == null) {
      return response;
    }
    return ApiContract.map(
        ApiContract.components("responses")
            .get(String.valueOf(ref).substring(RESPONSE_PREFIX.length())));
  }

  @Test
  void theSessionCookieSchemeIsDeclaredAndAcceptedGlobally() {
    final Map<String, Object> cookie =
        ApiContract.map(ApiContract.components("securitySchemes").get("SessionCookie"));

    assertEquals("apiKey", cookie.get("type"));
    assertEquals("cookie", cookie.get("in"));
    assertEquals("SECURITY_BASELINE_SESSION", cookie.get("name"));
    final List<Object> security = ApiContract.list(ApiContract.document().get("security"));
    assertTrue(security.stream().anyMatch(item -> ApiContract.map(item).containsKey("BearerAuth")));
    assertTrue(
        security.stream().anyMatch(item -> ApiContract.map(item).containsKey("SessionCookie")));
    assertTrue(String.valueOf(cookie.get("description")).contains("Bearer"));
  }

  @Test
  void everyOperationDocuments401403And503WithTheDecisionHeader() {
    assertEquals(17, ApiContract.operations().size());
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      final Map<String, Object> responses =
          ApiContract.map(operation.definition().get("responses"));
      for (final String status : List.of("401", "403", "503")) {
        assertTrue(responses.containsKey(status), operation.key() + " " + status);
        final Map<String, Object> response = resolve(ApiContract.map(responses.get(status)));
        final Map<String, Object> headers = ApiContract.map(response.get("headers"));
        assertNotNull(headers, operation.key() + " " + status);
        assertTrue(headers.containsKey("X-Decision-Id"), operation.key() + " " + status);
        final Map<String, Object> content = ApiContract.map(response.get("content"));
        assertNotNull(content.get("application/problem+json"), operation.key() + " " + status);
      }
    }
  }
}
