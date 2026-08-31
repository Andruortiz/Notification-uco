package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import org.junit.jupiter.api.Test;

class SendNotificationCommandTest {

  private static SendNotificationCommand valid() {
    return new SendNotificationCommand(
        TenantId.of("tenant-1"),
        ExternalId.of("order-42"),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  @Test
  void preservesGivenValues() {
    final SendNotificationCommand command = valid();

    assertEquals(TenantId.of("tenant-1"), command.tenantId());
    assertEquals(ExternalId.of("order-42"), command.externalId());
    assertEquals(ChannelType.of("EMAIL"), command.channelType());
    assertEquals(RecipientId.of("recipient-1"), command.recipientId());
    assertEquals(Recipient.of("alice@example.com"), command.recipient());
    assertEquals(NotificationContent.of("Body"), command.content());
    assertEquals(Priority.NORMAL, command.priority());
  }

  @Test
  void rejectsNullTenantId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationCommand(
                null,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                Priority.NORMAL));
  }

  @Test
  void rejectsNullExternalId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
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
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
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
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
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
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
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
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
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
            new SendNotificationCommand(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com"),
                NotificationContent.of("Body"),
                null));
  }
}
