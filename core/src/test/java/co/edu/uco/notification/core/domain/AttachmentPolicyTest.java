package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.policy.AttachmentPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentPolicyTest {

  private static final String SECRET = "secret-token-4455";
  private static final String URL =
      "http://minio.test/bucket/tenants/t/uploads/u?X-Amz-Signature=" + SECRET;
  private static final byte[] BYTES = ("pdf-bytes-" + SECRET).getBytes(StandardCharsets.UTF_8);
  private static final String CONTENT = Base64.getEncoder().encodeToString(BYTES);
  private static final long LARGE = 2_000_000L;

  private static AttachmentSubmission valid() {
    return AttachmentSubmission.embedded(
        "invoice.pdf", "application/pdf", (long) BYTES.length, CONTENT);
  }

  private static AttachmentSubmission withName(final String fileName) {
    return AttachmentSubmission.embedded(fileName, "application/pdf", (long) BYTES.length, CONTENT);
  }

  private static AttachmentSubmission withType(final String contentType) {
    return AttachmentSubmission.embedded("invoice.pdf", contentType, (long) BYTES.length, CONTENT);
  }

  private static AttachmentSubmission reference(final Long sizeBytes, final String url) {
    return AttachmentSubmission.reference("big.pdf", "application/pdf", sizeBytes, url);
  }

  private static AttachmentSubmission embeddedOfSize(final int size) {
    return AttachmentSubmission.embedded(
        "blob.pdf",
        "application/pdf",
        (long) size,
        Base64.getEncoder().encodeToString(new byte[size]));
  }

  private static InvalidAttachmentException rejected(final AttachmentSubmission... submissions) {
    return assertThrows(
        InvalidAttachmentException.class, () -> AttachmentPolicy.validate(List.of(submissions)));
  }

  private static void assertRejectedWith(
      final String expectedFragment, final AttachmentSubmission submission) {
    final InvalidAttachmentException exception = rejected(submission);
    assertTrue(exception.getMessage().contains(expectedFragment), exception.getMessage());
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
    assertFalse(exception.getMessage().contains(CONTENT), exception.getMessage());
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
  void rejectsMoreThanTheMaximumNumberOfAttachmentsWithoutPosition() {
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
  void acceptsAFileNameOfTheMaximumLength() {
    assertDoesNotThrow(
        () -> AttachmentPolicy.validate(List.of(withName("a".repeat(251) + ".pdf"))));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", ".", "..", "dir/invoice.pdf", "dir\\invoice.pdf", "in\nvoice.pdf"})
  void rejectsAnInvalidFileNameWithoutEchoingIt(final String fileName) {
    final InvalidAttachmentException exception = rejected(withName(fileName));

    assertEquals(0, exception.position());
    assertTrue(
        exception.getMessage().startsWith("attachments[0]: fileName"), exception.getMessage());
    assertFalse(exception.getMessage().endsWith(")"), exception.getMessage());
  }

  @Test
  void rejectsAFileNameOverTheMaximumLength() {
    assertRejectedWith("fileName must not exceed 255", withName("a".repeat(252) + ".pdf"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".exe", ".msi", ".bat", ".cmd", ".com", ".scr", ".pif", ".vbs", ".vbe", ".js", ".jse",
        ".wsf", ".wsh", ".ps1", ".hta", ".cpl", ".msc", ".reg", ".lnk", ".jar", ".dll", ".sh",
        ".apk", ".app", ".gadget", ".com.pif", ".msix", ".war"
      })
  void rejectsEveryForbiddenExtension(final String extension) {
    final InvalidAttachmentException exception = rejected(withName("factura" + extension));

    assertEquals("attachments[0]: fileName extension is not allowed", exception.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "factura.pdf.exe",
        "FACTURA.EXE",
        "factura.exe.",
        "factura.exe ",
        "factura.Exe. . "
      })
  void rejectsForbiddenExtensionsRegardlessOfCaseAndTrailingDotsOrSpaces(final String fileName) {
    assertRejectedWith("fileName extension is not allowed", withName(fileName));
  }

  @ParameterizedTest
  @ValueSource(strings = {"factura.exe.pdf", "report.json", "notes.jsx.txt", "comic.pdf"})
  void acceptsNamesThatOnlyContainAForbiddenExtensionInTheMiddleOrAsAPrefix(final String name) {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withName(name))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/pdf",
        "image/png",
        "image/jpeg",
        "text/plain",
        "text/csv",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Application/PDF; charset=binary"
      })
  void acceptsEveryTypeOfTheWhiteList(final String contentType) {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(withType(contentType))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "image/gif",
        "image/webp",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/x-msdownload",
        "text/html"
      })
  void rejectsTypesOutsideTheWhiteListNamingTheFile(final String contentType) {
    final InvalidAttachmentException exception = rejected(withType(contentType));

    assertEquals(
        "attachments[0]: contentType " + contentType + " is not allowed (invoice.pdf)",
        exception.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void rejectsAMissingContentType(final String contentType) {
    assertRejectedWith("contentType is required", withType(contentType));
  }

  @Test
  void acceptsSizesAtTheInclusiveLimits() {
    assertDoesNotThrow(() -> AttachmentPolicy.validate(List.of(embeddedOfSize(1))));
    assertDoesNotThrow(
        () -> AttachmentPolicy.validate(List.of(reference(AttachmentPolicy.MAX_SIZE_BYTES, URL))));
  }

  @Test
  void rejectsInvalidSizes() {
    assertRejectedWith("sizeBytes is required", reference(null, URL));
    assertRejectedWith("sizeBytes must be at least 1", reference(0L, URL));
    assertRejectedWith("sizeBytes must be at least 1", reference(-5L, URL));
    assertRejectedWith(
        "sizeBytes must not exceed 10485760", reference(AttachmentPolicy.MAX_SIZE_BYTES + 1, URL));
  }

  @Test
  void rejectsContentAndUrlTogetherOrNeither() {
    assertRejectedWith(
        "exactly one of content or url is required",
        new AttachmentSubmission("a.pdf", "application/pdf", (long) BYTES.length, CONTENT, URL));
    assertRejectedWith(
        "exactly one of content or url is required",
        new AttachmentSubmission("a.pdf", "application/pdf", (long) BYTES.length, null, null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not base64!",
        "data:application/pdf;base64,cGRm",
        "cGRm\ncGRm",
        "cGRmMQ",
        "cGRm====",
        ""
      })
  void rejectsContentThatIsNotStrictBase64(final String content) {
    assertRejectedWith(
        "content must be valid Base64",
        AttachmentSubmission.embedded("a.pdf", "application/pdf", 3L, content));
  }

  @Test
  void acceptsAnEmbeddedFileOfExactlyOneMegabyte() {
    assertDoesNotThrow(
        () ->
            AttachmentPolicy.validate(
                List.of(embeddedOfSize((int) AttachmentPolicy.EMBEDDED_MAX_SIZE_BYTES))));
  }

  @Test
  void rejectsAnEmbeddedFileOverOneMegabyte() {
    assertRejectedWith(
        "content is only allowed for files of up to 1048576 bytes",
        embeddedOfSize((int) AttachmentPolicy.EMBEDDED_MAX_SIZE_BYTES + 1));
  }

  @Test
  void rejectsEmbeddedContentWhoseDecodedSizeDiffersFromTheDeclaredSize() {
    assertRejectedWith(
        "sizeBytes does not match the decoded content",
        AttachmentSubmission.embedded(
            "a.pdf", "application/pdf", (long) BYTES.length + 1, CONTENT));
  }

  @Test
  void rejectsAUrlForAFileOfUpToOneMegabyte() {
    assertRejectedWith(
        "url is only allowed for files larger than 1048576 bytes",
        reference(AttachmentPolicy.EMBEDDED_MAX_SIZE_BYTES, URL));
    assertDoesNotThrow(
        () ->
            AttachmentPolicy.validate(
                List.of(reference(AttachmentPolicy.EMBEDDED_MAX_SIZE_BYTES + 1, URL))));
  }

  @Test
  void rejectsAUrlOverTheMaximumLength() {
    final String longUrl = "http://minio.test/" + "a".repeat(AttachmentPolicy.MAX_URL_LENGTH);

    assertRejectedWith("url must not exceed 2048 characters", reference(LARGE, longUrl));
  }

  @Test
  void acceptsATotalSizeExactlyAtTheAggregateLimit() {
    final long remainder =
        AttachmentPolicy.MAX_TOTAL_SIZE_BYTES - 2 * AttachmentPolicy.MAX_SIZE_BYTES;

    assertDoesNotThrow(
        () ->
            AttachmentPolicy.validate(
                List.of(
                    reference(AttachmentPolicy.MAX_SIZE_BYTES, URL),
                    reference(AttachmentPolicy.MAX_SIZE_BYTES, URL),
                    reference(remainder, URL))));
  }

  @Test
  void rejectsATotalSizeOverTheAggregateLimitWithoutPosition() {
    final long remainder =
        AttachmentPolicy.MAX_TOTAL_SIZE_BYTES - 2 * AttachmentPolicy.MAX_SIZE_BYTES + 1;

    final InvalidAttachmentException exception =
        rejected(
            reference(AttachmentPolicy.MAX_SIZE_BYTES, URL),
            reference(AttachmentPolicy.MAX_SIZE_BYTES, URL),
            reference(remainder, URL));

    assertEquals(-1, exception.position());
    assertEquals(
        "attachments: the total size of the attachments must not exceed 26214400 bytes",
        exception.getMessage());
  }

  @Test
  void reportsTheFirstRuleOfTheFirstInvalidAttachment() {
    final InvalidAttachmentException exception =
        rejected(valid(), withType("image/gif"), withName(".."));

    assertEquals(1, exception.position());
    assertTrue(exception.getMessage().startsWith("attachments[1]: contentType"));
  }

  @Test
  void decodeReturnsTheBytesOfAValidEmbeddedContent() {
    assertArrayEquals(BYTES, AttachmentPolicy.decode(0, valid()));
  }

  @Test
  void decodeRejectsInvalidContentWithItsPosition() {
    final InvalidAttachmentException exception =
        assertThrows(
            InvalidAttachmentException.class,
            () ->
                AttachmentPolicy.decode(
                    3, AttachmentSubmission.embedded("a.pdf", "application/pdf", 1L, "%%%")));

    assertEquals(3, exception.position());
    assertTrue(exception.getMessage().contains("content must be valid Base64"));
  }

  @Test
  void validateUploadRequestAcceptsALargeAllowedFile() {
    assertDoesNotThrow(
        () -> AttachmentPolicy.validateUploadRequest("contract.pdf", "application/pdf", LARGE));
  }

  @Test
  void validateUploadRequestAppliesNameExtensionTypeAndSizeRules() {
    assertUploadRejected("upload: fileName is required", null, "application/pdf", LARGE);
    assertUploadRejected(
        "upload: fileName extension is not allowed", "setup.exe", "application/pdf", LARGE);
    assertUploadRejected(
        "upload: contentType image/gif is not allowed (anim.gif)", "anim.gif", "image/gif", LARGE);
    assertUploadRejected(
        "upload: sizeBytes must not exceed 10485760 (big.pdf)",
        "big.pdf",
        "application/pdf",
        AttachmentPolicy.MAX_SIZE_BYTES + 1);
    assertUploadRejected(
        "upload: sizeBytes must be larger than 1048576 for an upload; smaller files travel embedded (small.pdf)",
        "small.pdf",
        "application/pdf",
        AttachmentPolicy.EMBEDDED_MAX_SIZE_BYTES);
  }

  private static void assertUploadRejected(
      final String expectedMessage,
      final String fileName,
      final String contentType,
      final Long sizeBytes) {
    final InvalidAttachmentException exception =
        assertThrows(
            InvalidAttachmentException.class,
            () -> AttachmentPolicy.validateUploadRequest(fileName, contentType, sizeBytes));
    assertEquals(expectedMessage, exception.getMessage());
  }
}
