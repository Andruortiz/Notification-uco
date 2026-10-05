package co.edu.uco.notification.infrastructure.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

public class KeyVaultEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String ENDPOINT_PROPERTY = "AZURE_KEYVAULT_ENDPOINT";
  static final String PREFIX_PROPERTY = "AZURE_KEYVAULT_SECRET_PREFIX";
  static final String PROPERTY_SOURCE_NAME = "azureKeyVaultSecrets";

  private final KeyVaultSecretsLoader loader;

  public KeyVaultEnvironmentPostProcessor() {
    this(KeyVaultSecretsLoader.azure());
  }

  KeyVaultEnvironmentPostProcessor(final KeyVaultSecretsLoader loader) {
    this.loader = loader;
  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final String endpoint = environment.getProperty(ENDPOINT_PROPERTY);
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    final String prefix = environment.getProperty(PREFIX_PROPERTY, "").trim();
    final Map<String, Object> secrets;
    try {
      secrets = loader.load(endpoint.trim(), prefix);
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "No se pudieron cargar los secretos de Azure Key Vault (" + endpoint.trim() + ")", e);
    }
    final MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, secrets);
    if (environment
        .getPropertySources()
        .contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
      environment
          .getPropertySources()
          .addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
    } else {
      environment.getPropertySources().addLast(source);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
