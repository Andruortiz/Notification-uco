package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

class ApiV2RoutesE2ETest extends ApiV2E2ESupport {

  private static String normalized(final String body) {
    return body.replaceAll("\"correlationId\":\"[^\"]*\"", "")
        .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>");
  }

  @Test
  void aNotificationIsSentAndReadBackThroughTheQueryParameter() {
    final Map<String, Object> sent = send(TENANT_A, notification("v2-flow-1", "recipient-flow"));
    final String id = (String) sent.get("notificationId");

    final Map<String, Object> status =
        detail(TENANT_A, id)
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertNotNull(status);
    assertEquals(id, status.get("notificationId"));
    assertEquals("EMAIL", status.get("channelType"));
  }

  @Test
  void aMissingOrMalformedIdentifierIsABadRequestWithTheErrorBody() {
    client
        .get()
        .uri("/notifications/detail")
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isNotEmpty()
        .jsonPath("$.correlationId")
        .isNotEmpty();
    client
        .get()
        .uri("/attachment-uploads/status")
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isNotEmpty()
        .jsonPath("$.correlationId")
        .isNotEmpty();
  }

  @Test
  void anIdentifierOfAnotherTenantIsIndistinguishableFromAnUnknownOne() {
    final String id =
        (String)
            send(TENANT_A, notification("v2-tenant-1", "recipient-tenant")).get("notificationId");

    final String foreign =
        new String(
            detail(TENANT_B, id)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .returnResult()
                .getResponseBody());
    final String unknown =
        new String(
            detail(TENANT_B, NotificationId.newId().value())
                .expectStatus()
                .isNotFound()
                .expectBody()
                .returnResult()
                .getResponseBody());

    assertTrue(foreign.contains("\"code\""));
    assertEquals(normalized(foreign), normalized(unknown));
    detail(TENANT_A, id).expectStatus().isOk();
  }

  @Test
  void versionOneRoutesAreNotServed() {
    final String id = NotificationId.newId().value();
    final String admin = TestTokens.bearer(TENANT_A, Role.ADMINISTRADOR);

    client
        .get()
        .uri("/notifications/" + id)
        .header("Authorization", admin)
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/attachment-uploads/upload-1")
        .header("Authorization", admin)
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .post()
        .uri("/attachment-uploads/upload-1:complete")
        .header("Authorization", admin)
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .post()
        .uri("/notifications:sendBatch")
        .header("Authorization", admin)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("items", List.of()))
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/notifications:subscribe")
        .header("Authorization", admin)
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void thePlannedOperationsAreNotServed() {
    final String admin = TestTokens.bearer(TENANT_A, Role.ADMINISTRADOR);
    for (final String path :
        List.of("/notification-retries", "/channels", "/providers", "/recipients/preferences")) {
      final int post =
          client
              .post()
              .uri(path)
              .header("Authorization", admin)
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(Map.of())
              .exchange()
              .returnResult(String.class)
              .getStatus()
              .value();
      assertNotServed(post);
    }
    assertNotServed(
        client
            .put()
            .uri("/recipients/preferences?recipientId=r1")
            .header("Authorization", admin)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange()
            .returnResult(String.class)
            .getStatus()
            .value());
    assertNotServed(
        client
            .get()
            .uri("/recipients/preferences?recipientId=r1")
            .header("Authorization", admin)
            .exchange()
            .returnResult(String.class)
            .getStatus()
            .value());
  }

  @Test
  void theUploadFlowRunsOnTheLiteralRoutes() throws IOException, InterruptedException {
    final var result =
        issueUpload(TENANT_A)
            .expectStatus()
            .isCreated()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult();
    final Map<String, Object> issued = result.getResponseBody();
    assertNotNull(issued);
    final String uploadId = (String) issued.get("uploadId");
    final String location = result.getResponseHeaders().getFirst("Location");
    assertEquals("/attachment-uploads/status?uploadId=" + uploadId, location);

    client
        .get()
        .uri(URI.create("http://localhost:" + port + location))
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.uploadId")
        .isEqualTo(uploadId)
        .jsonPath("$.state")
        .isEqualTo("PENDING_SCAN");

    assertEquals(204, uploadFile(issued, fileOfBytes(1_048_577)));

    client
        .post()
        .uri("/attachment-upload-completions")
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("uploadId", uploadId))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.uploadId")
        .isEqualTo(uploadId);

    client
        .get()
        .uri("/attachment-uploads/status?uploadId={id}", uploadId)
        .header("Authorization", TestTokens.bearer(TENANT_B))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void anIdentifierWithReservedCharactersTravelsPercentEncodedInTheQuery() {
    final String recipient = "a&b c=d";
    send(TENANT_A, notification("v2-encoded-1", recipient));

    client
        .get()
        .uri(URI.create("http://localhost:" + port + "/notifications?recipientId=a%26b%20c%3Dd"))
        .header("Authorization", TestTokens.bearer(TENANT_A))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].recipientId")
        .isEqualTo(recipient);

    client
        .get()
        .uri(URI.create("http://localhost:" + port + "/notifications?recipientId=a%26b%20c%3Dd"))
        .header("Authorization", TestTokens.bearer(TENANT_B))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
  }
}
