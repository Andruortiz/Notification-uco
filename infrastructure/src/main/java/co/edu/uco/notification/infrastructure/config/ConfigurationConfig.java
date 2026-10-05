package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.configuration.ValidationResult;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.in.RestoreLastKnownConfigurationUseCase;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogProperties;
import co.edu.uco.notification.infrastructure.adapter.out.parameters.ParametersProperties;
import co.edu.uco.notification.infrastructure.adapter.out.provider.ProviderContentLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties({
  ChannelCatalogProperties.class,
  AttachmentProperties.class,
  BrevoProviderProperties.class,
  TwilioProviderProperties.class,
  FcmProviderProperties.class,
  ParametersProperties.class
})
public class ConfigurationConfig {

  private static final String SIMULATED = "simulated";
  private static final String BREVO = "brevo";
  private static final String TWILIO = "twilio";
  private static final String FCM = "fcm";
  private static final Duration DEFAULT_SCAN_TIMEOUT = Duration.ofSeconds(10);
  private static final int DEFAULT_SCAN_MAX_ATTEMPTS = 3;
  public static final String VERSION_METRIC = "notification.configuration.version";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration STARTUP_RESTORE_MARGIN = Duration.ofSeconds(1);
  private static final Logger LOG = LoggerFactory.getLogger(ConfigurationConfig.class);

  @Bean
  ParameterRegistry parameterRegistry() {
    return new ParameterRegistry();
  }

  @Bean
  ConfigurationValidator configurationValidator(final ParameterRegistry registry) {
    return new ConfigurationValidator(registry);
  }

  @Bean
  FixedConfiguration fixedConfiguration(
      final ChannelCatalogProperties catalog,
      final AttachmentProperties attachments,
      final BrevoProviderProperties brevo,
      final TwilioProviderProperties twilio,
      final FcmProviderProperties fcm,
      @Value("${notification.attachments.sweeper.scan-deadline:1h}") final String sweepWindow) {
    final Set<String> enabledProviders = new HashSet<>();
    enabledProviders.add(SIMULATED);
    if (brevo.disabledReason().isEmpty()) {
      enabledProviders.add(BREVO);
    }
    if (twilio.disabledReason().isEmpty()) {
      enabledProviders.add(TWILIO);
    }
    if (FcmCredentials.load(fcm).disabledReason().isEmpty()) {
      enabledProviders.add(FCM);
    }
    final Map<String, Set<String>> enabledByChannel = new LinkedHashMap<>();
    final Map<String, Map<String, Long>> limitsByChannel = new LinkedHashMap<>();
    final Set<String> configuredProviders = new HashSet<>(Set.of(SIMULATED, BREVO, TWILIO, FCM));
    if (catalog.channels() != null) {
      catalog
          .channels()
          .forEach(
              (channel, entry) -> {
                final Set<String> providers =
                    entry.providers() == null ? Set.of() : new HashSet<>(entry.providers());
                configuredProviders.addAll(providers);
                final Set<String> enabled = new HashSet<>(providers);
                enabled.retainAll(enabledProviders);
                enabledByChannel.put(channel, enabled);
                limitsByChannel.put(channel, contentLimits(entry.contentSchema()));
              });
    }
    final AttachmentProperties.Scan scan = attachments.scan();
    final Duration scanTimeout =
        scan == null || scan.timeout() == null ? DEFAULT_SCAN_TIMEOUT : scan.timeout();
    final int scanMaxAttempts =
        scan == null || scan.maxAttempts() <= 0 ? DEFAULT_SCAN_MAX_ATTEMPTS : scan.maxAttempts();
    return new FixedConfiguration(
        enabledByChannel,
        configuredProviders,
        scanTimeout.toMillis(),
        scanMaxAttempts,
        DurationStyle.detectAndParse(sweepWindow).toMillis(),
        limitsByChannel,
        ProviderContentLimits.byProvider());
  }

