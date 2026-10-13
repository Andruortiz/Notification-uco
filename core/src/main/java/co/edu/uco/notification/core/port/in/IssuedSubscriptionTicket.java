package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.utils.Preconditions;

public record IssuedSubscriptionTicket(String ticket, long expiresInSeconds) {

  public IssuedSubscriptionTicket {
    Preconditions.requireNonBlank(ticket, "ticket must not be blank");
  }
}
