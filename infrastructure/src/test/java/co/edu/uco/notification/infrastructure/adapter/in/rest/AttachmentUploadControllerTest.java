package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.in.CompleteAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.GetAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.IssueAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.in.IssuedUpload;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.SecurityConfig;
import co.edu.uco.notification.infrastructure.config.WebFluxConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = AttachmentUploadController.class)
@Import({
  SecurityConfig.class,
  AuthenticationWebFilter.class,
  LocalJwtTokenValidationAdapter.class,
  RouteAuthorizationPolicy.class,
  AuthenticatedPrincipalArgumentResolver.class,
  WebFluxConfig.class
})
class AttachmentUploadControllerTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final UploadId UPLOAD_ID = UploadId.of("upload-1");
  private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
  private static final String SIGNED_URL =
      "http://minio.test/bucket/tenants/tenant-1/uploads/upload-1?X-Amz-Signature=secret-9090";
  private static final String BODY =
      """
      {"fileName": "contract.pdf", "contentType": "application/pdf", "sizeBytes": 2000000}
      """;

  @Autowired private WebTestClient webTestClient;

  @MockBean private IssueAttachmentUploadUseCase issueUseCase;

  @MockBean private CompleteAttachmentUploadUseCase completeUseCase;

  @MockBean private GetAttachmentUploadUseCase getUseCase;

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(AttachmentUploadController.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(AttachmentUploadController.class)).detachAppender(logs);
  }

  private static AttachmentUpload pending() {
    return AttachmentUpload.issue(
        UPLOAD_ID,
        TENANT,
        "contract.pdf",
        "application/pdf",
        2_000_000L,
        NOW,
        NOW.plusSeconds(900));
  }

  private boolean logsContain(final String fragment) {
    return logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(fragment));
  }

  @Test
  void issueReturnsCreatedWithTheSignedUrlOnlyOnce() {
    when(issueUseCase.issue(TENANT, "contract.pdf", "application/pdf", 2_000_000L))
        .thenReturn(Mono.just(new IssuedUpload(pending(), SIGNED_URL, NOW.plusSeconds(900))));

    webTestClient
        .post()
        .uri("/attachment-uploads")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .location("/attachment-uploads/upload-1")
        .expectBody()
        .jsonPath("$.uploadId")
        .isEqualTo("upload-1")
        .jsonPath("$.state")
        .isEqualTo("PENDING_SCAN")
        .jsonPath("$.uploadUrl")
        .isEqualTo(SIGNED_URL)
        .jsonPath("$.expiresAt")
        .isEqualTo("2026-09-29T10:15:00Z");

    assertTrue(logsContain("Attachment upload issued tenantId=tenant-1 uploadId=upload-1"));
    assertFalse(logsContain("secret-9090"));
  }

  @Test
  void completeReturnsAcceptedWithTheStateAndNoUrl() {
    when(completeUseCase.complete(TENANT, UPLOAD_ID))
        .thenReturn(Mono.just(pending().markCompleted(NOW)));

    webTestClient
        .post()
        .uri("/attachment-uploads/upload-1:complete")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.state")
        .isEqualTo("PENDING_SCAN")
        .jsonPath("$.uploadUrl")
        .doesNotExist();

    verify(completeUseCase).complete(TENANT, UPLOAD_ID);
    assertTrue(logsContain("Attachment upload completed tenantId=tenant-1 uploadId=upload-1"));
  }

  @Test
  void getReturnsTheStateHashAndReasonButNeverTheUrl() {
    final Sha256Digest sha = Sha256Digest.of("x".getBytes(StandardCharsets.UTF_8));
    when(getUseCase.get(TENANT, UPLOAD_ID))
        .thenReturn(
            Mono.just(
                pending().markInfected(sha, AttachmentRejectionReason.MALWARE, "Eicar", NOW)));

    webTestClient
        .get()
        .uri("/attachment-uploads/upload-1")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.state")
        .isEqualTo("INFECTED")
        .jsonPath("$.sha256")
        .isEqualTo(sha.hex())
        .jsonPath("$.rejectionReason")
        .isEqualTo("MALWARE")
        .jsonPath("$.uploadUrl")
        .doesNotExist()
        .jsonPath("$.signature")
        .doesNotExist();
  }

  @Test
  void errorsMapToTheirStatus() {
    when(getUseCase.get(any(), any()))
        .thenReturn(Mono.error(new AttachmentUploadNotFoundException()));
    when(completeUseCase.complete(any(), any()))
        .thenReturn(Mono.error(new AttachmentNotUploadedException()));
    when(issueUseCase.issue(any(), any(), any(), any()))
        .thenReturn(
            Mono.error(
                new AttachmentInspectionUnavailableException("the file storage is not available")));

    webTestClient
        .get()
        .uri("/attachment-uploads/upload-1")
        .header("Authorization", TestTokens.bearer("tenant-2"))
        .exchange()
        .expectStatus()
        .isNotFound();
    webTestClient
        .post()
        .uri("/attachment-uploads/upload-1:complete")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    webTestClient
        .post()
        .uri("/attachment-uploads")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void anInvalidUploadRequestIsABadRequest() {
    when(issueUseCase.issue(any(), any(), any(), any()))
        .thenThrow(InvalidAttachmentException.forUpload("fileName extension is not allowed"));

    webTestClient
        .post()
        .uri("/attachment-uploads")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(BODY.replace("contract.pdf", "setup.exe"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("upload: fileName extension is not allowed");
  }

  @Test
  void theResponseToStringHidesTheUrl() {
    final String text =
        AttachmentUploadResponse.issued(new IssuedUpload(pending(), SIGNED_URL, NOW)).toString();

    assertFalse(text.contains("secret-9090"), text);
    assertTrue(text.contains("upload-1"), text);
  }
}
