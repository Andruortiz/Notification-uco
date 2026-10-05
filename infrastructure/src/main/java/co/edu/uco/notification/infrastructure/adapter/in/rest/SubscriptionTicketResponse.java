package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.IssuedSubscriptionTicket;

public record SubscriptionTicketResponse(String ticket, long expiresInSeconds) {

  static SubscriptionTicketResponse from(final IssuedSubscriptionTicket issued) {
    return new SubscriptionTicketResponse(issued.ticket(), issued.expiresInSeconds());
  }
}
