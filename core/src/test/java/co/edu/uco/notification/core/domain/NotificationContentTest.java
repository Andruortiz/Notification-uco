package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.ProviderTemplateReference;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationContentTest {

  @Test
  void ofWithSubjectAndBodyPreservesBoth() {
    final NotificationContent content = NotificationContent.of("Subject", "Body");
    assertEquals("Subject", content.subject());
    assertEquals("Body", content.body());
  }

  @Test
  void ofWithOnlyBodyLeavesSubjectNull() {
    final NotificationContent content = NotificationContent.of("Body");
    assertNull(content.subject());
    assertEquals("Body", content.body());
  }

  @Test
  void rejectsBlankBody() {
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of("Subject", "   "));
  }

  @Test
  void rejectsNullBody() {
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(null));
  }

  @Test
  void acceptsBodyAtMaxLength() {
    final String body = "a".repeat(NotificationContent.MAX_LENGTH);
    assertEquals(NotificationContent.MAX_LENGTH, NotificationContent.of(body).body().length());
  }

  @Test
  void rejectsBodyOverMaxLength() {
    final String body = "a".repeat(NotificationContent.MAX_LENGTH + 1);
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(body));
  }

  @Test
  void rejectsSubjectPlusBodyOverMaxLength() {
    final String subject = "a".repeat(NotificationContent.MAX_LENGTH);
    assertThrows(IllegalArgumentException.class, () -> NotificationContent.of(subject, "b"));
  }

  private static final Attachment FIRST = attachment("a.pdf", "application/pdf", "first");
  private static final Attachment SECOND = attachment("b.png", "image/png", "second");

  private static Attachment attachment(
      final String fileName, final String contentType, final String text) {
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return new Attachment(
        TenantId.of("tenant-1"),
        fileName,
        contentType,
        bytes.length,
        Sha256Digest.of(bytes),
        new AttachmentSource.EmbeddedContent(bytes));
  }

  @Test
  void contentWithoutAttachmentsHasAnEmptyList() {
    assertEquals(List.of(), NotificationContent.of("Subject", "Body").attachments());
    assertEquals(List.of(), NotificationContent.of("Body").attachments());
    assertEquals(List.of(), new NotificationContent("Subject", "Body").attachments());
    assertFalse(NotificationContent.of("Body").hasAttachments());
  }

  @Test
  void nullAttachmentsBecomeAnEmptyList() {
    final NotificationContent content = NotificationContent.of("Subject", "Body", null);

    assertEquals(List.of(), content.attachments());
    assertFalse(content.hasAttachments());
  }

  @Test
  void keepsAttachmentsInOrderAsAnImmutableCopy() {
    final List<Attachment> attachments = new ArrayList<>(List.of(FIRST, SECOND));
    final NotificationContent content = NotificationContent.of("Subject", "Body", attachments);
    attachments.clear();

    assertEquals(List.of(FIRST, SECOND), content.attachments());
    assertTrue(content.hasAttachments());
    assertThrows(UnsupportedOperationException.class, () -> content.attachments().add(FIRST));
  }

  @Test
  void rejectsANullAttachment() {
    final List<Attachment> attachments = Arrays.asList(FIRST, null);

    assertThrows(
        NullPointerException.class, () -> NotificationContent.of("Subject", "Body", attachments));
  }

  @Test
  void maxLengthIgnoresAttachments() {
    final String body = "a".repeat(NotificationContent.MAX_LENGTH);

    assertEquals(
        NotificationContent.MAX_LENGTH,
        NotificationContent.of(null, body, List.of(FIRST)).body().length());
  }

  @Test
  void aProviderTemplateReplacesTheBody() {
    final ProviderTemplateReference template =
        ProviderTemplateReference.of("HX0001", Map.of("1", "Alice"));

    final NotificationContent content = NotificationContent.ofProviderTemplate(null, template);

    assertNull(content.body());
    assertTrue(content.hasProviderTemplate());
    assertEquals(template, content.providerTemplate());
  }

  @Test
  void rejectsBodyTogetherWithAProviderTemplate() {
    final ProviderTemplateReference template = ProviderTemplateReference.of("HX0001", null);

    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationContent.of(null, "Body", List.of(), template));
  }

  @Test
  void rejectsContentWithNeitherBodyNorProviderTemplate() {
    assertThrows(
        IllegalArgumentException.class, () -> NotificationContent.of(null, null, List.of(), null));
  }

  @Test
  void aBlankBodyAlongsideAProviderTemplateIsTreatedAsAbsent() {
    final NotificationContent content =
        NotificationContent.of(null, "  ", List.of(), ProviderTemplateReference.of("HX0001", null));

    assertNull(content.body());
  }

  @Test
  void contentWithoutATemplateReportsNoProviderTemplate() {
    assertFalse(NotificationContent.of("Body").hasProviderTemplate());
  }
}
