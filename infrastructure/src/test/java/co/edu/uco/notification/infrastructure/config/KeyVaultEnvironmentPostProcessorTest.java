package co.edu.uco.notification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class KeyVaultEnvironmentPostProcessorTest {

  private static final String ENDPOINT = "https://notification-kv.vault.azure.net/";

  @Test
  void sinEndpointNoConsultaElVaultNiAgregaFuentes() {
    final MockEnvironment environment = new MockEnvironment();
    final KeyVaultEnvironmentPostProcessor processor =
        new KeyVaultEnvironmentPostProcessor(
            (endpoint, prefix) -> {
              throw new AssertionError("no debe consultar el vault");
            });

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getPropertySources().contains("azureKeyVaultSecrets")).isFalse();
  }

  @Test
  void conEndpointExponeLosSecretosComoVariablesDeEntorno() {
    final MockEnvironment environment =
        new MockEnvironment().withProperty("AZURE_KEYVAULT_ENDPOINT", ENDPOINT);
    final KeyVaultEnvironmentPostProcessor processor =
        new KeyVaultEnvironmentPostProcessor(
            (endpoint, prefix) -> {
              assertThat(endpoint).isEqualTo(ENDPOINT.trim());
              assertThat(prefix).isEmpty();
              return Map.of("MONGO_PASSWORD", "secreto-vault");
            });

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("MONGO_PASSWORD")).isEqualTo("secreto-vault");
  }

  @Test
  void unaVariableDeEntornoRealPrevaleceSobreElVault() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("AZURE_KEYVAULT_ENDPOINT", ENDPOINT, "MONGO_PASSWORD", "desde-env")));
    final KeyVaultEnvironmentPostProcessor processor =
        new KeyVaultEnvironmentPostProcessor(
            (endpoint, prefix) -> Map.of("MONGO_PASSWORD", "desde-vault", "BREVO_API_KEY", "k"));

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("MONGO_PASSWORD")).isEqualTo("desde-env");
    assertThat(environment.getProperty("BREVO_API_KEY")).isEqualTo("k");
  }

  @Test
  void siElVaultFallaElArranqueFallaSinFiltrarElDetalleDelSecreto() {
    final MockEnvironment environment =
        new MockEnvironment().withProperty("AZURE_KEYVAULT_ENDPOINT", ENDPOINT);
    final KeyVaultEnvironmentPostProcessor processor =
        new KeyVaultEnvironmentPostProcessor(
            (endpoint, prefix) -> {
              throw new IllegalArgumentException("sin permisos");
            });

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Azure Key Vault")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void losNombresDeSecretoConGuionesSeConviertenAVariablesDeEntorno() {
    assertThat(KeyVaultSecretsLoader.toPropertyName("MONGO-PASSWORD")).isEqualTo("MONGO_PASSWORD");
    assertThat(KeyVaultSecretsLoader.toPropertyName("APP_PORT")).isEqualTo("APP_PORT");
  }

  @Test
  void elPrefijoConfiguradoSePasaAlCargador() {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty("AZURE_KEYVAULT_ENDPOINT", ENDPOINT)
            .withProperty("AZURE_KEYVAULT_SECRET_PREFIX", "  NOTIFICATION-  ");
    final KeyVaultEnvironmentPostProcessor processor =
        new KeyVaultEnvironmentPostProcessor(
            (endpoint, prefix) -> {
              assertThat(prefix).isEqualTo("NOTIFICATION-");
              return Map.of("MONGO_PASSWORD", "secreto-vault");
            });

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("MONGO_PASSWORD")).isEqualTo("secreto-vault");
  }

  @Test
  void elOrdenDelProcesadorEsElMasTardio() {
    assertThat(new KeyVaultEnvironmentPostProcessor((endpoint, prefix) -> Map.of()).getOrder())
        .isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
  }
}
