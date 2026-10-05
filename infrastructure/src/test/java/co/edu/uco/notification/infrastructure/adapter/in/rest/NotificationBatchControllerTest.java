package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.BatchLimits;
import co.edu.uco.notification.core.domain.valueobject.BatchId;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import co.edu.uco.notification.core.port.in.BatchNotificationItem;
import co.edu.uco.notification.core.port.in.SendNotificationBatchCommand;
import co.edu.uco.notification.core.port.in.SendNotificationBatchUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.SecurityConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = NotificationBatchController.class)
@Import({
  SecurityConfig.class,
  AuthenticationWebFilter.class,
  LocalJwtTokenValidationAdapter.class,
  RouteAuthorizationPolicy.class,
  AuthenticatedPrincipalArgumentResolver.class
})
class NotificationBatchControllerTest {

  private static final String VALID_ITEM =
      """
      {
        "externalId": "order-1",
        "channelType": "EMAIL",
        "recipientId": "recipient-1",
        "recipientAddress": "alice@example.com",
        "subject": "Subject",
        "body": "Body",
        "priority": "HIGH"
      }
      """;

  private static final String ITEM_WITHOUT_SUBJECT =
      """
      {
        "externalId": "order-2",
        "channelType": "SMS",
        "recipientId": "recipient-2",
        "recipientAddress": "+573001234567",
        "body": "Hola",
        "priority": "LOW"
      }
      """;

  @Autowired private WebTestClient webTestClient;

  @MockBean private SendNotificationBatchUseCase sendNotificationBatchUseCase;

