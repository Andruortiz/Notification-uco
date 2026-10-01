package co.edu.uco.notification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyVaultSecretsLoaderTest {

  private final SecretClient client = mock(SecretClient.class);

  private static SecretProperties properties(final String name, final boolean enabled) {
    return new KeyVaultSecret(name, "x").getProperties().setEnabled(enabled);
  }

  @SuppressWarnings("unchecked")
  private void vaultHolds(final List<SecretProperties> listed, final String... values) {
    final PagedIterable<SecretProperties> paged = mock(PagedIterable.class);
    final Iterator<SecretProperties> iterator = listed.iterator();
    when(paged.iterator()).thenReturn(iterator);
    when(client.listPropertiesOfSecrets()).thenReturn(paged);
    for (int i = 0; i < listed.size(); i++) {
      final String name = listed.get(i).getName();
      final String value = i < values.length ? values[i] : "valor-" + name;
      when(client.getSecret(name)).thenReturn(new KeyVaultSecret(name, value));
    }
  }

  @Test
  void sinPrefijoLeeTodosLosHabilitadosConNombreDeVariable() {
    vaultHolds(
        List.of(properties("MONGO-PASSWORD", true), properties("BREVO-API-KEY", true)), "m", "b");

    assertThat(KeyVaultSecretsLoader.loadFrom(client, null))
        .containsEntry("MONGO_PASSWORD", "m")
        .containsEntry("BREVO_API_KEY", "b")
        .hasSize(2);
  }

  @Test
  void noPideElValorDeLosSecretosDeshabilitados() {
    vaultHolds(List.of(properties("APAGADO", false), properties("ACTIVO", true)));

    assertThat(KeyVaultSecretsLoader.loadFrom(client, "")).containsOnlyKeys("ACTIVO");

    verify(client, never()).getSecret("APAGADO");
  }

  @Test
  void conPrefijoSoloLeeLosSecretosPropiosYDescartaElPrefijo() {
    vaultHolds(
        List.of(
            properties("NOTIFICATION-MONGO-PASSWORD", true),
            properties("OTRO-SERVICIO-TOKEN", true),
            properties("NOTIFICATION-", true)),
        "m");

    assertThat(KeyVaultSecretsLoader.loadFrom(client, "NOTIFICATION-"))
        .containsOnlyKeys("MONGO_PASSWORD");

    verify(client, never()).getSecret("OTRO-SERVICIO-TOKEN");
    verify(client, never()).getSecret("NOTIFICATION-");
    verify(client).getSecret("NOTIFICATION-MONGO-PASSWORD");
  }

  @Test
  void unVaultVacioNoDevuelveNada() {
    vaultHolds(List.of());

    assertThat(KeyVaultSecretsLoader.loadFrom(client, "NOTIFICATION-")).isEmpty();
    verify(client, never()).getSecret(anyString());
  }
}