  @Bean
  ConfigurationHolder configurationHolder(
      final ParameterRegistry registry,
      final ConfigurationValidator validator,
      final FixedConfiguration fixed,
      final Environment environment,
      final BrevoProviderProperties brevo,
      final TwilioProviderProperties twilio,
      final FcmProviderProperties fcm,
      final Clock clock) {
    final Map<String, Long> values = new HashMap<>();
    for (final ParameterDescriptor descriptor : registry.descriptors()) {
      values.put(descriptor.key(), descriptor.defaultValue());
    }
    values.put(
        ParameterRegistry.DISPATCH_MAX_ATTEMPTS,
        environment.getProperty(
            "notification.rabbit.dispatch.max-attempts",
            Long.class,
            values.get(ParameterRegistry.DISPATCH_MAX_ATTEMPTS)));
    values.put(
        ParameterRegistry.REQUEUE_INTERVAL_MS,
        environment.getProperty(
            "notification.scheduler.requeue-interval-ms",
            Long.class,
            values.get(ParameterRegistry.REQUEUE_INTERVAL_MS)));
    values.put(ParameterRegistry.timeoutKey(BREVO), brevo.timeoutMs());
    values.put(ParameterRegistry.connectTimeoutKey(BREVO), brevo.connectTimeoutMs());
    values.put(ParameterRegistry.timeoutKey(TWILIO), twilio.timeoutMs());
    values.put(ParameterRegistry.connectTimeoutKey(TWILIO), twilio.connectTimeoutMs());
    values.put(ParameterRegistry.timeoutKey(FCM), fcm.timeoutMs());
    values.put(ParameterRegistry.connectTimeoutKey(FCM), fcm.connectTimeoutMs());
    final ConfigurationSnapshot defaults =
        new ConfigurationSnapshot(
            0, ConfigurationSource.DEFAULTS, values, clock.instant(), Set.of());
    final ValidationResult validation = validator.validate(defaults, fixed);
    if (!validation.isValid()) {
      throw new IllegalStateException(
          "Default configuration violates the validation rules: " + validation.summary());
    }
    return new ConfigurationHolder(defaults);
  }

  @Bean
  SmartInitializingSingleton lastKnownConfigurationRestorer(
      final ObjectProvider<RestoreLastKnownConfigurationUseCase> restoreUseCase,
      final ParametersProperties parametersProperties) {
    return () -> {
      final RestoreLastKnownConfigurationUseCase useCase = restoreUseCase.getIfAvailable();
      if (useCase == null) {
        return;
      }
      try {
        useCase
            .restore()
            .block(parametersProperties.lastKnownLoadTimeout().plus(STARTUP_RESTORE_MARGIN));
      } catch (final RuntimeException exception) {
        LOG.warn(
            "last known configuration could not be restored reason={}",
            exception.getClass().getSimpleName());
      }
    };
  }

  @Bean
  MeterBinder configurationVersionMetric(final ConfigurationView configurationView) {
    return registry -> {
      for (final ConfigurationSource source : ConfigurationSource.values()) {
        Gauge.builder(
                VERSION_METRIC,
                configurationView,
                view -> view.snapshot().source() == source ? view.snapshot().version() : 0)
            .tag("source", source.name())
            .description("Version of the configuration in use by this replica")
            .register(registry);
      }
    };
  }

  private static Map<String, Long> contentLimits(final String contentSchema) {
    final Map<String, Long> limits = new LinkedHashMap<>();
    if (contentSchema == null || contentSchema.isBlank()) {
      return limits;
    }
    try {
      final JsonNode properties = MAPPER.readTree(contentSchema).path("properties");
      final Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
      while (fields.hasNext()) {
        final Map.Entry<String, JsonNode> field = fields.next();
        collectLimit(limits, field, "maxLength");
        collectLimit(limits, field, "maximum");
      }
    } catch (final IOException exception) {
      return new LinkedHashMap<>();
    }
    return limits;
  }

  private static void collectLimit(
      final Map<String, Long> limits, final Map.Entry<String, JsonNode> field, final String name) {
    final JsonNode limit = field.getValue().path(name);
    if (limit.isIntegralNumber()) {
      limits.put(field.getKey() + "." + name, limit.asLong());
    }
  }
}
