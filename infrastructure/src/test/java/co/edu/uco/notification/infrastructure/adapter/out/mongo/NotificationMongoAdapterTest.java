package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.usecase.RequeuePendingNotificationsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class NotificationMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private NotificationMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new NotificationMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(NotificationDocument.class).block();
    mongoTemplate
        .indexOps(NotificationDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(Document.parse("{'tenantId': 1, 'externalId': 1}"))
                .unique()
                .named("tenant_external_unique"))
        .block();
  }

  private static Notification aNotification(final String externalId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of(externalId),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  @Test
  void savePersistsANewNotificationWithVersionZero() {
    final Notification saved = adapter.save(aNotification("order-1")).block();

    assertEquals(0L, saved.version());
  }

  @Test
  void saveThenFindByIdRoundTrips() {
    final Notification saved = adapter.save(aNotification("order-2")).block();

    StepVerifier.create(adapter.findById(saved.notificationId()))
        .assertNext(
            found -> {
              assertEquals(saved.notificationId(), found.notificationId());
              assertEquals(saved.tenantId(), found.tenantId());
              assertEquals(saved.externalId(), found.externalId());
              assertEquals(saved.version(), found.version());
            })
        .verifyComplete();
  }

  @Test
  void findByIdIsEmptyWhenMissing() {
    StepVerifier.create(adapter.findById(NotificationId.newId())).verifyComplete();
  }

  @Test
  void findByTenantAndExternalIdFindsAnExistingNotification() {
    adapter.save(aNotification("order-3")).block();

    StepVerifier.create(
            adapter.findByTenantAndExternalId(TenantId.of("tenant-1"), ExternalId.of("order-3")))
        .assertNext(found -> assertEquals(ExternalId.of("order-3"), found.externalId()))
        .verifyComplete();
  }

  @Test
  void findByTenantAndExternalIdIsEmptyWhenNoMatch() {
    StepVerifier.create(
            adapter.findByTenantAndExternalId(TenantId.of("tenant-1"), ExternalId.of("missing")))
        .verifyComplete();
  }

  @Test
  void saveRejectsADuplicateTenantAndExternalId() {
    adapter.save(aNotification("order-4")).block();

    assertThrows(DuplicateKeyException.class, () -> adapter.save(aNotification("order-4")).block());
  }

  @Test
  void saveRejectsAConcurrentModificationOfTheSameNotification() {
    final Notification saved = adapter.save(aNotification("order-5")).block();
    final Notification firstLoad = adapter.findById(saved.notificationId()).block();
    final Notification secondLoad = adapter.findById(saved.notificationId()).block();

    firstLoad.markQueued();
    adapter.save(firstLoad).block();

    secondLoad.markQueued();
    assertThrows(
        NotificationVersionConflictException.class, () -> adapter.save(secondLoad).block());
  }

  @Test
  void requeuePendingRequeuesOnlyNotificationsWhoseBackoffAlreadyElapsed() {
    final Notification due =
        adapter.save(recoverableNotification("order-6", Instant.now().minusSeconds(120))).block();
    final Notification notDue =
        adapter.save(recoverableNotification("order-7", Instant.now())).block();

    final NotificationEventPublisherPort eventPublisherPort =
        Mockito.mock(NotificationEventPublisherPort.class);
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any())).thenReturn(Mono.empty());
    final RequeuePendingNotificationsService service =
        new RequeuePendingNotificationsService(
            adapter, eventPublisherPort, new RetryPolicy(), Duration.ofSeconds(60));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    StepVerifier.create(adapter.findById(due.notificationId()))
        .assertNext(found -> assertEquals(NotificationStatus.PENDING, found.status()))
        .verifyComplete();
    StepVerifier.create(adapter.findById(notDue.notificationId()))
        .assertNext(found -> assertEquals(NotificationStatus.RECOVERABLE, found.status()))
        .verifyComplete();
  }

  @Test
  void requeuePendingReenqueuesOrphanedPendingNotificationsWithoutAnyAttempt() {
    final Notification orphaned =
        adapter.save(pendingOrphanNotification("order-8", Instant.now().minusSeconds(120))).block();
    final Notification recentlyAccepted =
        adapter.save(pendingOrphanNotification("order-9", Instant.now())).block();

    final NotificationEventPublisherPort eventPublisherPort =
        Mockito.mock(NotificationEventPublisherPort.class);
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any())).thenReturn(Mono.empty());
    final RequeuePendingNotificationsService service =
        new RequeuePendingNotificationsService(
            adapter, eventPublisherPort, new RetryPolicy(), Duration.ofSeconds(60));

    StepVerifier.create(service.requeuePending()).verifyComplete();

    verify(eventPublisherPort).enqueueForDispatch(orphaned);
    verify(eventPublisherPort, never()).enqueueForDispatch(recentlyAccepted);
  }

  private static Notification pendingOrphanNotification(
      final String externalId, final Instant acceptedAt) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of(externalId),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.PENDING,
        new NotificationMetadata(acceptedAt, null),
        List.of());
  }

  private static Notification recoverableNotification(
      final String externalId, final Instant lastAttemptAt) {
    final List<DeliveryAttempt> attempts =
        List.of(
            DeliveryAttempt.of(
                lastAttemptAt,
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptOrigin.AUTOMATIC,
                ProviderId.of("brevo")));
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of(externalId),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.RECOVERABLE,
        new NotificationMetadata(Instant.now().minusSeconds(300), null),
        attempts);
  }
}
