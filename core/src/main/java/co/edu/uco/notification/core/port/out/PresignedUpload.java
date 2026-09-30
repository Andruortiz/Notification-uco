package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record PresignedUpload(String url, Instant expiresAt) {

  public PresignedUpload {
    Preconditions.requireNonBlank(url, "url must not be blank");
    Preconditions.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    return "PresignedUpload[expiresAt=" + expiresAt + "]";
  }
}
