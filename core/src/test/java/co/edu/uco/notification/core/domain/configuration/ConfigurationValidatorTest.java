package co.edu.uco.notification.core.domain.configuration;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.change;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.defaults;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixedWith;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixedWithoutProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationValidatorTest {

  private final ConfigurationValidator validator =
      new ConfigurationValidator(new ParameterRegistry());

  private ValidationResult validateChange(final ConfigurationChange change) {
    return validator.validate(defaults(), change, fixed());
  }

  static Stream<Arguments> invalidEntries() {
    return Stream.of(
        Arguments.of("unknown key", "provider.simulated.timeout-ms", 5_000, "unknown"),
        Arguments.of("unknown plain key", "dispatch.something", 5, "unknown"),
        Arguments.of("text instead of integer", "dispatch.max-attempts", "five", "integer"),
        Arguments.of("fraction instead of integer", "dispatch.max-attempts", 2.5, "integer"),
        Arguments.of("boolean instead of integer", "dispatch.max-attempts", true, "integer"),
        Arguments.of("null value", "dispatch.max-attempts", null, "integer"),
        Arguments.of("below minimum", "dispatch.max-attempts", 0, "between"),
        Arguments.of("above maximum", "dispatch.max-attempts", 21, "between"),
        Arguments.of("interval below minimum", "requeue.interval-ms", 4_999, "between"),
        Arguments.of("interval above maximum", "requeue.interval-ms", 600_001, "between"),
        Arguments.of(
            "connect timeout below minimum", "provider.fcm.connect-timeout-ms", 499, "between"));
  }

  static Stream<Arguments> validEntries() {
    return Stream.of(
        Arguments.of("provider.brevo.timeout-ms", 8_000),
        Arguments.of("dispatch.max-attempts", 5),
        Arguments.of("dispatch.max-attempts", 5.0),
        Arguments.of("dispatch.max-attempts", 1),
        Arguments.of("dispatch.max-attempts", 20),
        Arguments.of("requeue.interval-ms", 10_001),
        Arguments.of("requeue.interval-ms", 600_000L),
        Arguments.of("provider.fcm.connect-timeout-ms", 500));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidEntries")
  void rejectsInvalidEntry(
      final String name, final String key, final Object value, final String expectedFragment) {
    final Map<String, Object> values = new HashMap<>();
    values.put(key, value);

    final ValidationResult result = validateChange(new ConfigurationChange(1, values));

    assertFalse(result.isValid(), name);
    assertTrue(result.summary().contains(key), result.summary());
    assertTrue(result.summary().contains(expectedFragment), result.summary());
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("validEntries")
  void acceptsValidEntry(final String key, final Object value) {
    final ValidationResult result = validateChange(change(1, key, value));

    assertTrue(result.isValid(), result.summary());
  }

  @Test
  void rejectsScopeNotApplicableWhenTheProviderIsNotConfigured() {
    final ConfigurationChange change = change(1, "provider.fcm.timeout-ms", 8_000);

    final ValidationResult withoutProvider =
        validator.validate(defaults(), change, fixedWithoutProvider("fcm"));
    final ValidationResult withProvider = validator.validate(defaults(), change, fixed());

    assertFalse(withoutProvider.isValid());
    assertTrue(withoutProvider.summary().contains("provider.fcm.timeout-ms"));
    assertTrue(withoutProvider.summary().contains("scope"));
    assertTrue(withProvider.isValid(), withProvider.summary());
  }

  @Test
  void mixedValidAndInvalidChangeIsRejectedAsAWholeAndReportsOnlyTheInvalidKey() {
    final ValidationResult mixed =
        validateChange(change(1, "dispatch.max-attempts", 5, "requeue.interval-ms", 1));
    final ValidationResult corrected =
        validateChange(change(1, "dispatch.max-attempts", 5, "requeue.interval-ms", 20_000));

    assertFalse(mixed.isValid());
    assertEquals(1, mixed.violations().size());
    assertTrue(mixed.violations().get(0).contains("requeue.interval-ms"));
    assertTrue(corrected.isValid(), corrected.summary());
  }

  @Test
  void rejectsTimeoutNotBelowTheRequeueIntervalAtTheExactBoundary() {
    final ValidationResult equal = validateChange(change(1, "requeue.interval-ms", 10_000));
    final ValidationResult justAbove = validateChange(change(1, "requeue.interval-ms", 10_001));

    assertFalse(equal.isValid());
    assertTrue(equal.summary().contains("ProviderTimeoutBelowRequeueIntervalRule"));
    assertTrue(justAbove.isValid(), justAbove.summary());
  }

  @Test
  void rejectsAProviderTimeoutRaisedToTheIntervalAndAcceptsOneJustBelow() {
    final ValidationResult equal = validateChange(change(1, "provider.brevo.timeout-ms", 30_000));
    final ValidationResult justBelow =
        validateChange(change(1, "provider.brevo.timeout-ms", 29_999));

    assertFalse(equal.isValid());
    assertTrue(equal.summary().contains("ProviderTimeoutBelowRequeueIntervalRule"));
    assertTrue(justBelow.isValid(), justBelow.summary());
  }

  @Test
  void rejectsSweepWindowNotAboveScanTimeoutTimesAttemptsAtTheExactBoundary() {
    final Map<String, Set<String>> channels = fixed().enabledProvidersByChannel();
    final Map<String, Map<String, Long>> limits = fixed().contentLimitsByChannel();

    final ValidationResult equal =
        validator.validate(defaults(), fixedWith(channels, 10_000, 3, 30_000, limits));
    final ValidationResult justAbove =
        validator.validate(defaults(), fixedWith(channels, 10_000, 3, 30_001, limits));

    assertFalse(equal.isValid());
    assertTrue(equal.summary().contains("AttachmentSweepWindowRule"));
    assertTrue(justAbove.isValid(), justAbove.summary());
  }

  @Test
  void rejectsAChannelWithoutAnEnabledProvider() {
    final Map<String, Map<String, Long>> limits = fixed().contentLimitsByChannel();
    final Map<String, Set<String>> noSmsProvider =
        Map.of("EMAIL", Set.of("simulated"), "SMS", Set.of(), "PUSH", Set.of("fcm"));
    final Map<String, Set<String>> oneSmsProvider =
        Map.of("EMAIL", Set.of("simulated"), "SMS", Set.of("twilio"), "PUSH", Set.of("fcm"));

    final ValidationResult empty =
        validator.validate(defaults(), fixedWith(noSmsProvider, 10_000, 3, 3_600_000, limits));
    final ValidationResult single =
        validator.validate(defaults(), fixedWith(oneSmsProvider, 10_000, 3, 3_600_000, limits));

    assertFalse(empty.isValid());
    assertTrue(empty.summary().contains("ChannelHasEnabledProviderRule"));
    assertTrue(empty.summary().contains("SMS"));
    assertTrue(single.isValid(), single.summary());
  }

  @Test
  void rejectsAChannelContentLimitAboveWhatItsProviderAccepts() {
    final Map<String, Set<String>> channels = fixed().enabledProvidersByChannel();

    final ValidationResult above =
        validator.validate(
            defaults(),
            fixedWith(
                channels, 10_000, 3, 3_600_000, Map.of("SMS", Map.of("body.maxLength", 1_601L))));
    final ValidationResult atLimit =
        validator.validate(
            defaults(),
            fixedWith(
                channels, 10_000, 3, 3_600_000, Map.of("SMS", Map.of("body.maxLength", 1_600L))));

    assertFalse(above.isValid());
    assertTrue(above.summary().contains("ContentLimitWithinProviderLimitRule"));
    assertTrue(above.summary().contains("twilio"));
    assertTrue(atLimit.isValid(), atLimit.summary());
  }

  @Test
  void aChannelLimitWithoutADeclaredProviderLimitImposesNoRestriction() {
    final Map<String, Set<String>> channels = fixed().enabledProvidersByChannel();

    final ValidationResult result =
        validator.validate(
            defaults(),
            fixedWith(
                channels, 10_000, 3, 3_600_000, Map.of("PUSH", Map.of("body.maxLength", 900L))));

    assertTrue(result.isValid(), result.summary());
  }

  @Test
  void validatesACompleteSnapshotAndRejectsOneWithMissingOrOutOfRangeKeys() {
    final ConfigurationSnapshot complete = defaults();
    final Map<String, Long> missing = new HashMap<>(complete.values());
    missing.remove("requeue.interval-ms");
    final Map<String, Long> outOfRange = new HashMap<>(complete.values());
    outOfRange.put("dispatch.max-attempts", 99L);

    assertTrue(validator.validate(complete, fixed()).isValid());
    assertFalse(validator.validate(snapshotWith(missing), fixed()).isValid());
    assertFalse(validator.validate(snapshotWith(outOfRange), fixed()).isValid());
  }

  @Test
  void aCompleteSnapshotWithAnUnknownKeyIsRejected() {
    final Map<String, Long> values = new HashMap<>(defaults().values());
    values.put("provider.brevo.api-key-length", 5L);

    final ValidationResult result = validator.validate(snapshotWith(values), fixed());

    assertFalse(result.isValid());
    assertTrue(result.summary().contains("unknown"));
  }

  @Test
  void listsEveryViolationOfAChange() {
    final ValidationResult result =
        validateChange(change(1, "dispatch.max-attempts", 0, "requeue.interval-ms", 1, "nope", 3));

    assertEquals(3, result.violations().size());
  }

  @Test
  void customRulesAreEvaluatedAfterTheEntryChecks() {
    final CrossParameterRule alwaysFails =
        new CrossParameterRule() {
          @Override
          public String name() {
            return "AlwaysFailsRule";
          }

          @Override
          public java.util.Optional<String> violation(
              final Map<String, Long> values, final FixedConfiguration fixed) {
            return java.util.Optional.of("always");
          }
        };
    final ConfigurationValidator custom =
        new ConfigurationValidator(new ParameterRegistry(), List.of(alwaysFails));

    final ValidationResult result = custom.validate(defaults(), fixed());

    assertEquals(List.of("AlwaysFailsRule: always"), result.violations());
  }

  private static ConfigurationSnapshot snapshotWith(final Map<String, Long> values) {
    return new ConfigurationSnapshot(
        0, ConfigurationSource.DEFAULTS, values, Instant.parse("2026-10-05T10:00:00Z"), Set.of());
  }
}
