package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AttachmentTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final byte[] BYTES = "secret-bytes-3344".getBytes(StandardCharsets.UTF_8);
  private static final Sha256Digest SHA = Sha256Digest.of(BYTES);

  private static Attachment embedded(final byte[] bytes) {
    return new Attachment(
        TENANT,
        "invoice.pdf",
        "application/pdf",
        bytes.length,
        SHA,
        new AttachmentSource.EmbeddedContent(bytes));
  }

  @Test
  void keepsItsValuesAndNormalizesTheContentType() {
    final Attachment attachment =
        new Attachment(
            TENANT,
            "invoice.pdf",
            " Application/PDF ; charset=binary",
            17L,
            SHA,
            new AttachmentSource.EmbeddedContent(BYTES));

    assertEquals(TENANT, attachment.tenantId());
    assertEquals("invoice.pdf", attachment.fileName());
    assertEquals("application/pdf", attachment.contentType());
    assertEquals(17L, attachment.sizeBytes());
    assertEquals(SHA, attachment.sha256());
  }

  @Test
  void requiresTenantHashAndSource() {
    final AttachmentSource source = new AttachmentSource.EmbeddedContent(BYTES);
    assertThrows(
        NullPointerException.class,
        () -> new Attachment(null, "a.pdf", "application/pdf", 1L, SHA, source));
    assertThrows(
        NullPointerException.class,
        () -> new Attachment(TENANT, "a.pdf", "application/pdf", 1L, null, source));
    assertThrows(
        NullPointerException.class,
        () -> new Attachment(TENANT, "a.pdf", "application/pdf", 1L, SHA, null));
  }

  @Test
  void embeddedContentCopiesTheBytesOnTheWayInAndOut() {
    final byte[] original = BYTES.clone();
    final AttachmentSource.EmbeddedContent content = new AttachmentSource.EmbeddedContent(original);
    original[0] = 'X';
    final byte[] read = content.bytes();
    read[1] = 'Y';

    assertArrayEquals(BYTES, content.bytes());
  }

  @Test
  void embeddedContentComparesByValue() {
    assertEquals(
        new AttachmentSource.EmbeddedContent(BYTES.clone()),
        new AttachmentSource.EmbeddedContent(BYTES.clone()));
    assertEquals(
        new AttachmentSource.EmbeddedContent(BYTES.clone()).hashCode(),
        new AttachmentSource.EmbeddedContent(BYTES.clone()).hashCode());
    assertNotEquals(
        new AttachmentSource.EmbeddedContent(BYTES),
        new AttachmentSource.EmbeddedContent(new byte[1]));
    assertEquals(embedded(BYTES.clone()), embedded(BYTES.clone()));
  }

  @Test
  void storedObjectKeepsItsUploadAndKey() {
    final UploadId uploadId = UploadId.newId();
    final AttachmentSource.StoredObject stored =
        new AttachmentSource.StoredObject(uploadId, "tenants/tenant-1/clean/" + uploadId.value());

    assertEquals(uploadId, stored.uploadId());
    assertEquals("tenants/tenant-1/clean/" + uploadId.value(), stored.objectKey());
  }

  @Test
  void toStringNeverContainsTheBytesNorTheObjectKey() {
    final String embeddedText = embedded(BYTES).toString();
    final String storedText =
        new Attachment(
                TENANT,
                "big.pdf",
                "application/pdf",
                2_000_000L,
                SHA,
                new AttachmentSource.StoredObject(UploadId.of("u-1"), "tenants/t/clean/key-7781"))
            .toString();

    assertFalse(embeddedText.contains("secret-bytes-3344"), embeddedText);
    assertFalse(embeddedText.contains("[B@"), embeddedText);
    assertTrue(embeddedText.contains("invoice.pdf"), embeddedText);
    assertTrue(embeddedText.contains(SHA.hex()), embeddedText);
    assertFalse(storedText.contains("key-7781"), storedText);
    assertTrue(storedText.contains("2000000"), storedText);
  }
}
