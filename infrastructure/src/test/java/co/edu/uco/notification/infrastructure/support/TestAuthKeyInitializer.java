package co.edu.uco.notification.infrastructure.support;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Registers the ephemeral test public key as notification.auth.platform.public-key in every test
 * context. A context that declares test.auth.no-default-key=true receives nothing, so it can
 * exercise startup without a key.
 */
public final class TestAuthKeyInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  public static final String NO_DEFAULT_KEY = "test.auth.no-default-key";

  @Override
  public void initialize(final ConfigurableApplicationContext context) {
    if (context.getEnvironment().getProperty(NO_DEFAULT_KEY, Boolean.class, false)) {
      return;
    }
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "testAuthDefaults",
                Map.of("notification.auth.platform.public-key", TestTokens.PUBLIC_KEY)));
  }
}
