package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangelogContractTest {

  private static final Map<String, String> V1_TO_V2 = new LinkedHashMap<>();

  static {
    V1_TO_V2.put("POST /notifications:sendBatch", "POST /notifications/batches");
    V1_TO_V2.put("GET /notifications/{id}", "GET /notifications/detail");
    V1_TO_V2.put("POST /notifications/{id}:retry", "POST /notification-retries");
    V1_TO_V2.put("GET /notifications:subscribe", "GET /notifications/stream");
    V1_TO_V2.put("POST /notifications:subscribeTicket", "POST /notifications/stream/tickets");
    V1_TO_V2.put(
        "POST /attachment-uploads/{uploadId}:complete", "POST /attachment-upload-completions");
    V1_TO_V2.put("GET /attachment-uploads/{uploadId}", "GET /attachment-uploads/status");
    V1_TO_V2.put("POST /channels:register", "POST /channels");
    V1_TO_V2.put("POST /providers:register", "POST /providers");
    V1_TO_V2.put("GET /recipients/{recipientId}/preferences", "GET /recipients/preferences");
    V1_TO_V2.put("POST /recipients/{recipientId}:updatePreferences", "PUT /recipients/preferences");
  }

  private static String description() {
    return String.valueOf(ApiContract.info().get("description"));
  }

  @Test
  void theContractVersionIsTwoPointZero() {
    assertEquals("2.0.0", ApiContract.info().get("version"));
  }

  @Test
  void theChangelogListsEveryRenamedRouteOnBothSides() {
    final String description = description();
    V1_TO_V2.forEach(
        (v1, v2) -> {
          assertTrue(description.contains(v1), v1);
          assertTrue(description.contains(v2), v2);
        });
  }

  @Test
  void theChangelogMigrationTableMentionsEveryOperationOfTheContract() {
    final String description = description();
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      assertTrue(
          description.contains(operation.method() + " " + operation.path()), operation.key());
    }
  }

  @Test
  void everyV2TargetRouteExistsInTheContract() {
    final List<String> keys =
        ApiContract.operations().stream().map(ApiContract.Operation::key).toList();
    V1_TO_V2.values().forEach(v2 -> assertTrue(keys.contains(v2), v2));
  }
}
