package co.edu.uco.notification.infrastructure.config;

import org.springframework.core.env.Environment;

public final class PepDelegation {

  private static final String SECURITY_ENABLED = "security.enabled";
  private static final String PEP_ENFORCEMENT_ENABLED = "security.pep.enforcement.enabled";

  private PepDelegation() {}

  public static boolean isActive(final Environment environment) {
    return environment.getProperty(SECURITY_ENABLED, Boolean.class, true)
        && environment.getProperty(PEP_ENFORCEMENT_ENABLED, Boolean.class, false);
  }
}
