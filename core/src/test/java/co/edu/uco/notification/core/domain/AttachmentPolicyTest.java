package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentPolicyTest {

  private static final String SECRET = "secret-token-4455";
  private static final String URL = "https://files.example.test/" + SECRET + "/invoice.pdf";

  private static Attachment valid() {
    return Attachment.of("invoice.pdf", "application/pdf", 1024L, URL);
  }

  private static Attachment withName(final String fileName) {
    return Attachment.of(fileName, "application/pdf", 1024L, URL);
  }

  private static Attachment withType(final String contentType) {
    return Attachment.of("invoice.pdf", contentType, 1024L, URL);
  }

  private static Attachment withSize(final Long sizeBytes) {
    return Attachment.of("invoice.pdf", "application/pdf", sizeBytes, URL);
  }

  private static Attachment withUrl(final String url) {
    return Attachment.of("invoice.pdf", "application/pdf", 1024L, url);
  }

  private static InvalidAttachmentException rejected(final Attachment... attachments) {
    return assertThrows(
        InvalidAttachmentException.class, () -> AttachmentPolicy.validate(List.of(attachments)));
  }

  @Test
  void acceptsAnEmptyList() {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of()));
  }

  @Test
  void acceptsTheMaximumNumberOfAttachments() {
    assertDoesNotThrow(
        () ->
            AttachmentPolicy.validate(
                Collections.nCopies(AttachmentPolicy.MAX_ATTACHMENTS, valid())));
  }

  @Test
  void rejectsMoreThanTheMaximumNumberOfAttachments() {
    final InvalidAttachmentException exception =
        assertThrows(
            InvalidAttachmentException.class,
            () ->
                AttachmentPolicy.validate(
                    Collections.nCopies(AttachmentPolicy.MAX_ATTACHMENTS + 1, valid())));

    assertEquals(-1, exception.position());
    assertEquals("attachments: at most 5 attachments are allowed", exception.getMessage());
  }

  @Test
  void acceptsSizeAtBothInclusiveLimits() {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withSize(1L))));
    assertDoesNotThrow(
        () -> AttachmentPolicy.validate(List.of(withSize(AttachmentPolicy.MAX_SIZE_BYTES))));
  }

  @Test
  void rejectsSizeAboveTheGlobalLimit() {
    final InvalidAttachmentException exception =
        rejected(withSize(AttachmentPolicy.MAX_SIZE_BYTES + 1));

    assertEquals(
        "attachments[0]: sizeBytes must not exceed 10485760 (invoice.pdf)", exception.getMessage());
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -5L})
  void rejectsNonPositiveSize(final long sizeBytes) {
    assertEquals(
        "attachments[0]: sizeBytes must be at least 1 (invoice.pdf)",
        rejected(withSize(sizeBytes)).getMessage());
  }

  @Test
  void rejectsMissingSize() {
    assertEquals(
        "attachments[0]: sizeBytes is required (invoice.pdf)",
        rejected(withSize(null)).getMessage());
  }

  @Test
  void acceptsNameAtMaximumLength() {
    assertDoesNotThrow(
        () ->
            AttachmentPolicy.validate(
                List.of(withName("a".repeat(AttachmentPolicy.MAX_FILE_NAME_LENGTH)))));
  }

  @Test
  void rejectsNameAboveMaximumLength() {
    final InvalidAttachmentException exception =
        rejected(withName("a".repeat(AttachmentPolicy.MAX_FILE_NAME_LENGTH + 1)));

    assertEquals("attachments[0]: fileName must not exceed 255 characters", exception.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsMissingName(final String fileName) {
    assertEquals("attachments[0]: fileName is required", rejected(withName(fileName)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"../etc/passwd", "dir\\file.pdf", "line\r\nbreak.pdf", "tab\tname.pdf"})
  void rejectsNameWithPathSeparatorsOrControlCharacters(final String fileName) {
    assertEquals(
        "attachments[0]: fileName must not contain path separators or control characters",
        rejected(withName(fileName)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {".", ".."})
  void rejectsDotNames(final String fileName) {
    assertEquals(
        "attachments[0]: fileName must not be . or ..", rejected(withName(fileName)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "text/plain",
        "text/csv",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Application/PDF; charset=binary"
      })
  void acceptsEveryGloballyAllowedType(final String contentType) {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withType(contentType))));
  }

  @Test
  void rejectsATypeOutsideTheGlobalList() {
    assertEquals(
        "attachments[0]: contentType application/x-msdownload is not allowed (invoice.pdf)",
        rejected(withType("application/x-msdownload")).getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void rejectsMissingType(final String contentType) {
    assertEquals(
        "attachments[0]: contentType is required (invoice.pdf)",
        rejected(withType(contentType)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "HTTPS://files.example.test/a.pdf",
        "https://files.example.test:8443/a.pdf?token=abc#page=2"
      })
  void acceptsAbsoluteHttpsUrls(final String url) {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withUrl(url))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://files.example.test/" + SECRET + ".pdf",
        "/files/" + SECRET + ".pdf",
        "https:///" + SECRET + ".pdf",
        "https://user:" + SECRET + "@files.example.test/a.pdf",
        "https://files.example.test/" + SECRET + " a.pdf",
        "ftp://files.example.test/" + SECRET + ".pdf"
      })
  void rejectsUrlsThatAreNotAbsoluteHttpsWithoutCredentials(final String url) {
    final InvalidAttachmentException exception = rejected(withUrl(url));

    assertEquals(
        "attachments[0]: url must be an absolute https URL with a host and without user"
            + " information (invoice.pdf)",
        exception.getMessage());
    assertFalse(exception.getMessage().contains(SECRET));
  }

  @Test
  void acceptsUrlAtMaximumLength() {
    final String prefix = "https://files.example.test/";
    final String url = prefix + "a".repeat(AttachmentPolicy.MAX_URL_LENGTH - prefix.length());

    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withUrl(url))));
  }

  @Test
  void rejectsUrlAboveMaximumLength() {
    final String prefix = "https://files.example.test/" + SECRET;
    final String url = prefix + "a".repeat(AttachmentPolicy.MAX_URL_LENGTH + 1 - prefix.length());

    final InvalidAttachmentException exception = rejected(withUrl(url));

    assertEquals(
        "attachments[0]: url must not exceed 2048 characters (invoice.pdf)",
        exception.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void rejectsMissingUrl(final String url) {
    assertEquals(
        "attachments[0]: url is required (invoice.pdf)", rejected(withUrl(url)).getMessage());
  }

  @Test
  void reportsThePositionOfTheFirstInvalidAttachment() {
    final InvalidAttachmentException exception =
        rejected(valid(), withType("application/zip"), withSize(0L));

    assertEquals(1, exception.position());
    assertTrue(exception.getMessage().startsWith("attachments[1]: "), exception.getMessage());
  }

  @Test
  void neverIncludesTheUrlInAnyMessage() {
    final InvalidAttachmentException exception = rejected(withSize(0L));

    assertFalse(exception.getMessage().contains(SECRET));
    assertFalse(exception.getMessage().contains("https://"));
  }
}
