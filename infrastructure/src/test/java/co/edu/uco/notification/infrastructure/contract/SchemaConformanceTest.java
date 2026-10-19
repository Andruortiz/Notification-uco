package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaConformanceTest {

  private static Map<String, Object> validStatus() {
    final Map<String, Object> body = new HashMap<>();
    body.put("notificationId", "6f1f5d0e-2f56-4f6b-8a58-0a5a3a6c1b11");
    body.put("status", "PENDING");
    body.put("channelType", "EMAIL");
    body.put("providerId", null);
    body.put("lastUpdatedAt", "2026-10-19T10:15:30Z");
    body.put("correlationId", null);
    return body;
  }

  @Test
  void aConformingBodyHasNoViolations() {
    assertEquals(
        List.of(), SchemaConformance.violations(validStatus(), "NotificationStatusResponse"));
  }

  @Test
  void aMissingRequiredFieldIsReported() {
    final Map<String, Object> body = validStatus();
    body.remove("providerId");

    final List<String> violations =
        SchemaConformance.violations(body, "NotificationStatusResponse");

    assertEquals(1, violations.size());
    assertTrue(violations.get(0).contains("providerId"));
  }

  @Test
  void aNullInANonNullableFieldIsReported() {
    final Map<String, Object> body = validStatus();
    body.put("channelType", null);

    assertEquals(1, SchemaConformance.violations(body, "NotificationStatusResponse").size());
  }

  @Test
  void anUnknownEnumValueAndAnUndeclaredFieldAreReported() {
    final Map<String, Object> body = validStatus();
    body.put("status", "UNKNOWN");
    body.put("extra", 1);

    assertEquals(2, SchemaConformance.violations(body, "NotificationStatusResponse").size());
  }

  @Test
  void nestedArraysAreValidatedItemByItem() {
    final Map<String, Object> attempt = new HashMap<>();
    attempt.put("occurredOn", "2026-10-19T10:15:30Z");
    attempt.put("result", "ACCEPTED");
    attempt.put("origin", "AUTOMATIC");
    final Map<String, Object> item = new HashMap<>();
    item.put("notificationId", "6f1f5d0e-2f56-4f6b-8a58-0a5a3a6c1b11");
    item.put("externalId", "e1");
    item.put("recipientId", "r1");
    item.put("channelType", "EMAIL");
    item.put("status", "DELIVERED");
    item.put("acceptedAt", "2026-10-19T10:15:30Z");
    item.put("deliveryAttempts", List.of(attempt));

    final List<String> violations = SchemaConformance.violations(item, "NotificationHistoryItem");

    assertEquals(1, violations.size());
    assertTrue(violations.get(0).contains("deliveryAttempts[0].providerId"));
  }
}
