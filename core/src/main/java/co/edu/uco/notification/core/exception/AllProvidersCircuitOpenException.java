package co.edu.uco.notification.core.exception;

public class AllProvidersCircuitOpenException extends RuntimeException {

  public AllProvidersCircuitOpenException() {
    super("All candidate providers have an open circuit");
  }
}