  private WebTestClient.ResponseSpec post(final String body) {
    return webTestClient
        .post()
        .uri("/notifications:sendBatch")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  @Test
  void sendBatchReturnsAcceptedWithOneResultPerItemInOrder() {
    final NotificationId accepted = NotificationId.newId();
    final NotificationId duplicate = NotificationId.newId();
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-1"),
                    List.of(
                        BatchItemResult.accepted(ExternalId.of("order-1"), accepted),
                        BatchItemResult.duplicate(ExternalId.of("order-2"), duplicate),
                        BatchItemResult.rejected(
                            ExternalId.of("order-3"), "Channel FAX is not available")))));

    post("{\"batchId\": \"batch-1\", \"items\": [" + VALID_ITEM + "]}")
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectBody()
        .jsonPath("$.batchId")
        .isEqualTo("batch-1")
        .jsonPath("$.results.length()")
        .isEqualTo(3)
        .jsonPath("$.results[0].externalId")
        .isEqualTo("order-1")
        .jsonPath("$.results[0].outcome")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.results[0].notificationId")
        .isEqualTo(accepted.value())
        .jsonPath("$.results[1].outcome")
        .isEqualTo("DUPLICATE")
        .jsonPath("$.results[1].notificationId")
        .isEqualTo(duplicate.value())
        .jsonPath("$.results[2].externalId")
        .isEqualTo("order-3")
        .jsonPath("$.results[2].outcome")
        .isEqualTo("REJECTED")
        .jsonPath("$.results[2].notificationId")
        .doesNotExist()
        .jsonPath("$.results[2].rejectionReason")
        .isEqualTo("Channel FAX is not available");
  }

  @Test
  void sendBatchTranslatesTheRequestIntoTheCommand() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-1"),
                    List.of(
                        BatchItemResult.accepted(
                            ExternalId.of("order-1"), NotificationId.newId())))));

    post("{\"batchId\": \"batch-1\", \"items\": [" + VALID_ITEM + "," + ITEM_WITHOUT_SUBJECT + "]}")
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED);

    final ArgumentCaptor<SendNotificationBatchCommand> captor =
        ArgumentCaptor.forClass(SendNotificationBatchCommand.class);
    verify(sendNotificationBatchUseCase).sendBatch(captor.capture());
    final SendNotificationBatchCommand command = captor.getValue();
    assertEquals(TenantId.of("tenant-1"), command.tenantId());
    assertEquals(BatchId.of("batch-1"), command.batchId());
    assertEquals(
        List.of(
            new BatchNotificationItem(
                ExternalId.of("order-1"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Subject", "Body"),
                Priority.HIGH),
            new BatchNotificationItem(
                ExternalId.of("order-2"),
                ChannelType.of("SMS"),
                RecipientId.of("recipient-2"),
                Recipient.of("+573001234567"),
                NotificationContent.of("Hola"),
                Priority.LOW)),
        command.items());
  }

  @Test
  void sendBatchLeavesTheBatchIdToTheUseCaseWhenTheClientOmitsIt() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("generated"),
                    List.of(
                        BatchItemResult.accepted(
                            ExternalId.of("order-1"), NotificationId.newId())))));

    post("{\"items\": [" + VALID_ITEM + "]}")
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectBody()
        .jsonPath("$.batchId")
        .isEqualTo("generated");

    final ArgumentCaptor<SendNotificationBatchCommand> captor =
        ArgumentCaptor.forClass(SendNotificationBatchCommand.class);
    verify(sendNotificationBatchUseCase).sendBatch(captor.capture());
    assertNull(captor.getValue().batchId());
  }

  static Stream<Arguments> structurallyInvalidBatches() {
    return Stream.of(
        Arguments.of("items vacio", "{\"items\": []}"),
        Arguments.of("items ausente", "{\"batchId\": \"batch-1\"}"),
        Arguments.of("elemento nulo", "{\"items\": [" + VALID_ITEM + ", null]}"),
        Arguments.of(
            "priority ausente",
            "{\"items\": [" + VALID_ITEM.replace(",\n  \"priority\": \"HIGH\"", "") + "]}"),
        Arguments.of(
            "priority desconocida",
            "{\"items\": [" + VALID_ITEM.replace("\"HIGH\"", "\"URGENT\"") + "]}"),
        Arguments.of(
            "externalId en blanco",
            "{\"items\": [" + VALID_ITEM.replace("\"order-1\"", "\" \"") + "]}"),
        Arguments.of(
            "recipientAddress ausente",
            "{\"items\": ["
                + VALID_ITEM.replace("\"recipientAddress\": \"alice@example.com\",", "")
                + "]}"),
        Arguments.of("batchId en blanco", "{\"batchId\": \"\", \"items\": [" + VALID_ITEM + "]}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("structurallyInvalidBatches")
  void sendBatchRejectsAStructurallyInvalidBatchWithoutCallingTheUseCase(
      final String scenario, final String body) {
    post(body).expectStatus().isBadRequest();

    verify(sendNotificationBatchUseCase, never()).sendBatch(any());
  }

  private static String batchOf(final int size) {
    return "{\"batchId\": \"batch-big\", \"items\": ["
        + String.join(",", Collections.nCopies(size, VALID_ITEM))
        + "]}";
  }

  @Test
  void sendBatchRejectsMoreThanTheMaximumItemsWithoutCallingTheUseCase() {
    post(batchOf(BatchLimits.MAX_ITEMS + 1)).expectStatus().isBadRequest();

    verify(sendNotificationBatchUseCase, never()).sendBatch(any());
  }

  @Test
  void sendBatchAcceptsExactlyTheMaximumItems() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-big"),
                    List.of(
                        BatchItemResult.accepted(
                            ExternalId.of("order-1"), NotificationId.newId())))));

    post(batchOf(BatchLimits.MAX_ITEMS)).expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    final ArgumentCaptor<SendNotificationBatchCommand> captor =
        ArgumentCaptor.forClass(SendNotificationBatchCommand.class);
    verify(sendNotificationBatchUseCase).sendBatch(captor.capture());
    assertEquals(BatchLimits.MAX_ITEMS, captor.getValue().items().size());
  }

  @Test
  void sendBatchExposesTheTrackingFlagInTheResponse() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-1"),
                    List.of(
                        BatchItemResult.accepted(ExternalId.of("order-1"), NotificationId.newId())),
                    false)));

    post("{\"batchId\": \"batch-1\", \"items\": [" + VALID_ITEM + "]}")
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectBody()
        .jsonPath("$.trackingSaved")
        .isEqualTo(false);
  }

  @Test
  void sendBatchRejectsARequestWithoutAToken() {
    webTestClient
        .post()
        .uri("/notifications:sendBatch")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"items\": [" + VALID_ITEM + "]}")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    verify(sendNotificationBatchUseCase, never()).sendBatch(any());
  }

  @Test
  void sendBatchPassesTheRequestCorrelationIdToTheCommand() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-c"),
                    List.of(
                        BatchItemResult.accepted(
                            ExternalId.of("order-1"), NotificationId.newId())))));

    webTestClient
        .post()
        .uri("/notifications:sendBatch")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .header(CorrelationId.HEADER, "req-batch-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"batchId\": \"batch-c\", \"items\": [" + VALID_ITEM + "]}")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, "req-batch-1");

    final ArgumentCaptor<SendNotificationBatchCommand> command =
        ArgumentCaptor.forClass(SendNotificationBatchCommand.class);
    verify(sendNotificationBatchUseCase).sendBatch(command.capture());
    assertEquals(CorrelationId.of("req-batch-1"), command.getValue().correlationId());
  }

  @Test
  void sendBatchWithoutTheHeaderGeneratesAnIdForTheCommand() {
    when(sendNotificationBatchUseCase.sendBatch(any()))
        .thenReturn(
            Mono.just(
                new BatchAcceptedResult(
                    BatchId.of("batch-d"),
                    List.of(
                        BatchItemResult.accepted(
                            ExternalId.of("order-1"), NotificationId.newId())))));

    post("{\"batchId\": \"batch-d\", \"items\": [" + VALID_ITEM + "]}")
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED);

    final ArgumentCaptor<SendNotificationBatchCommand> command =
        ArgumentCaptor.forClass(SendNotificationBatchCommand.class);
    verify(sendNotificationBatchUseCase).sendBatch(command.capture());
    assertNotNull(command.getValue().correlationId());
  }
}
