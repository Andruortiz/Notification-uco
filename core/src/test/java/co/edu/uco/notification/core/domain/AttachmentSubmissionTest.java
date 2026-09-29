package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import org.junit.jupiter.api.Test;

class AttachmentSubmissionTest {

  private static final String CONTENT = "c2VjcmV0LWNvbnRlbnQtNjY3Nw==";
  private static final String URL =
      "http://minio.test/bucket/tenants/t/uploads/u?X-Amz-Signature=sig-5511";

  @Test
  void keepsTheDeclaredValuesAndNormalizesTheContentType() {
    final AttachmentSubmission submission =
        AttachmentSubmission.embedded(
            "invoice.pdf", " Application/PDF ; charset=binary", 10L, CONTENT);

    assertEquals("invoice.pdf", submission.fileName());
    assertEquals("application/pdf", submission.contentType());
    assertEquals(10L, submission.sizeBytes());
    assertEquals(CONTENT, submission.content());
    assertNull(submission.url());
  }

  @Test
  void keepsANullContentTypeAsNull() {
    assertNull(new AttachmentSubmission("a.pdf", null, 1L, CONTENT, null).contentType());
  }

  @Test
  void tellsWhetherItIsEmbeddedOrAReference() {
    final AttachmentSubmission embedded =
        AttachmentSubmission.embedded("a.pdf", "application/pdf", 1L, CONTENT);
    final AttachmentSubmission reference =
        AttachmentSubmission.reference("a.pdf", "application/pdf", 2_000_000L, URL);
    final AttachmentSubmission neither =
        new AttachmentSubmission("a.pdf", "application/pdf", 1L, null, null);

    assertTrue(embedded.isEmbedded());
    assertFalse(embedded.isReference());
    assertTrue(reference.isReference());
    assertFalse(reference.isEmbedded());
    assertFalse(neither.isEmbedded());
    assertFalse(neither.isReference());
  }

  @Test
  void toStringNeverContainsTheContentNorTheUrl() {
    final String text =
        new AttachmentSubmission("a.pdf", "application/pdf", 7L, CONTENT, URL).toString();

    assertFalse(text.contains(CONTENT), text);
    assertFalse(text.contains("sig-5511"), text);
    assertTrue(text.contains("a.pdf"), text);
    assertTrue(text.contains("application/pdf"), text);
    assertTrue(text.contains("7"), text);
  }
}
