package co.edu.uco.notification.core.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class NotificationSearchCriteriaTest {

  private static Notification notification(final TenantId tenantId, final RecipientId recipientId) {
    return Notification.accept(
        new NotificationRouting(
            tenantId,
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            recipientId,
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL));
  }

  private static NotificationSearchCriteria criteria(
      final TenantId tenantId,
      final RecipientId recipientId,
      final ChannelType channelType,
      final NotificationStatus status,
      final Instant from,
      final Instant to) {
    return new NotificationSearchCriteria(
        tenantId, recipientId, channelType, status, from, to, 50, 0);
  }

  @Test
  void matchesWhenNoOptionalFilterIsPresent() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, null, null, null);

    assertTrue(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenTenantDiffers() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-2"), null, null, null, null, null);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenRecipientIdDiffers() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), RecipientId.of("r2"), null, null, null, null);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void matchesWhenRecipientIdEquals() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), RecipientId.of("r1"), null, null, null, null);

    assertTrue(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenChannelTypeDiffers() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, ChannelType.of("SMS"), null, null, null);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenStatusDiffers() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, NotificationStatus.DELIVERED, null, null);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void matchesWhenStatusEquals() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, NotificationStatus.PENDING, null, null);

    assertTrue(criteria.matches(notification));
  }

  @Test
  void matchesWhenAcceptedAtIsWithinRangeInclusive() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final Instant from = notification.acceptedAt();
    final Instant to = notification.acceptedAt();
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, null, from, to);

    assertTrue(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenAcceptedAtIsBeforeFrom() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final Instant from = notification.acceptedAt().plus(1, ChronoUnit.SECONDS);
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, null, from, null);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void doesNotMatchWhenAcceptedAtIsAfterTo() {
    final Notification notification = notification(TenantId.of("tenant-1"), RecipientId.of("r1"));
    final Instant to = notification.acceptedAt().minus(1, ChronoUnit.SECONDS);
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, null, null, to);

    assertFalse(criteria.matches(notification));
  }

  @Test
  void rejectsNullNotification() {
    final NotificationSearchCriteria criteria =
        criteria(TenantId.of("tenant-1"), null, null, null, null, null);

    assertThrows(NullPointerException.class, () -> criteria.matches(null));
  }
}
