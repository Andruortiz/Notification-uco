package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.Map;

public record PresignedUpload(
    String url, Map<String, String> fields, String attachmentUrl, Instant expiresAt) {

  public PresignedUpload {
    Preconditions.requireNonBlank(url, "url must not be blank");
    Preconditions.requireNonNull(fields, "fields must not be null");
    Preconditions.requireNonBlank(attachmentUrl, "attachmentUrl must not be blank");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
    fields = Map.copyOf(fields);
  }

  @Override
  public String toString() {
    return "PresignedUpload[expiresAt=" + expiresAt + "]";
  }
}
