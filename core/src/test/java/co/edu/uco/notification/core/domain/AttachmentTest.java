package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import org.junit.jupiter.api.Test;

class AttachmentTest {

  private static final String URL = "https://files.example.test/secret-token-7788/invoice.pdf";

  @Test
  void keepsTheDeclaredValues() {
    final Attachment attachment = Attachment.of("invoice.pdf", "application/pdf", 1024L, URL);

    assertEquals("invoice.pdf", attachment.fileName());
    assertEquals("application/pdf", attachment.contentType());
    assertEquals(1024L, attachment.sizeBytes());
    assertEquals(URL, attachment.url());
  }

  @Test
  void normalizesTheContentTypeToLowerCaseWithoutParameters() {
    final Attachment attachment =
        Attachment.of("invoice.pdf", " Application/PDF ; charset=binary", 1024L, URL);

    assertEquals("application/pdf", attachment.contentType());
  }

  @Test
  void keepsANullContentTypeAsNull() {
    assertNull(Attachment.of("invoice.pdf", null, 1024L, URL).contentType());
  }

  @Test
  void ofIsEquivalentToTheConstructor() {
    assertEquals(
        new Attachment("invoice.pdf", "application/pdf", 1024L, URL),
        Attachment.of("invoice.pdf", "application/pdf", 1024L, URL));
  }

  @Test
  void toStringNeverContainsTheUrl() {
    final String text = Attachment.of("invoice.pdf", "application/pdf", 1024L, URL).toString();

    assertFalse(text.contains("secret-token-7788"), text);
    assertTrue(text.contains("invoice.pdf"), text);
    assertTrue(text.contains("application/pdf"), text);
    assertTrue(text.contains("1024"), text);
  }
}
