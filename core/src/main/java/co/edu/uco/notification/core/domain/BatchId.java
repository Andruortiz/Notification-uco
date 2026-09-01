package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;

public record BatchId(String value) {

  public BatchId {
    Preconditions.requireNonBlank(value, "BatchId must not be blank");
  }

  public static BatchId newId() {
    return new BatchId(UUID.randomUUID().toString());
  }

  public static BatchId of(final String value) {
    return new BatchId(value);
  }
}
