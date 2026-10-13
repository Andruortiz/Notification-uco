package co.edu.uco.notification.core.domain.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ParameterRegistryTest {

  private static final Pattern SENSITIVE_NAME =
      Pattern.compile(
          "(^|[.-])(secret|password|token|credential|credentials|api-key|queue|exchange|base-url)([.-]|$)");

  private static boolean leaksSensitiveName(final ParameterRegistry registry) {
    return registry.descriptors().stream()
        .map(ParameterDescriptor::key)
        .anyMatch(key -> SENSITIVE_NAME.matcher(key).find());
  }

  @Test
  void containsExactlyTheInitialManageableKeys() {
    final ParameterRegistry registry = new ParameterRegistry();

    final Set<String> keys =
        registry.descriptors().stream().map(ParameterDescriptor::key).collect(Collectors.toSet());

    assertEquals(
        Set.of(
            "dispatch.max-attempts",
            "provider.brevo.timeout-ms",
            "provider.brevo.connect-timeout-ms",
            "provider.twilio.timeout-ms",
            "provider.twilio.connect-timeout-ms",
            "provider.fcm.timeout-ms",
            "provider.fcm.connect-timeout-ms",
            "requeue.interval-ms"),
        keys);
    assertEquals(8, registry.descriptors().size());
  }

  @Test
  void dispatchMaxAttemptsIsGlobalWithRangeOneToTwenty() {
    final ParameterDescriptor descriptor =
        new ParameterRegistry().find("dispatch.max-attempts").orElseThrow();

    assertEquals(ParameterType.INTEGER, descriptor.type());
    assertEquals(3, descriptor.defaultValue());
    assertEquals(1, descriptor.min());
    assertEquals(20, descriptor.max());
    assertEquals(ParameterScope.GLOBAL, descriptor.scope());
    assertTrue(descriptor.scopeIds().isEmpty());
    assertEquals(AdoptionMode.HOT, descriptor.adoption());
  }

  @Test
  void providerTimeoutsAreProviderScopedWithTheirOwnScopeId() {
    final ParameterRegistry registry = new ParameterRegistry();

    for (final String providerId : List.of("brevo", "twilio", "fcm")) {
      final ParameterDescriptor timeout =
          registry.find("provider." + providerId + ".timeout-ms").orElseThrow();
      assertEquals(10_000, timeout.defaultValue());
      assertEquals(1_000, timeout.min());
      assertEquals(60_000, timeout.max());
      assertEquals(ParameterScope.PROVIDER, timeout.scope());
      assertEquals(List.of(providerId), timeout.scopeIds());
      assertEquals(AdoptionMode.HOT, timeout.adoption());

      final ParameterDescriptor connect =
          registry.find("provider." + providerId + ".connect-timeout-ms").orElseThrow();
      assertEquals(5_000, connect.defaultValue());
      assertEquals(500, connect.min());
      assertEquals(30_000, connect.max());
      assertEquals(ParameterScope.PROVIDER, connect.scope());
      assertEquals(List.of(providerId), connect.scopeIds());
    }
  }

  @Test
  void requeueIntervalIsGlobalWithItsRange() {
    final ParameterDescriptor descriptor =
        new ParameterRegistry().find("requeue.interval-ms").orElseThrow();

    assertEquals(30_000, descriptor.defaultValue());
    assertEquals(5_000, descriptor.min());
    assertEquals(600_000, descriptor.max());
    assertEquals(ParameterScope.GLOBAL, descriptor.scope());
  }

  @Test
  void noKeyExposesSecretsTopologyOrBaseUrls() {
    assertFalse(leaksSensitiveName(new ParameterRegistry()));
  }

  @Test
  void sensitiveNameCheckDetectsAnApiKeyDescriptor() {
    final ParameterRegistry registry =
        new ParameterRegistry(
            List.of(ParameterDescriptor.forProvider("provider.brevo.api-key", "brevo", 5, 1, 10)));
    final ParameterRegistry tokenRegistry =
        new ParameterRegistry(
            List.of(ParameterDescriptor.forProvider("provider.brevo.token", "brevo", 5, 1, 10)));

    assertTrue(leaksSensitiveName(registry));
    assertTrue(leaksSensitiveName(tokenRegistry));
  }

  @Test
  void additionalDescriptorsAreAcceptedAndKeepRestartMode() {
    final ParameterDescriptor restart =
        ParameterDescriptor.global("test.restart-only", 5, 1, 10)
            .withAdoption(AdoptionMode.RESTART);

    final ParameterRegistry registry = new ParameterRegistry(List.of(restart));

    assertEquals(AdoptionMode.RESTART, registry.find("test.restart-only").orElseThrow().adoption());
    assertEquals(9, registry.descriptors().size());
  }

  @Test
  void duplicateKeysAreRejected() {
    final List<ParameterDescriptor> duplicate =
        List.of(ParameterDescriptor.global("dispatch.max-attempts", 3, 1, 20));

    assertThrows(IllegalArgumentException.class, () -> new ParameterRegistry(duplicate));
  }

  @Test
  void unknownKeyIsNotFound() {
    assertTrue(new ParameterRegistry().find("provider.simulated.timeout-ms").isEmpty());
  }
}
