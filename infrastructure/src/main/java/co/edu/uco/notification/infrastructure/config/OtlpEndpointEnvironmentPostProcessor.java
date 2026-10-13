package co.edu.uco.notification.infrastructure.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class OtlpEndpointEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String ENVIRONMENT_VARIABLE = "OTEL_EXPORTER_OTLP_ENDPOINT";
  static final String ENDPOINT_PROPERTY = "management.otlp.tracing.endpoint";
  static final String TRACES_PATH = "/v1/traces";
  static final String PROPERTY_SOURCE_NAME = "otlpTracingEndpoint";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final String configured = environment.getProperty(ENDPOINT_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return;
    }
    final String endpoint = environment.getProperty(ENVIRONMENT_VARIABLE);
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    final String base = endpoint.trim();
    final String url =
        base.endsWith(TRACES_PATH)
            ? base
            : (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + TRACES_PATH;
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(ENDPOINT_PROPERTY, url)));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
