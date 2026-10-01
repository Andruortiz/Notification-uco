package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.port.in.AttachmentSummary;
import co.edu.uco.notification.utils.LogSanitizer;
import java.util.List;
import java.util.stream.Collectors;

final class AttachmentLogFormatter {

  private AttachmentLogFormatter() {}

  static String ofSummaries(final List<AttachmentSummary> attachments) {
    return attachments.stream()
        .map(
            attachment ->
                LogSanitizer.safe(attachment.fileName())
                    + "|"
                    + LogSanitizer.safe(attachment.contentType())
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
                LogSanitizer.safe(attachment.fileName())
                    + "|"
                    + LogSanitizer.safe(
                        AttachmentSubmission.normalizeContentType(attachment.contentType()))
                    + "|"
                    + attachment.sizeBytes())
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
