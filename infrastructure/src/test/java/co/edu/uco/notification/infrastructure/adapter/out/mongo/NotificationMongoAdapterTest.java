package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
            ExternalId.of("order-42"),
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
    assertThrows(OptimisticLockingFailureException.class, () -> adapter.save(secondLoad).block());
  }
}
