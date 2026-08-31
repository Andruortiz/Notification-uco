package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.DomainEventRecorder;
import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationDelivered;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
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
  private final Recipient recipient;
  private final NotificationContent content;
  private final Priority priority;
  private final Instant acceptedAt;
  private final DomainEventRecorder eventRecorder = new DomainEventRecorder();
  private final List<DeliveryAttempt> deliveryAttempts = new ArrayList<>();

  private NotificationStatus status;

  private Notification(
      final NotificationId notificationId,
      final TenantId tenantId,
      final ExternalId externalId,
      final ChannelType channelType,
      final Recipient recipient,
      final NotificationContent content,
      final Priority priority,
      final Instant acceptedAt) {
    this.notificationId = notificationId;
    this.tenantId = tenantId;
    this.externalId = externalId;
    this.channelType = channelType;
    this.recipient = recipient;
    this.content = content;
    this.priority = priority;
    this.acceptedAt = acceptedAt;
    this.status = NotificationStatus.PENDING;
  }

  public static Notification accept(
      final TenantId tenantId,
      final ExternalId externalId,
      final ChannelType channelType,
      final Recipient recipient,
      final NotificationContent content,
      final Priority priority) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    Preconditions.requireNonNull(content, "content must not be null");
    Preconditions.requireNonNull(priority, "priority must not be null");

    final Notification notification =
        new Notification(
            NotificationId.newId(),
            tenantId,
            externalId,
            channelType,
            recipient,
            content,
            priority,
            Instant.now());
    notification.eventRecorder.record(
        new NotificationAccepted(notification.notificationId, notification.acceptedAt));
    return notification;
  }

  public static Notification reconstitute(
      final NotificationId notificationId,
      final TenantId tenantId,
      final ExternalId externalId,
      final ChannelType channelType,
      final Recipient recipient,
      final NotificationContent content,
      final Priority priority,
      final NotificationStatus status,
      final Instant acceptedAt,
      final List<DeliveryAttempt> deliveryAttempts) {
    Preconditions.requireNonNull(notificationId, "notificationId must not be null");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(externalId, "externalId must not be null");
    Preconditions.requireNonNull(channelType, "channelType must not be null");
    Preconditions.requireNonNull(recipient, "recipient must not be null");
    Preconditions.requireNonNull(content, "content must not be null");
    Preconditions.requireNonNull(priority, "priority must not be null");
    Preconditions.requireNonNull(status, "status must not be null");
    Preconditions.requireNonNull(acceptedAt, "acceptedAt must not be null");
    Preconditions.requireNonNull(deliveryAttempts, "deliveryAttempts must not be null");

    final Notification notification =
        new Notification(
            notificationId,
            tenantId,
            externalId,
            channelType,
            recipient,
            content,
            priority,
            acceptedAt);
    notification.status = status;
    notification.deliveryAttempts.addAll(deliveryAttempts);
    return notification;
  }

  public void markQueued() {
    transitionTo(NotificationStatus.IN_PROCESS);
    eventRecorder.record(new NotificationQueued(notificationId, Instant.now()));
  }

  public void markDelivered(final AttemptOrigin origin) {
    transitionTo(NotificationStatus.DELIVERED);
    final Instant now = Instant.now();
    deliveryAttempts.add(DeliveryAttempt.of(now, AttemptResult.ACCEPTED, origin));
    eventRecorder.record(new NotificationDelivered(notificationId, now));
  }

  public void markRecoverable(final AttemptOrigin origin) {
    transitionTo(NotificationStatus.RECOVERABLE);
    deliveryAttempts.add(
        DeliveryAttempt.of(Instant.now(), AttemptResult.RECOVERABLE_FAILURE, origin));
  }

  public void markFailed(final AttemptOrigin origin) {
    transitionTo(NotificationStatus.FAILED);
    final Instant now = Instant.now();
    deliveryAttempts.add(DeliveryAttempt.of(now, AttemptResult.PERMANENT_FAILURE, origin));
    eventRecorder.record(new NotificationFailed(notificationId, now));
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
}
