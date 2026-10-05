package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.RestoreLastKnownConfigurationUseCase;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import co.edu.uco.notification.core.usecase.RestoreLastKnownConfigurationService;
import co.edu.uco.notification.infrastructure.adapter.out.parameters.ParametersProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

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

  @Configuration
  static class RestoreWiringConfig {

    static final AtomicReference<LastKnownConfigurationPort> STORE = new AtomicReference<>();

    @Bean
    LastKnownConfigurationPort lastKnownConfigurationPort() {
      return STORE.get();
    }

    @Bean
    RestoreLastKnownConfigurationUseCase restoreLastKnownConfigurationUseCase(
        final LastKnownConfigurationPort port,
        final ConfigurationHolder holder,
        final ConfigurationValidator validator,
        final FixedConfiguration fixed,
        final ParametersProperties parameters,
        final Clock clock) {
      return new RestoreLastKnownConfigurationService(
          port, holder, validator, fixed, clock, parameters.lastKnownLoadTimeout());
    }
  }

  private static LastKnownConfigurationPort storeReturning(final Mono<ConfigurationSnapshot> load) {
    return new LastKnownConfigurationPort() {
      @Override
      public Mono<ConfigurationSnapshot> load() {
        return load;
      }

      @Override
      public Mono<Boolean> saveIfNewer(final ConfigurationSnapshot snapshot) {
        return Mono.just(true);
      }
    };
  }

  private static ConfigurationSnapshot storedSnapshot() {
    final Map<String, Long> values = new HashMap<>();
    new ParameterRegistry()
        .descriptors()
        .forEach(descriptor -> values.put(descriptor.key(), descriptor.defaultValue()));
    values.put(ParameterRegistry.DISPATCH_MAX_ATTEMPTS, 7L);
    return new ConfigurationSnapshot(
        12,
        ConfigurationSource.PARAMETERS,
        values,
        Instant.parse("2026-10-04T00:00:00Z"),
        Set.of());
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
  void aSlowLastKnownStoreStillStartsWithinThirtySecondsOnDefaults() {
    RestoreWiringConfig.STORE.set(
        storeReturning(Mono.delay(Duration.ofSeconds(8)).thenReturn(storedSnapshot())));
    final Instant begin = Instant.now();

    runner
        .withUserConfiguration(RestoreWiringConfig.class)
        .withPropertyValues(properties())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              final ConfigurationSnapshot snapshot =
                  context.getBean(ConfigurationView.class).snapshot();
              assertEquals(ConfigurationSource.DEFAULTS, snapshot.source());
              assertEquals(0, snapshot.version());
            });

    final Duration elapsed = Duration.between(begin, Instant.now());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(30)) <= 0, "startup took " + elapsed);
  }

  @Test
  void aFastLastKnownStoreStartsWithTheLastKnownSourceControlForTheSlowStore() {
    RestoreWiringConfig.STORE.set(storeReturning(Mono.just(storedSnapshot())));
    final Instant begin = Instant.now();

    runner
        .withUserConfiguration(RestoreWiringConfig.class)
        .withPropertyValues(properties())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              final ConfigurationSnapshot snapshot =
                  context.getBean(ConfigurationView.class).snapshot();
              assertEquals(ConfigurationSource.LAST_KNOWN, snapshot.source());
              assertEquals(12, snapshot.version());
              assertEquals(7, snapshot.dispatchMaxAttempts());
            });

    final Duration elapsed = Duration.between(begin, Instant.now());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(30)) <= 0, "startup took " + elapsed);
  }

  @Test
  void aStoreThatFailsAtStartupStillStartsOnDefaults() {
    RestoreWiringConfig.STORE.set(
        storeReturning(Mono.error(new IllegalStateException("store down"))));

    runner
        .withUserConfiguration(RestoreWiringConfig.class)
        .withPropertyValues(properties())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(
                  ConfigurationSource.DEFAULTS,
                  context.getBean(ConfigurationView.class).snapshot().source());
            });
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
