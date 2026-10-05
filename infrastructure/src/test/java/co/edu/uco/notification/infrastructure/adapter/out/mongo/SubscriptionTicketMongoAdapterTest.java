package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SubscriptionTicketFingerprint;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class SubscriptionTicketMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final AuthenticatedPrincipal PRINCIPAL =
      new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), Role.OPERADOR);
  private static final String TICKET = "plain-ticket-value-that-must-never-be-stored-0001";

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private SubscriptionTicketMongoAdapter adapterAt(final Instant now) {
    return new SubscriptionTicketMongoAdapter(mongoTemplate, Clock.fixed(now, ZoneOffset.UTC));
  }

  @BeforeEach
  void setUp() {
    mongoTemplate.dropCollection(SubscriptionTicketDocument.class).block();
    adapterAt(NOW).ensureIndexes().block();
  }

  private void saveTicket(final SubscriptionTicketMongoAdapter adapter, final Instant expiresAt) {
    adapter.save(SubscriptionTicketFingerprint.of(TICKET), PRINCIPAL, expiresAt).block();
  }

  @Test
  void aTicketIsConsumedOnceAndTheSecondConsumptionIsEmpty() {
    final SubscriptionTicketMongoAdapter adapter = adapterAt(NOW);
    saveTicket(adapter, NOW.plusSeconds(30));
    final String fingerprint = SubscriptionTicketFingerprint.of(TICKET);

    assertEquals(PRINCIPAL, adapter.consume(fingerprint).block());
    assertNull(adapter.consume(fingerprint).block());
  }

  @Test
  void anExpiredTicketIsEmptyEvenIfMongoTtlHasNotRun() {
    saveTicket(adapterAt(NOW), NOW.plusSeconds(30));

    assertNull(
        adapterAt(NOW.plusSeconds(31)).consume(SubscriptionTicketFingerprint.of(TICKET)).block());
    assertEquals(1L, mongoTemplate.count(new Query(), "subscription_tickets").block());
  }

  @Test
  void anUnknownTicketIsEmpty() {
    saveTicket(adapterAt(NOW), NOW.plusSeconds(30));

    assertNull(adapterAt(NOW).consume(SubscriptionTicketFingerprint.of("another-ticket")).block());
  }

  @Test
  void concurrentConsumptionsOfTheSameTicketProduceExactlyOneWinner() {
    final SubscriptionTicketMongoAdapter adapter = adapterAt(NOW);
    saveTicket(adapter, NOW.plusSeconds(30));
    final String fingerprint = SubscriptionTicketFingerprint.of(TICKET);

    final List<AuthenticatedPrincipal> winners =
        Flux.range(0, 20)
            .flatMap(i -> adapter.consume(fingerprint).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(Duration.ofSeconds(30));

    assertEquals(1, winners.size());
    assertEquals(PRINCIPAL, winners.get(0));
  }

  @Test
  void theStoredDocumentNeverContainsTheTicketInClear() {
    saveTicket(adapterAt(NOW), NOW.plusSeconds(30));

    final List<Document> documents =
        mongoTemplate.findAll(Document.class, "subscription_tickets").collectList().block();

    assertEquals(1, documents.size());
    final Document document = documents.get(0);
    assertFalse(document.toJson().contains(TICKET));
    assertEquals(SubscriptionTicketFingerprint.of(TICKET), document.get("_id"));
    assertEquals("tenant-a", document.get("tenantId"));
    assertEquals("OPERADOR", document.get("role"));
    assertEquals("client-1", document.get("subject"));
  }

  @Test
  void theCollectionHasATtlIndexOverExpiresAt() {
    final List<IndexInfo> indexes =
        mongoTemplate
            .indexOps(SubscriptionTicketDocument.class)
            .getIndexInfo()
            .collectList()
            .block();

    assertTrue(
        indexes.stream()
            .anyMatch(
                index ->
                    index.getIndexFields().stream()
                            .anyMatch(field -> field.getKey().equals("expiresAt"))
                        && index
                            .getExpireAfter()
                            .map(Duration.ofSeconds(60)::equals)
                            .orElse(false)));
  }
}
