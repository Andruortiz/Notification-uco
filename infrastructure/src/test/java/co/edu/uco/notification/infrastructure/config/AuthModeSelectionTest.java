package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.platform.PlatformJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.support.TestAuthKeyInitializer;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthModeSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SecurityConfig.class, PlatformJwtTokenValidationAdapter.class)
          .withPropertyValues(TestAuthKeyInitializer.NO_DEFAULT_KEY + "=true");

  @Test
  void startsWithThePlatformAdapterWhenThePublicKeyIsConfigured() {
    runner
        .withPropertyValues("notification.auth.platform.public-key=" + TestTokens.PUBLIC_KEY)
        .run(
            context ->
                assertInstanceOf(
                    PlatformJwtTokenValidationAdapter.class,
                    context.getBean(TokenValidationPort.class)));
  }

  @Test
  void failsToStartWithoutAPublicKeyAndNamesTheVariable() {
    runner.run(
        context -> {
          assertNotNull(context.getStartupFailure());
          assertTrue(
              context.getStartupFailure().toString().contains("AUTH_PLATFORM_PUBLIC_KEY")
                  || causeMessages(context.getStartupFailure())
                      .contains("AUTH_PLATFORM_PUBLIC_KEY"));
        });
  }

  private static String causeMessages(final Throwable failure) {
    final StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append(' ');
    }
    return messages.toString();
  }
}
