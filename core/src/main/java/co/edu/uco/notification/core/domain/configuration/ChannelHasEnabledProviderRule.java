package co.edu.uco.notification.core.domain.configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChannelHasEnabledProviderRule implements CrossParameterRule {

  @Override
  public String name() {
    return "ChannelHasEnabledProviderRule";
  }

  @Override
  public Optional<String> violation(
      final Map<String, Long> values, final FixedConfiguration fixed) {
    final List<String> channelsWithoutProvider =
        fixed.enabledProvidersByChannel().entrySet().stream()
            .filter(entry -> entry.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    if (channelsWithoutProvider.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "channel without an enabled provider: " + String.join(", ", channelsWithoutProvider));
  }
}
