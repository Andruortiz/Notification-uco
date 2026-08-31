package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.utils.Preconditions;

public record Recipient(String address) {

  public Recipient {
    Preconditions.requireNonBlank(address, "Recipient address must not be blank");
  }

  public static Recipient of(final String address) {
    return new Recipient(address);
  }
}
