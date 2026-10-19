package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionCookieContractTest {

  private static final String OBSOLETE_CLAIM = "solo acepta bearer";

  private static boolean affirmsBearerOnly(final String description) {
    return description.toLowerCase(java.util.Locale.ROOT).contains(OBSOLETE_CLAIM);
  }

  private static String cookieDescription() {
    return String.valueOf(
        ApiContract.map(ApiContract.components("securitySchemes").get("SessionCookie"))
            .get("description"));
  }

  @Test
  void theSessionCookieDescriptionDescribesPrecedenceActivePepAndEffectiveRole() {
    final String description = cookieDescription();

    assertFalse(affirmsBearerOnly(description.replace('\n', ' ')));
    assertTrue(description.contains("Bearer"));
    assertTrue(description.contains("PEP activo"));
    assertTrue(description.contains("CLIENTE"));
    assertTrue(description.contains("401"));
  }

  @Test
  void theObsoleteClaimIsDetectedWhenItIsPresent() {
    assertTrue(affirmsBearerOnly("el modo interino solo acepta Bearer y todavía no la lee"));
  }

  @Test
  void theGlobalDescriptionNoLongerStatesThatOnlyBearerIsAccepted() {
    final String info =
        String.valueOf(ApiContract.map(ApiContract.document().get("info")).get("description"));

    assertFalse(affirmsBearerOnly(info.replace('\n', ' ')));
    assertTrue(info.contains("NTF-2003"));
  }

  @Test
  void theUnavailableResponseDocumentsTheServiceErrorBody() {
    final Map<String, Object> response =
        ApiContract.map(ApiContract.components("responses").get("PepUnavailable"));
    final Map<String, Object> content = ApiContract.map(response.get("content"));

    assertNotNull(content.get("application/json"));
    assertNotNull(content.get("application/problem+json"));
    assertTrue(String.valueOf(response.get("description")).contains("NTF-2003"));
  }

  @Test
  void theErrorCodeEnumIncludesTheSessionUnavailableCode() {
    final Map<String, Object> schema =
        ApiContract.map(ApiContract.components("schemas").get("ErrorResponse"));
    final Map<String, Object> code =
        ApiContract.map(ApiContract.map(schema.get("properties")).get("code"));
    final List<Object> codes = ApiContract.list(code.get("enum"));

    assertTrue(codes.contains("NTF-2003"));
  }
}
