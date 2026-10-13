package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class ConfigurationValidator {

  private final ParameterRegistry registry;
  private final List<CrossParameterRule> rules;

  public ConfigurationValidator(final ParameterRegistry registry) {
    this(
        registry,
        List.of(
            new ProviderTimeoutBelowRequeueIntervalRule(),
            new AttachmentSweepWindowRule(),
            new ChannelHasEnabledProviderRule(),
            new ContentLimitWithinProviderLimitRule()));
  }

  public ConfigurationValidator(
      final ParameterRegistry registry, final List<CrossParameterRule> rules) {
    this.registry = Preconditions.requireNonNull(registry, "registry must not be null");
    this.rules = List.copyOf(Preconditions.requireNonNull(rules, "rules must not be null"));
  }

  public ParameterRegistry registry() {
    return registry;
  }

  public ValidationResult validate(
      final ConfigurationSnapshot current,
      final ConfigurationChange change,
      final FixedConfiguration fixed) {
    final List<String> entryViolations = entryViolations(change.values(), fixed);
    if (!entryViolations.isEmpty()) {
      return new ValidationResult(entryViolations);
    }
    final Map<String, Long> candidate = new HashMap<>(current.values());
    new TreeMap<>(change.values())
        .forEach(
            (key, raw) -> {
              final ParameterDescriptor descriptor = registry.find(key).orElseThrow();
              if (descriptor.adoption() == AdoptionMode.HOT) {
                candidate.put(key, descriptor.asInteger(raw).orElseThrow());
              }
            });
    return new ValidationResult(ruleViolations(candidate, fixed, false));
  }

  public ValidationResult validate(
      final ConfigurationSnapshot candidate, final FixedConfiguration fixed) {
    return validate(candidate, fixed, false);
  }

  private ValidationResult validate(
      final ConfigurationSnapshot candidate,
      final FixedConfiguration fixed,
      final boolean startupOnly) {
    final List<String> violations = new ArrayList<>(entryViolations(asObjects(candidate), fixed));
    registry.descriptors().stream()
        .map(ParameterDescriptor::key)
        .filter(key -> !candidate.values().containsKey(key))
        .sorted()
        .forEach(key -> violations.add(key + " has no value"));
    if (violations.isEmpty()) {
      violations.addAll(ruleViolations(candidate.values(), fixed, startupOnly));
    }
    return new ValidationResult(violations);
  }

  public ValidationResult validateAtStartup(
      final ConfigurationSnapshot candidate, final FixedConfiguration fixed) {
    return validate(candidate, fixed, true);
  }

  public List<String> startupWarnings(
      final ConfigurationSnapshot candidate, final FixedConfiguration fixed) {
    final List<String> warnings = new ArrayList<>();
    for (final CrossParameterRule rule : rules) {
      if (!rule.blocksStartup()) {
        rule.violation(candidate.values(), fixed)
            .ifPresent(detail -> warnings.add(rule.name() + ": " + detail));
      }
    }
    return warnings;
  }

  private static Map<String, Object> asObjects(final ConfigurationSnapshot candidate) {
    return new HashMap<>(candidate.values());
  }

  private List<String> entryViolations(
      final Map<String, Object> entries, final FixedConfiguration fixed) {
    final List<String> violations = new ArrayList<>();
    new TreeMap<>(entries)
        .forEach(
            (key, raw) -> {
              final Optional<ParameterDescriptor> descriptor = registry.find(key);
              if (descriptor.isEmpty()) {
                violations.add("unknown parameter key " + key);
                return;
              }
              scopeViolation(descriptor.get(), fixed).ifPresent(violations::add);
              descriptor.get().violation(raw).ifPresent(violations::add);
            });
    return violations;
  }

  private static Optional<String> scopeViolation(
      final ParameterDescriptor descriptor, final FixedConfiguration fixed) {
    return switch (descriptor.scope()) {
      case GLOBAL -> Optional.empty();
      case PROVIDER ->
          descriptor.scopeIds().stream()
              .filter(providerId -> !fixed.configuredProviders().contains(providerId))
              .findFirst()
              .map(
                  providerId ->
                      descriptor.key()
                          + " scope not applicable: provider "
                          + providerId
                          + " is not configured");
      case CHANNEL ->
          descriptor.scopeIds().stream()
              .filter(channel -> !fixed.enabledProvidersByChannel().containsKey(channel))
              .findFirst()
              .map(
                  channel ->
                      descriptor.key()
                          + " scope not applicable: channel "
                          + channel
                          + " is not configured");
    };
  }

  private List<String> ruleViolations(
      final Map<String, Long> values, final FixedConfiguration fixed, final boolean startupOnly) {
    final List<String> violations = new ArrayList<>();
    for (final CrossParameterRule rule : rules) {
      if (startupOnly && !rule.blocksStartup()) {
        continue;
      }
      rule.violation(values, fixed)
          .ifPresent(detail -> violations.add(rule.name() + ": " + detail));
    }
    return violations;
  }
}
