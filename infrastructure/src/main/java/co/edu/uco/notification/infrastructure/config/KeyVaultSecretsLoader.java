package co.edu.uco.notification.infrastructure.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import java.util.LinkedHashMap;
import java.util.Map;

@FunctionalInterface
interface KeyVaultSecretsLoader {

  Map<String, Object> load(String endpoint, String prefix);

  static String toPropertyName(final String secretName) {
    return secretName.replace('-', '_');
  }

  static KeyVaultSecretsLoader azure() {
    return (endpoint, prefix) ->
        loadFrom(
            new SecretClientBuilder()
                .vaultUrl(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient(),
            prefix);
  }

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
