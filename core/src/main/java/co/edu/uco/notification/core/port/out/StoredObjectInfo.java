package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.utils.Preconditions;

public record StoredObjectInfo(long sizeBytes, String etag) {

  public StoredObjectInfo {
    Preconditions.requireNonBlank(etag, "etag must not be blank");
  }
}
