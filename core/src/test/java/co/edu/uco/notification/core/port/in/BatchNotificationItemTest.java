package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import org.junit.jupiter.api.Test;

class BatchNotificationItemTest {

  private static BatchNotificationItem valid() {
    return new BatchNotificationItem(
        ExternalId.of("order-42"),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  @Test
  void preservesGivenValues() {
    final BatchNotificationItem item = valid();

    assertEquals(ExternalId.of("order-42"), item.externalId());
    assertEquals(ChannelType.of("EMAIL"), item.channelType());
    assertEquals(RecipientId.of("recipient-1"), item.recipientId());
    assertEquals(Recipient.of("alice@example.com"), item.recipient());
    assertEquals(NotificationContent.of("Body"), item.content());
    assertEquals(Priority.NORMAL, item.priority());
  }

  @Test
  void rejectsNullExternalId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                null,
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void rejectsNullChannelType() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                ExternalId.of("order-42"),
                null,
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void rejectsNullRecipientId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                null,
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void rejectsNullRecipient() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                null,
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void rejectsNullContent() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                null,
                Priority.NORMAL));
  }

  @Test
  void rejectsNullPriority() {
    assertThrows(
        NullPointerException.class,
        () ->
            new BatchNotificationItem(
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                null));
  }
}
