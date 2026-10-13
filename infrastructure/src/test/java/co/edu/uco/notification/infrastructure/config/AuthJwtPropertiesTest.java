package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthJwtPropertiesTest {

  private static final String VALID_SECRET =
      "a-valid-secret-with-more-than-thirty-two-bytes-0123456789";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SecurityConfig.class)
          .withPropertyValues("spring.profiles.active=none");

  @Test
  void startsWithAValidSecretAndTtl() {
    runner
        .withPropertyValues(
            "notification.auth.jwt.hs256-secret=" + VALID_SECRET,
            "notification.auth.jwt.ttl-minutes=720")
        .run(context -> assertNull(context.getStartupFailure()));
  }

  @Test
  void failsToStartWithoutASecretAndNamesTheVariable() {
    runner.run(
        context -> {
          assertNotNull(context.getStartupFailure());
          assertTrue(rootMessage(context.getStartupFailure()).contains("AUTH_JWT_HS256_SECRET"));
        });
  }

  @Test
  void failsToStartWithABlankSecret() {
    runner
        .withPropertyValues("notification.auth.jwt.hs256-secret=   ")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void failsToStartWithASecretShorterThanThirtyTwoBytes() {
    runner
        .withPropertyValues("notification.auth.jwt.hs256-secret=" + "x".repeat(31))
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void startsWithASecretOfExactlyThirtyTwoBytes() {
    runner
        .withPropertyValues("notification.auth.jwt.hs256-secret=" + "x".repeat(32))
        .run(context -> assertNull(context.getStartupFailure()));
  }

  @Test
  void failsToStartWithThePublishedDevelopmentSecretOutsideTheLocalProfile() {
    runner
        .withPropertyValues(
            "notification.auth.jwt.hs256-secret=" + AuthJwtSecretGuard.DEVELOPMENT_SECRET)
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void acceptsThePublishedDevelopmentSecretWithTheLocalProfile() {
    runner
        .withPropertyValues(
            "spring.profiles.active=local",
            "notification.auth.jwt.hs256-secret=" + AuthJwtSecretGuard.DEVELOPMENT_SECRET)
        .run(context -> assertNull(context.getStartupFailure()));
  }

  @Test
  void failsToStartWhenTheTtlIsOutOfRange() {
    runner
        .withPropertyValues(
            "notification.auth.jwt.hs256-secret=" + VALID_SECRET,
            "notification.auth.jwt.ttl-minutes=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
    runner
        .withPropertyValues(
            "notification.auth.jwt.hs256-secret=" + VALID_SECRET,
            "notification.auth.jwt.ttl-minutes=1441")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void doesNotRequireASecretInPlatformMode() {
    runner
        .withPropertyValues("notification.auth.mode=platform")
        .run(context -> assertNull(context.getStartupFailure()));
  }

  private static String rootMessage(final Throwable failure) {
    Throwable current = failure;
    final StringBuilder messages = new StringBuilder();
    while (current != null) {
      messages.append(current.getMessage()).append(' ');
      current = current.getCause();
    }
    return messages.toString();
  }
}
