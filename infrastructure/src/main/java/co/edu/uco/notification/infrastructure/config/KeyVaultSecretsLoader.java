package co.edu.uco.notification.infrastructure.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lee los secretos habilitados de un Azure Key Vault y los devuelve con el nombre de una variable
 * de entorno: Key Vault solo admite letras, digitos y guiones, asi que {@code MONGO-PASSWORD} se
 * expone como {@code MONGO_PASSWORD}.
 *
 * <p>Con un prefijo, solo se leen los secretos cuyo nombre lo empieza y el prefijo se descarta:
 * {@code NOTIFICATION-MONGO-PASSWORD} con el prefijo {@code NOTIFICATION-} se expone como {@code
 * MONGO_PASSWORD}. Sin prefijo se leen todos.
 */
@FunctionalInterface
interface KeyVaultSecretsLoader {

  Map<String, Object> load(String endpoint, String prefix);

  static String toPropertyName(final String secretName) {
    return secretName.replace('-', '_');
  }

  /**
   * Autentica con {@code DefaultAzureCredential} (identidad administrada en Azure, az login local).
   */
  static KeyVaultSecretsLoader azure() {
    return (endpoint, prefix) ->
        loadFrom(
            new SecretClientBuilder()
                .vaultUrl(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient(),
            prefix);
  }

  /** Solo pide el valor de los secretos que se van a usar: ni los deshabilitados ni los ajenos. */
  static Map<String, Object> loadFrom(final SecretClient client, final String prefix) {
    final String namePrefix = prefix == null ? "" : prefix;
    final Map<String, Object> secrets = new LinkedHashMap<>();
    for (final SecretProperties properties : client.listPropertiesOfSecrets()) {
      final String name = properties.getName();
      if (Boolean.FALSE.equals(properties.isEnabled()) || !name.startsWith(namePrefix)) {
        continue;
      }
      final String variable = name.substring(namePrefix.length());
      if (variable.isEmpty()) {
        continue;
      }
      final KeyVaultSecret secret = client.getSecret(name);
      secrets.put(toPropertyName(variable), secret.getValue());
    }
    return secrets;
  }
}
