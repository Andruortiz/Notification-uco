package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.AttemptRecord;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.UndeliverableRecipient;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class UndeliverableRecipientMongoAdapterTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final TenantId OTHER = TenantId.of("tenant-2");
  private static final ChannelType EMAIL = ChannelType.of("EMAIL");
  private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private UndeliverableRecipientMongoAdapter marks;
  private NotificationMongoAdapter notifications;

  @BeforeEach
  void setUp() {
    marks = new UndeliverableRecipientMongoAdapter(mongoTemplate);
    notifications = new NotificationMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(UndeliverableRecipientDocument.class).block();
    mongoTemplate.dropCollection(NotificationDocument.class).block();
    mongoTemplate
        .indexOps(UndeliverableRecipientDocument.class)
        .ensureIndex(
            new CompoundIndexDefinition(
                    Document.parse("{'tenantId': 1, 'channelType': 1, 'normalizedAddress': 1}"))
                .unique()
                .named("tenant_channel_address_unique"))
        .block();
  }

  private static UndeliverableRecipient mark(final TenantId tenant, final String reason) {
    return new UndeliverableRecipient(tenant, EMAIL, "a@example.com", reason, NOW);
  }

  @Test
  void concurrentMarksOfTheSameKeyCreateExactlyOneDocumentAndOneCreation() {
    final List<Boolean> results =
        Flux.range(0, 20)
            .flatMap(i -> marks.markIfAbsent(mark(TENANT, "hard_bounce")), 20)
            .collectList()
            .block();

    assertEquals(1, results.stream().filter(created -> created).count());
    assertEquals(
        1L, mongoTemplate.count(new Query(), UndeliverableRecipientDocument.class).block());
  }

  @Test
  void aSecondMarkKeepsTheFirstReasonAndDate() {
    marks.markIfAbsent(mark(TENANT, "hard_bounce")).block();
    marks
        .markIfAbsent(
            new UndeliverableRecipient(
                TENANT, EMAIL, "a@example.com", "blocked", NOW.plusSeconds(60)))
        .block();

    StepVerifier.create(marks.find(TENANT, EMAIL, "a@example.com"))
        .assertNext(
            found -> {
              assertEquals("hard_bounce", found.reason());
              assertEquals(NOW, found.markedAt());
            })
        .verifyComplete();
  }

  @Test
  void marksAreIsolatedByTenantAndChannelAndCanBeRemoved() {
    marks.markIfAbsent(mark(TENANT, "hard_bounce")).block();

    StepVerifier.create(marks.find(OTHER, EMAIL, "a@example.com")).verifyComplete();
    StepVerifier.create(marks.find(TENANT, ChannelType.of("SMS"), "a@example.com"))
        .verifyComplete();
    StepVerifier.create(marks.remove(OTHER, EMAIL, "a@example.com")).verifyComplete();
    StepVerifier.create(marks.find(TENANT, EMAIL, "a@example.com"))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(marks.remove(TENANT, EMAIL, "a@example.com")).verifyComplete();
    StepVerifier.create(marks.find(TENANT, EMAIL, "a@example.com")).verifyComplete();
  }

  @Test
  void aNotificationIsResolvedByTheMessageIdOfAnyOfItsAttempts() {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TENANT,
                ExternalId.of("order-1"),
                EMAIL,
                RecipientId.of("recipient-1"),
                Recipient.of("Alice@Example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    final Notification saved = notifications.save(notification).block();
    saved.markQueued();
    saved.markDelivered(
        new AttemptRecord(
            AttemptResult.ACCEPTED,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("brevo"),
            false,
            null,
            null,
            null,
            "<m-1@smtp>"));
    notifications.save(saved).block();

    StepVerifier.create(notifications.findByProviderMessageId("<m-1@smtp>"))
        .assertNext(
            ref -> {
              assertEquals(TENANT, ref.tenantId());
              assertEquals(EMAIL, ref.channelType());
              assertEquals("Alice@Example.com", ref.address());
              assertEquals(saved.notificationId(), ref.notificationId());
            })
        .verifyComplete();
    StepVerifier.create(notifications.findByProviderMessageId("<other@smtp>")).verifyComplete();
  }
}
