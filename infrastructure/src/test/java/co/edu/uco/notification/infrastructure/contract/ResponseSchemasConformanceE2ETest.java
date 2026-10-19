package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class ResponseSchemasConformanceE2ETest extends ApiV2E2ESupport {

  private final Set<String> covered = new TreeSet<>();
  private final List<String> violations = new ArrayList<>();

  private Object body(final WebTestClient.ResponseSpec response) {
    return response
        .expectBody(new ParameterizedTypeReference<Object>() {})
        .returnResult()
        .getResponseBody();
  }

  private void conforms(final String operation, final Object body, final String schema) {
    covered.add(operation);
    SchemaConformance.violations(body, schema)
        .forEach(violation -> violations.add(operation + " " + violation));
  }

  @Test
  void everyAvailableOperationAnswersWithTheShapeTheContractDeclares()
      throws IOException, InterruptedException {
    final String auth = TestTokens.bearer(TENANT_A);

    final Map<String, Object> request = notification("v2-conf-1", "recipient-conf");
    final Object sent =
        body(
            client
                .post()
                .uri("/notifications")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications", sent, "SendNotificationResponse");
    final String id = (String) ((Map<?, ?>) sent).get("notificationId");

    final Object duplicate =
        body(
            client
                .post()
                .uri("/notifications")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications", duplicate, "SendNotificationResponse");
    assertEquals(true, ((Map<?, ?>) duplicate).get("duplicate"));

    conforms(
        "GET /notifications/detail",
        body(detail(TENANT_A, id).expectStatus().isOk()),
        "NotificationStatusResponse");

    conforms(
        "GET /notifications",
        body(
            client
                .get()
                .uri("/notifications?recipientId=recipient-conf")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "NotificationSearchResponse");

    final Object batch =
        body(
            client
                .post()
                .uri("/notifications/batches")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    Map.of(
                        "items",
                        List.of(
                            notification("v2-conf-batch-1", "recipient-conf"),
                            Map.of(
                                "externalId", "v2-conf-batch-2",
                                "channelType", "NO_SUCH_CHANNEL",
                                "recipientId", "recipient-conf",
                                "recipientAddress", "alice@example.com",
                                "body", "Body",
                                "priority", "NORMAL"))))
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications/batches", batch, "BatchAcceptedResponse");
    final List<?> results = (List<?>) ((Map<?, ?>) batch).get("results");
    assertEquals(2, results.size());
    assertEquals("REJECTED", ((Map<?, ?>) results.get(1)).get("outcome"));
    assertEquals(null, ((Map<?, ?>) results.get(1)).get("notificationId"));

    conforms(
        "GET /channels",
        body(
            client
                .get()
                .uri("/channels")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ChannelCatalogResponse");
    conforms(
        "GET /providers",
        body(
            client
                .get()
                .uri("/providers")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ProviderCatalogResponse");
    conforms(
        "GET /configuration",
        body(
            client
                .get()
                .uri("/configuration")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ConfigurationResponse");
    conforms(
        "POST /notifications/stream/tickets",
        body(
            client
                .post()
                .uri("/notifications/stream/tickets")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "SubscriptionTicketResponse");

    final var issuedResult =
        issueUpload(TENANT_A)
            .expectStatus()
            .isCreated()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult();
    final Map<String, Object> issued = issuedResult.getResponseBody();
    assertNotNull(issued);
    conforms("POST /attachment-uploads", issued, "AttachmentUploadResponse");
    final String uploadId = (String) issued.get("uploadId");
    conforms(
        "GET /attachment-uploads/status",
        body(
            client
                .get()
                .uri(
                    URI.create(
                        "http://localhost:"
                            + port
                            + issuedResult.getResponseHeaders().getFirst("Location")))
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "AttachmentUploadResponse");
    assertEquals(204, uploadFile(issued, fileOfBytes(1_048_577)));
    conforms(
        "POST /attachment-upload-completions",
        body(
            client
                .post()
                .uri("/attachment-upload-completions")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("uploadId", uploadId))
                .exchange()
                .expectStatus()
                .isAccepted()),
        "AttachmentUploadResponse");

    final Object notFound = body(detail(TENANT_B, id).expectStatus().isNotFound());
    assertEquals(List.of(), SchemaConformance.violations(notFound, "ErrorResponse"));

    assertEquals(List.of(), violations);
    final Set<String> available = new TreeSet<>();
    ApiContract.operations().stream()
        .filter(ApiContract.Operation::available)
        .forEach(operation -> available.add(operation.key()));
    available.remove("GET /notifications/stream");
    assertFalse(available.isEmpty());
    assertEquals(available, covered);
  }
}
