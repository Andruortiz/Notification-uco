package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationMongoAdapter;
import co.edu.uco.notification.infrastructure.adapter.out.rabbit.NotificationRabbitPublisher;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.channels.EMAIL.providers[0]=counting",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.scheduler.pending-orphan-threshold-ms=1000",
      "notification.scheduler.in-process-timeout-ms=2000"
    })
@Testcontainers
class DispatchIdempotencyE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final class CountingSender implements NotificationSenderPort {

    private final Map<String, AtomicInteger> sends = new ConcurrentHashMap<>();

    @Override
    public Mono<AttemptResult> send(final Notification notification) {
      sends
          .computeIfAbsent(notification.notificationId().value(), key -> new AtomicInteger())
          .incrementAndGet();
      return Mono.just(AttemptResult.ACCEPTED);
    }

    int sendsOf(final NotificationId id) {
      final AtomicInteger count = sends.get(id.value());
      return count == null ? 0 : count.get();
    }

    @Override
    public ProviderId providerId() {
      return ProviderId.of("counting");
    }

    @Override
    public Optional<String> disabledReason() {
      return Optional.empty();
    }

    @Override
    public boolean supportsAttachments() {
      return true;
    }
  }

  static final class SaveFailureSwitch {

    final AtomicBoolean failSaves = new AtomicBoolean(false);
    final AtomicInteger enqueueFailures = new AtomicInteger(0);
  }

  static final class FlakyNotificationRepository implements NotificationRepository {

    private final NotificationMongoAdapter delegate;
    private final SaveFailureSwitch failureSwitch;

    FlakyNotificationRepository(
        final NotificationMongoAdapter delegate, final SaveFailureSwitch failureSwitch) {
      this.delegate = delegate;
      this.failureSwitch = failureSwitch;
    }

    @Override
    public Mono<Notification> save(final Notification notification) {
      return failureSwitch.failSaves.get()
          ? Mono.error(new IllegalStateException("mongo down"))
          : delegate.save(notification);
    }

    @Override
    public Mono<Notification> findById(final NotificationId notificationId) {
      return delegate.findById(notificationId);
    }

    @Override
    public Mono<Notification> findByTenantAndExternalId(
        final TenantId tenantId, final ExternalId externalId) {
      return delegate.findByTenantAndExternalId(tenantId, externalId);
    }

    @Override
    public Flux<Notification> findByStatus(final NotificationStatus status) {
      return delegate.findByStatus(status);
    }

    @Override
    public Flux<Notification> search(final NotificationSearchCriteria criteria) {
      return delegate.search(criteria);
    }

    @Override
    public Mono<Notification> reserveForDispatch(final NotificationId notificationId) {
      return delegate.reserveForDispatch(notificationId);
    }

    @Override
    public Mono<Notification> releaseReservation(final NotificationId notificationId) {
      return delegate.releaseReservation(notificationId);
    }

    @Override
    public Flux<Notification> claimForRequeue(final Instant threshold, final int limit) {
      return delegate.claimForRequeue(threshold, limit);
    }

    @Override
    public Flux<Notification> claimStuckInProcess(final Instant threshold, final int limit) {
      return delegate.claimStuckInProcess(threshold, limit);
    }
  }

  static final class FlakyEventPublisher implements NotificationEventPublisherPort {

    private final NotificationRabbitPublisher delegate;
    private final SaveFailureSwitch failureSwitch;

    FlakyEventPublisher(
        final NotificationRabbitPublisher delegate, final SaveFailureSwitch failureSwitch) {
      this.delegate = delegate;
      this.failureSwitch = failureSwitch;
    }

    @Override
    public Mono<Void> enqueueForDispatch(final Notification notification) {
      return failureSwitch.enqueueFailures.getAndUpdate(count -> Math.max(0, count - 1)) > 0
          ? Mono.error(new IllegalStateException("broker down"))
          : delegate.enqueueForDispatch(notification);
    }

    @Override
    public Mono<Void> publish(final List<DomainEvent> events) {
      return delegate.publish(events);
    }
  }

  @TestConfiguration
  static class FlakyInfrastructureConfiguration {

    @Bean
    CountingSender countingSender() {
      return new CountingSender();
    }

    @Bean
    SaveFailureSwitch saveFailureSwitch() {
      return new SaveFailureSwitch();
    }

    @Bean
    @Primary
    FlakyNotificationRepository flakyNotificationRepository(
        final NotificationMongoAdapter adapter, final SaveFailureSwitch failureSwitch) {
      return new FlakyNotificationRepository(adapter, failureSwitch);
    }

    @Bean
    @Primary
    FlakyEventPublisher flakyEventPublisher(
        final NotificationRabbitPublisher publisher, final SaveFailureSwitch failureSwitch) {
      return new FlakyEventPublisher(publisher, failureSwitch);
    }
  }

  @Autowired private ReactiveMongoTemplate mongoTemplate;
  @Autowired private NotificationMongoAdapter mongoAdapter;
  @Autowired private ChannelCatalogPort channelCatalogPort;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitTopologyProperties topology;
  @Autowired private CountingSender countingSender;
  @Autowired private SaveFailureSwitch failureSwitch;
  @Autowired private RequeuePendingNotificationsUseCase requeueUseCase;

  @BeforeEach
  void setUp() throws InterruptedException {
    failureSwitch.failSaves.set(false);
    failureSwitch.enqueueFailures.set(0);
    awaitRoute();
    while (rabbitTemplate.receive(topology.dlq().queue(), 100) != null) {
      Thread.onSpinWait();
    }
  }

  private void awaitRoute() throws InterruptedException {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final var route =
          channelCatalogPort
              .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("tenant-1"))
              .block();
      if (route != null && route.preferredProvider().equals(ProviderId.of("counting"))) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("El catalogo nunca prefirio el proveedor contador");
  }

  private Notification pending(final String externalId) {
    return mongoAdapter
        .save(
            Notification.accept(
                new NotificationRouting(
                    TenantId.of("tenant-1"),
                    ExternalId.of(externalId + "-" + UUID.randomUUID()),
                    ChannelType.of("EMAIL"),
                    RecipientId.of("recipient-1"),
                    Recipient.of("alice@example.com")),
                new NotificationDetails(
                    NotificationContent.of("Subject", "Body"), Priority.NORMAL)))
        .block();
  }

  private void publishToDispatchQueue(final NotificationId id) {
    rabbitTemplate.convertAndSend(
        topology.dispatch().exchange(),
        topology.dispatch().routingKey(),
        id.value(),
        message -> {
          message.getMessageProperties().setMessageId(UUID.randomUUID().toString());
          return message;
        });
  }

  private Notification awaitStatus(final NotificationId id, final NotificationStatus expected)
      throws InterruptedException {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    Notification current = mongoAdapter.findById(id).block();
    while (Instant.now().isBefore(deadline) && current.status() != expected) {
      Thread.sleep(100);
      current = mongoAdapter.findById(id).block();
    }
    return current;
  }

  private Message awaitDeadLetter(final NotificationId id) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      final Message received = rabbitTemplate.receive(topology.dlq().queue(), 500);
      if (received != null
          && id.value().equals(new String(received.getBody(), StandardCharsets.UTF_8))) {
        return received;
      }
    }
    return null;
  }

  @Test
  void aNewNotificationIsDeliveredExactlyOnce() throws InterruptedException {
    final Notification notification = pending("control");

    publishToDispatchQueue(notification.notificationId());

    final Notification delivered =
        awaitStatus(notification.notificationId(), NotificationStatus.DELIVERED);
    assertEquals(NotificationStatus.DELIVERED, delivered.status());
    assertEquals(1, countingSender.sendsOf(notification.notificationId()));
    assertNull(delivered.dispatchReservedAt());
    assertNull(rabbitTemplate.receive(topology.dlq().queue(), 1_000));
  }

  @Test
  void aHundredRedeliveriesOfTheSameNotificationProduceASingleSendAndAnEmptyDeadLetterQueue()
      throws InterruptedException {
    final Notification notification = pending("redelivery");
    final Notification sentinel = pending("sentinel");

    for (int i = 0; i < 100; i++) {
      publishToDispatchQueue(notification.notificationId());
    }
    publishToDispatchQueue(sentinel.notificationId());

    awaitStatus(sentinel.notificationId(), NotificationStatus.DELIVERED);
    final Notification delivered =
        awaitStatus(notification.notificationId(), NotificationStatus.DELIVERED);

    assertEquals(NotificationStatus.DELIVERED, delivered.status());
    assertEquals(1, delivered.deliveryAttempts().size());
    assertEquals(1, countingSender.sendsOf(notification.notificationId()));
    assertEquals(1, countingSender.sendsOf(sentinel.notificationId()));
    assertNull(rabbitTemplate.receive(topology.dlq().queue(), 1_500));
  }

  @Test
  void aSaveThatFailsAfterAnAcceptedSendDoesNotProduceASecondSendAndLeavesTheCauseInTheDlq()
      throws InterruptedException {
    final Notification notification = pending("save-fails");
    failureSwitch.failSaves.set(true);

    publishToDispatchQueue(notification.notificationId());

    final Message deadLettered = awaitDeadLetter(notification.notificationId());
    failureSwitch.failSaves.set(false);

    assertNotNull(deadLettered, "el mensaje debe quedar en la cola de mensajes muertos");
    final Object cause =
        deadLettered
            .getMessageProperties()
            .getHeaders()
            .get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);
    assertNotNull(cause);
    assertTrue(cause.toString().contains("mongo down"));
    assertEquals(1, countingSender.sendsOf(notification.notificationId()));
    final Notification stored = mongoAdapter.findById(notification.notificationId()).block();
    assertEquals(NotificationStatus.IN_PROCESS, stored.status());
    assertNotNull(stored.dispatchReservedAt());

    publishToDispatchQueue(notification.notificationId());
    final Notification sentinel = pending("save-fails-sentinel");
    publishToDispatchQueue(sentinel.notificationId());
    awaitStatus(sentinel.notificationId(), NotificationStatus.DELIVERED);

    assertEquals(1, countingSender.sendsOf(notification.notificationId()));
    assertEquals(1, countingSender.sendsOf(sentinel.notificationId()));
    assertEquals(
        NotificationStatus.IN_PROCESS,
        mongoAdapter.findById(notification.notificationId()).block().status());
  }

  @Test
  void aFailedEnqueueOfTheRequeuePassIsRetriedInTheNextPass() throws InterruptedException {
    final Notification orphan = pending("orphan");
    mongoTemplate
        .updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id")
                    .is(orphan.notificationId().value())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("pendingSince", Instant.now().minusSeconds(120)),
            co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument.class)
        .block();
    failureSwitch.enqueueFailures.set(1);

    requeueUseCase.requeuePending().block();

    assertEquals(0, countingSender.sendsOf(orphan.notificationId()));
    assertEquals(
        NotificationStatus.PENDING,
        mongoAdapter.findById(orphan.notificationId()).block().status());

    Thread.sleep(1_300);
    requeueUseCase.requeuePending().block();

    final Notification delivered =
        awaitStatus(orphan.notificationId(), NotificationStatus.DELIVERED);
    assertEquals(NotificationStatus.DELIVERED, delivered.status());
    assertEquals(1, countingSender.sendsOf(orphan.notificationId()));
  }

  @Test
  void anInProcessNotificationStuckBeyondTheTimeoutBecomesRecoverable()
      throws InterruptedException {
    final Notification stuck = pending("stuck");
    final Notification fresh = pending("not-stuck");
    mongoAdapter.reserveForDispatch(stuck.notificationId()).block();
    Thread.sleep(2_300);
    mongoAdapter.reserveForDispatch(fresh.notificationId()).block();

    requeueUseCase.requeuePending().block();

    final Notification released = mongoAdapter.findById(stuck.notificationId()).block();
    assertEquals(NotificationStatus.RECOVERABLE, released.status());
    assertNull(released.dispatchReservedAt());
    assertEquals(
        NotificationStatus.IN_PROCESS,
        mongoAdapter.findById(fresh.notificationId()).block().status());
  }
}
