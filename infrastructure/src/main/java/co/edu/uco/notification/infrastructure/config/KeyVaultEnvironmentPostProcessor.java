package co.edu.uco.notification.infrastructure.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Permite consumir las variables de entorno del servicio tambien desde Azure Key Vault.
 *
 * <p>Se activa solo si esta definida {@value #ENDPOINT_PROPERTY}; sin ella no hace nada y el
 * servicio sigue leyendo el {@code .env} como siempre. Los secretos se agregan justo despues de las
 * variables de entorno reales, de modo que una variable de entorno siempre prevalece sobre el vault
 * y {@code application.yml} sigue usando sus placeholders {@code ${MONGO_PASSWORD}}.
 *
 * <p>Con {@value #PREFIX_PROPERTY} solo se leen los secretos cuyo nombre empieza con ese prefijo,
 * que se descarta al exponerlos; sin ella se leen todos los del vault.
 *
 * <p>Si el vault esta configurado pero no se puede leer, el arranque falla: seguir sin secretos
 * dejaria el servicio medio configurado.
 */
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
