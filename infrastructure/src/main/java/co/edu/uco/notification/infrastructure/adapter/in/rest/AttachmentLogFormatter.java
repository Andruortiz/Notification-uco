package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.port.in.AttachmentSummary;
import java.util.List;
import java.util.stream.Collectors;

final class AttachmentLogFormatter {

  private AttachmentLogFormatter() {}

  static String ofSummaries(final List<AttachmentSummary> attachments) {
    return attachments.stream()
        .map(
            attachment ->
                safe(attachment.fileName())
                    + "|"
                    + safe(attachment.contentType())
                    + "|"
                    + attachment.sizeBytes()
                    + "|"
                    + attachment.sha256())
        .collect(Collectors.joining(", ", "[", "]"));
  }

  static String ofRequests(final List<AttachmentRequest> attachments) {
    return attachments.stream()
        .map(
            attachment ->
                safe(attachment.fileName())
                    + "|"
                    + safe(AttachmentSubmission.normalizeContentType(attachment.contentType()))
                    + "|"
                    + attachment.sizeBytes())
        .collect(Collectors.joining(", ", "[", "]"));
  }

  static String safe(final String value) {
    if (value == null) {
      return "null";
    }
    final StringBuilder safe = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(code -> safe.appendCodePoint(Character.isISOControl(code) ? '_' : code));
    return safe.toString();
  }
}
