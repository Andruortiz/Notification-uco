package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.DomainEventRecorder;
import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationDelivered;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.domain.policy.StatusTransitionPolicy;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.InvalidStatusTransitionException;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Notification {

  private final NotificationId notificationId;
  private final TenantId tenantId;
  private final ExternalId externalId;
  private final ChannelType channelType;
  private final RecipientId recipientId;
  private final Recipient recipient;
  private final NotificationContent content;
  private final Priority priority;
  private final Instant acceptedAt;
  private final DomainEventRecorder eventRecorder = new DomainEventRecorder();
  private final List<DeliveryAttempt> deliveryAttempts = new ArrayList<>();

  private NotificationStatus status;
  private final Long version;

  private Notification(
      final NotificationId notificationId,
      final NotificationRouting routing,
      final NotificationDetails details,
      final NotificationMetadata metadata) {
    this.notificationId = notificationId;
    this.tenantId = routing.tenantId();
    this.externalId = routing.externalId();
    this.channelType = routing.channelType();
    this.recipientId = routing.recipientId();
    this.recipient = routing.recipient();
    this.content = details.content();
    this.priority = details.priority();
    this.acceptedAt = metadata.acceptedAt();
    this.status = NotificationStatus.PENDING;
    this.version = metadata.version();
  }

  public static Notification accept(
      final NotificationRouting routing, final NotificationDetails details) {
    Preconditions.requireNonNull(routing, "routing must not be null");
    Preconditions.requireNonNull(details, "details must not be null");
    final Notification notification =
        new Notification(
            NotificationId.newId(),
            routing,
            details,
            new NotificationMetadata(Instant.now(), null));
    notification.eventRecorder.registerEvent(
        new NotificationAccepted(notification.notificationId, notification.acceptedAt));
    return notification;
  }

  public static Notification reconstitute(
      final NotificationId notificationId,
      final NotificationRouting routing,
      final NotificationDetails details,
      final NotificationStatus status,
      final NotificationMetadata metadata,
      final List<DeliveryAttempt> deliveryAttempts) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(routing, "routing must not be null");
    Preconditions.requireNonNull(details, "details must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(metadata, "metadata must not be null");
    Preconditions.requireNonNull(deliveryAttempts, "deliveryAttempts must not be null");

    final Notification notification = new Notification(notificationId, routing, details, metadata);
    notification.status = status;
    notification.deliveryAttempts.addAll(deliveryAttempts);
    return notification;
  }

  public void markQueued() {
    transitionTo(NotificationStatus.IN_PROCESS);
    eventRecorder.registerEvent(new NotificationQueued(notificationId, Instant.now()));
  }

  public void markDelivered(final AttemptOrigin origin, final ProviderId providerId) {
    transitionTo(NotificationStatus.DELIVERED);
    final Instant now = Instant.now();
    deliveryAttempts.add(DeliveryAttempt.of(now, AttemptResult.ACCEPTED, origin, providerId));
    eventRecorder.registerEvent(new NotificationDelivered(notificationId, now));
  }

  public void markRecoverable(final AttemptOrigin origin, final ProviderId providerId) {
    transitionTo(NotificationStatus.RECOVERABLE);
    deliveryAttempts.add(
        DeliveryAttempt.of(Instant.now(), AttemptResult.RECOVERABLE_FAILURE, origin, providerId));
  }

  public void markRetriesExhausted(final AttemptOrigin origin, final ProviderId providerId) {
    transitionTo(NotificationStatus.FAILED);
    final Instant now = Instant.now();
    deliveryAttempts.add(
        DeliveryAttempt.of(now, AttemptResult.RECOVERABLE_FAILURE, origin, providerId));
    eventRecorder.registerEvent(new NotificationFailed(notificationId, now));
  }

  public void markFailed(final AttemptOrigin origin, final ProviderId providerId) {
    transitionTo(NotificationStatus.FAILED);
    final Instant now = Instant.now();
    deliveryAttempts.add(
        DeliveryAttempt.of(now, AttemptResult.PERMANENT_FAILURE, origin, providerId));
    eventRecorder.registerEvent(new NotificationFailed(notificationId, now));
  }

  public void requeue() {
    transitionTo(NotificationStatus.PENDING);
  }

  public void discard() {
    transitionTo(NotificationStatus.DISCARDED);
  }

  private void transitionTo(final NotificationStatus target) {
    if (!StatusTransitionPolicy.canTransition(status, target)) {
      throw new InvalidStatusTransitionException(status, target);
    }
    status = target;
  }

  public List<DomainEvent> pullEvents() {
    return eventRecorder.pullEvents();
  }

  public NotificationId notificationId() {
    return notificationId;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public ExternalId externalId() {
    return externalId;
  }

  public ChannelType channelType() {
    return channelType;
  }

  public RecipientId recipientId() {
    return recipientId;
  }

  public Recipient recipient() {
    return recipient;
  }

  public NotificationContent content() {
    return content;
  }

  public Priority priority() {
    return priority;
  }

  public NotificationStatus status() {
    return status;
  }

  public Instant acceptedAt() {
    return acceptedAt;
  }

  public List<DeliveryAttempt> deliveryAttempts() {
    return List.copyOf(deliveryAttempts);
  }

  public Long version() {
    return version;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Notification that)) {
      return false;
    }
    return notificationId.equals(that.notificationId);
  }

  @Override
  public int hashCode() {
    return notificationId.hashCode();
  }
}
