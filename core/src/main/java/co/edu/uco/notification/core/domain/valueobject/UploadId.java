package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;

public record UploadId(String value) {

  public UploadId {
    Preconditions.requireNonBlank(value, "UploadId must not be blank");
  }

  public static UploadId of(final String value) {
    return new UploadId(value);
  }

  public static UploadId newId() {
    return new UploadId(UUID.randomUUID().toString());
  }
}
