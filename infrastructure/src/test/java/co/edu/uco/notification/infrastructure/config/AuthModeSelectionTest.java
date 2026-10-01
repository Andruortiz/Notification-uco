package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.port.out.TokenValidationPort;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.adapter.out.security.platform.PlatformJwtTokenValidationAdapter;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthModeSelectionTest {

  private static String publicKey;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SecurityConfig.class,
              LocalJwtTokenValidationAdapter.class,
              PlatformJwtTokenValidationAdapter.class)
          .withPropertyValues(
              "notification.auth.jwt.hs256-secret=test-only-secret-never-used-in-production-0123456789abcdef");

  @BeforeAll
  static void generateKey() throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    publicKey =
        Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
  }

  @Test
  void usesTheLocalAdapterByDefault() {
    runner.run(
        context -> {
          assertInstanceOf(
              LocalJwtTokenValidationAdapter.class, context.getBean(TokenValidationPort.class));
          assertTrue(context.getBeansOfType(PlatformJwtTokenValidationAdapter.class).isEmpty());
        });
  }

  @Test
  void usesTheLocalAdapterWhenTheModeIsLocal() {
    runner
        .withPropertyValues("notification.auth.mode=local")
        .run(
            context ->
                assertInstanceOf(
                    LocalJwtTokenValidationAdapter.class,
                    context.getBean(TokenValidationPort.class)));
  }

  @Test
  void usesThePlatformAdapterWhenTheModeIsPlatform() {
    runner
        .withPropertyValues(
            "notification.auth.mode=platform", "notification.auth.platform.public-key=" + publicKey)
        .run(
            context -> {
              assertInstanceOf(
                  PlatformJwtTokenValidationAdapter.class,
                  context.getBean(TokenValidationPort.class));
              assertTrue(context.getBeansOfType(LocalJwtTokenValidationAdapter.class).isEmpty());
            });
  }

  @Test
  void failsToStartInPlatformModeWithoutAPublicKey() {
    runner
        .withPropertyValues("notification.auth.mode=platform")
        .run(context -> assertTrue(context.getStartupFailure() != null));
  }
}
