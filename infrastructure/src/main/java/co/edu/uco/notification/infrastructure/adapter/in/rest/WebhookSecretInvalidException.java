package co.edu.uco.notification.infrastructure.adapter.in.rest;

public class WebhookSecretInvalidException extends RuntimeException {

  public WebhookSecretInvalidException() {
    super("the webhook secret is missing or invalid");
  }
}
