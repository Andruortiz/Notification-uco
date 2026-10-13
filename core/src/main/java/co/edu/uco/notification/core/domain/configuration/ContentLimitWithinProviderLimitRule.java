package co.edu.uco.notification.core.domain.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class ContentLimitWithinProviderLimitRule implements CrossParameterRule {

  @Override
  public String name() {
    return "ContentLimitWithinProviderLimitRule";
  }

  @Override
  public Optional<String> violation(
      final Map<String, Long> values, final FixedConfiguration fixed) {
    final List<String> offenders = new ArrayList<>();
    new TreeMap<>(fixed.contentLimitsByChannel())
        .forEach(
            (channel, limits) ->
                new TreeMap<>(limits)
                    .forEach(
                        (limitKey, channelLimit) ->
                            collectOffenders(offenders, fixed, channel, limitKey, channelLimit)));
    if (offenders.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(String.join(", ", offenders));
  }

  private static void collectOffenders(
      final List<String> offenders,
      final FixedConfiguration fixed,
      final String channel,
      final String limitKey,
      final long channelLimit) {
    final List<String> providers =
        fixed.enabledProvidersByChannel().getOrDefault(channel, java.util.Set.of()).stream()
            .sorted()
            .toList();
    for (final String provider : providers) {
      final Long providerLimit =
          fixed
              .providerContentLimits()
              .getOrDefault(provider, Map.of())
              .getOrDefault(channel, Map.of())
              .get(limitKey);
      if (providerLimit != null && channelLimit > providerLimit) {
        offenders.add(
            channel
                + " "
                + limitKey
                + "="
                + channelLimit
                + " exceeds "
                + provider
                + " limit "
                + providerLimit);
      }
    }
  }
}
