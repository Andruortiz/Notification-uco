package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.ProviderId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationStatusViewTest {

  @Test
  void preservesGivenValues() {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();

    final NotificationStatusView view =
        new NotificationStatusView(
            id, NotificationStatus.DELIVERED, ChannelType.of("EMAIL"), ProviderId.of("brevo"), now);

    assertEquals(id, view.notificationId());
    assertEquals(NotificationStatus.DELIVERED, view.status());
    assertEquals(ChannelType.of("EMAIL"), view.channelType());
    assertEquals(ProviderId.of("brevo"), view.lastProviderId());
    assertEquals(now, view.lastUpdatedAt());
  }

  @Test
  void allowsNullLastProviderId() {
    final NotificationStatusView view =
        new NotificationStatusView(
            NotificationId.newId(),
            NotificationStatus.PENDING,
            ChannelType.of("EMAIL"),
            null,
            Instant.now());

    assertNull(view.lastProviderId());
  }

  @Test
  void rejectsNullNotificationId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new NotificationStatusView(
                null, NotificationStatus.PENDING, ChannelType.of("EMAIL"), null, Instant.now()));
  }

  @Test
  void rejectsNullStatus() {
    assertThrows(
        NullPointerException.class,
        () ->
            new NotificationStatusView(
                NotificationId.newId(), null, ChannelType.of("EMAIL"), null, Instant.now()));
  }

  @Test
  void rejectsNullChannelType() {
    assertThrows(
        NullPointerException.class,
        () ->
            new NotificationStatusView(
                NotificationId.newId(), NotificationStatus.PENDING, null, null, Instant.now()));
  }

  @Test
  void rejectsNullLastUpdatedAt() {
    assertThrows(
        NullPointerException.class,
        () ->
            new NotificationStatusView(
                NotificationId.newId(),
                NotificationStatus.PENDING,
                ChannelType.of("EMAIL"),
                null,
                null));
  }
}
