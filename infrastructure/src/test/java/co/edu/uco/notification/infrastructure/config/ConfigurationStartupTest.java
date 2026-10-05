package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConfigurationStartupTest {

  private static final String TWILIO_SID = "AC" + "0".repeat(32);

  private static final String SMS_SCHEMA_160 =
      "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":160}}}";
  private static final String SMS_SCHEMA_1601 =
      "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":1601}}}";

  @Configuration
  static class TestClockConfig {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ConfigurationConfig.class, TestClockConfig.class);

  private static String[] properties(final String... overrides) {
    final List<String> values =
        new ArrayList<>(
            List.of(
                "notification.catalog.channels.EMAIL.providers=simulated,brevo",
                "notification.catalog.channels.SMS.providers=simulated,twilio",
                "notification.catalog.channels.SMS.content-schema=" + SMS_SCHEMA_160,
                "notification.catalog.channels.PUSH.providers=simulated,fcm",
                "notification.attachments.scan.timeout=10s",
                "notification.attachments.scan.max-attempts=3",
                "notification.attachments.sweeper.scan-deadline=1h",
                "notification.provider.twilio.account-sid=" + TWILIO_SID,
                "notification.provider.twilio.auth-token=test-token",
                "notification.provider.twilio.from-number=+15005550006"));
    values.addAll(List.of(overrides));
    return values.toArray(String[]::new);
  }

  private static Throwable rootOf(final Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String failureMessages(final AssertableApplicationContext context) {
    assertNotNull(context.getStartupFailure(), "context was expected to fail");
    final StringBuilder messages = new StringBuilder();
    Throwable current = context.getStartupFailure();
    while (current != null) {
      messages.append(current.getMessage()).append(" | ");
      current = current.getCause();
    }
    return messages.toString();
  }

  @Test
  void validDefaultsStartWithDefaultsSourceAndVersionZero() {
    runner
        .withPropertyValues(properties())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              final ConfigurationSnapshot snapshot =
                  context.getBean(ConfigurationView.class).snapshot();
              assertEquals(ConfigurationSource.DEFAULTS, snapshot.source());
              assertEquals(0, snapshot.version());
              assertEquals(3, snapshot.dispatchMaxAttempts());
              assertEquals(30_000, snapshot.requeueIntervalMs());
              assertEquals(10_000, snapshot.providerTimeoutMs("brevo"));
              assertEquals(5_000, snapshot.providerConnectTimeoutMs("fcm"));
            });
  }

  @Test
  void startupReadsTheConfiguredDefaultsFromTheApplicationProperties() {
    runner
        .withPropertyValues(
            properties(
                "notification.rabbit.dispatch.max-attempts=7",
                "notification.scheduler.requeue-interval-ms=45000",
                "notification.provider.brevo.timeout-ms=12000",
                "notification.provider.twilio.connect-timeout-ms=2500"))
        .run(
            context -> {
              final ConfigurationSnapshot snapshot =
                  context.getBean(ConfigurationView.class).snapshot();
              assertEquals(7, snapshot.dispatchMaxAttempts());
              assertEquals(45_000, snapshot.requeueIntervalMs());
              assertEquals(12_000, snapshot.providerTimeoutMs("brevo"));
              assertEquals(2_500, snapshot.providerConnectTimeoutMs("twilio"));
              assertEquals(10_000, snapshot.providerTimeoutMs("twilio"));
            });
  }

  @Test
  void defaultsThatBreakTheSweepWindowRuleFailTheStartupNamingTheRule() {
    runner
        .withPropertyValues(properties("notification.attachments.sweeper.scan-deadline=30s"))
        .run(
            context -> {
              final String messages = failureMessages(context);
              assertTrue(messages.contains("AttachmentSweepWindowRule"), messages);
            });
  }

  @Test
  void defaultsThatLeaveAChannelWithoutAnEnabledProviderFailTheStartupNamingTheRule() {
    runner
        .withPropertyValues(
            properties(
                "notification.catalog.channels.SMS.providers=twilio",
                "notification.provider.twilio.auth-token="))
        .run(
            context -> {
              final String messages = failureMessages(context);
              assertTrue(messages.contains("ChannelHasEnabledProviderRule"), messages);
              assertTrue(messages.contains("SMS"), messages);
            });
  }

  @Test
  void defaultsWithAContentLimitAboveTheProviderLimitFailTheStartupNamingTheRule() {
    runner
        .withPropertyValues(
            properties("notification.catalog.channels.SMS.content-schema=" + SMS_SCHEMA_1601))
        .run(
            context -> {
              final String messages = failureMessages(context);
              assertTrue(messages.contains("ContentLimitWithinProviderLimitRule"), messages);
              assertTrue(messages.contains("twilio"), messages);
            });
  }

  @Test
  void defaultsThatBreakTheTimeoutBelowIntervalRuleFailTheStartup() {
    runner
        .withPropertyValues(properties("notification.scheduler.requeue-interval-ms=5000"))
        .run(
            context -> {
              final String messages = failureMessages(context);
              assertTrue(messages.contains("ProviderTimeoutBelowRequeueIntervalRule"), messages);
            });
  }

  @Test
  void defaultsOutOfTheDeclaredRangeFailTheStartup() {
    Stream.of(
            "notification.rabbit.dispatch.max-attempts=0",
            "notification.provider.brevo.timeout-ms=999")
        .forEach(
            override ->
                runner
                    .withPropertyValues(properties(override))
                    .run(
                        context -> {
                          final String messages = failureMessages(context);
                          assertTrue(messages.contains("between"), messages);
                        }));
  }

  @Test
  void aProviderWithoutCredentialsStillLeavesTheSimulatedProviderEnabledSoTheChannelIsServed() {
    runner
        .withPropertyValues(
            properties(
                "notification.provider.twilio.auth-token=",
                "notification.provider.twilio.account-sid="))
        .run(context -> assertNull(context.getStartupFailure()));
  }
}
