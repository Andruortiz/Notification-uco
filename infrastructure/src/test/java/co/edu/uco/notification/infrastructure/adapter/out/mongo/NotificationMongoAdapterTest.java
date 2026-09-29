package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.core.usecase.RequeuePendingNotificationsService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Binary;
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

  private static final byte[] INVOICE_BYTES = "%PDF-1.4 invoice".getBytes(StandardCharsets.UTF_8);
  private static final Sha256Digest LARGE_SHA =
      Sha256Digest.of("large".getBytes(StandardCharsets.UTF_8));

  private static Attachment embeddedInvoice(final TenantId tenant) {
    return new Attachment(
        tenant,
        "invoice.pdf",
        "application/pdf",
        INVOICE_BYTES.length,
        Sha256Digest.of(INVOICE_BYTES),
        new AttachmentSource.EmbeddedContent(INVOICE_BYTES));
  }

  private static Attachment storedContract(final TenantId tenant) {
    return new Attachment(
        tenant,
        "contract.pdf",
        "application/pdf",
        2_000_000L,
        LARGE_SHA,
        new AttachmentSource.StoredObject(
            UploadId.of("upload-1"), "tenants/" + tenant.value() + "/clean/upload-1"));
  }

  private static Notification withAttachments(
      final String externalId, final List<Attachment> attachments) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of(externalId),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(
            NotificationContent.of("Subject", "Body", attachments), Priority.NORMAL));
  }

  @Test
  void saveThenFindByIdKeepsEmbeddedAndStoredAttachmentsInOrder() {
    final TenantId tenant = TenantId.of("tenant-1");
    final List<Attachment> attachments = List.of(embeddedInvoice(tenant), storedContract(tenant));
    final Notification saved =
        adapter.save(withAttachments("order-attachments", attachments)).block();

    StepVerifier.create(adapter.findById(saved.notificationId()))
        .assertNext(found -> assertEquals(attachments, found.content().attachments()))
        .verifyComplete();
  }

  @Test
  void embeddedContentIsStoredAsBinaryAndTheDocumentNeverKeepsAUrl() {
    final Notification saved =
        adapter
            .save(
                withAttachments(
                    "order-binary",
                    List.of(
                        embeddedInvoice(TenantId.of("tenant-1")),
                        storedContract(TenantId.of("tenant-1")))))
            .block();

    final Document raw =
        mongoTemplate
            .getCollection("notifications")
            .flatMap(
                collection ->
                    Mono.from(
                        collection
                            .find(new Document("_id", saved.notificationId().value()))
                            .first()))
            .block();
    final List<Document> stored = raw.getList("attachments", Document.class);
    assertEquals("EMBEDDED", stored.get(0).getString("storage"));
    assertInstanceOf(Binary.class, stored.get(0).get("content"));
    assertEquals("tenant-1", stored.get(0).getString("tenantId"));
    assertEquals(Sha256Digest.of(INVOICE_BYTES).hex(), stored.get(0).getString("sha256"));
    assertEquals("OBJECT", stored.get(1).getString("storage"));
    assertEquals("tenants/tenant-1/clean/upload-1", stored.get(1).getString("objectKey"));
    assertFalse(stored.get(1).containsKey("content"), stored.get(1).toJson());
    assertFalse(raw.toJson().contains("\"url\""), raw.toJson());
  }

  @Test
  void aDocumentStoredWithoutAttachmentsIsReadWithAnEmptyList() {
    final String id = NotificationId.newId().value();
    mongoTemplate
        .getCollection("notifications")
        .flatMap(
            collection ->
                Mono.from(
                    collection.insertOne(
                        new Document("_id", id)
                            .append("tenantId", "tenant-1")
                            .append("externalId", "legacy-1")
                            .append("channelType", "EMAIL")
                            .append("recipientId", "recipient-1")
                            .append("recipientAddress", "alice@example.com")
                            .append("contentBody", "Body")
                            .append("priority", "NORMAL")
                            .append("status", "PENDING")
                            .append("acceptedAt", Date.from(Instant.now()))
                            .append("version", 0L))))
        .block();

    StepVerifier.create(adapter.findById(NotificationId.of(id)))
        .assertNext(found -> assertEquals(List.of(), found.content().attachments()))
        .verifyComplete();
  }

  @Test
  void anAttachmentOfAnotherTenantIsNeverWritten() {
    StepVerifier.create(
            adapter.save(
                withAttachments(
                    "order-foreign", List.of(embeddedInvoice(TenantId.of("tenant-2"))))))
        .expectErrorSatisfies(
            error -> {
              assertInstanceOf(IllegalStateException.class, error);
              assertFalse(error.getMessage().contains("tenant-2"), error.getMessage());
            })
        .verify();

    assertEquals(
        0L,
        mongoTemplate
            .count(
                new org.springframework.data.mongodb.core.query.Query(), NotificationDocument.class)
            .block());
  }

  @Test
  void aStoredAttachmentOfAnotherTenantIsAnIntegrityErrorWhenReadBack() {
    final String id = NotificationId.newId().value();
    mongoTemplate
        .save(
            new NotificationDocument(
                id,
                "tenant-1",
                "order-tampered",
                "EMAIL",
                "recipient-1",
                "alice@example.com",
                "Subject",
                "Body",
                List.of(
                    new AttachmentDocument(
                        "tenant-2",
                        "invoice.pdf",
                        "application/pdf",
                        INVOICE_BYTES.length,
                        Sha256Digest.of(INVOICE_BYTES).hex(),
                        AttachmentDocument.EMBEDDED,
                        INVOICE_BYTES,
                        null,
                        null)),
                Priority.NORMAL,
                NotificationStatus.PENDING,
                Instant.now(),
                List.of(),
                null))
        .block();

    StepVerifier.create(adapter.findById(NotificationId.of(id)))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void searchNeverLoadsTheAttachmentContentButFindByStatusKeepsItForTheRequeue() {
    final Notification saved =
        adapter
            .save(
                withAttachments("order-search", List.of(embeddedInvoice(TenantId.of("tenant-1")))))
            .block();

    final List<Notification> found =
        adapter
            .search(
                new NotificationSearchCriteria(
                    TenantId.of("tenant-1"), null, null, null, null, null, 10, 0))
            .collectList()
            .block();
    assertEquals(1, found.size());
    assertEquals(List.of(), found.getFirst().content().attachments());

    final Notification pending = adapter.findByStatus(NotificationStatus.PENDING).blockFirst();
    assertEquals(saved.notificationId(), pending.notificationId());
    assertEquals(
        List.of(embeddedInvoice(TenantId.of("tenant-1"))), pending.content().attachments());
  }
}
