package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ProviderHttpClientsTest {

  private static final String BASE_URL = "http://localhost:9";

  private static ConfigurationSnapshot snapshot(
      final long brevoTimeout, final long brevoConnect, final long twilioTimeout) {
    final Map<String, Long> values = new HashMap<>();
    values.put(ParameterRegistry.timeoutKey("brevo"), brevoTimeout);
    values.put(ParameterRegistry.connectTimeoutKey("brevo"), brevoConnect);
    values.put(ParameterRegistry.timeoutKey("twilio"), twilioTimeout);
    values.put(ParameterRegistry.connectTimeoutKey("twilio"), 5_000L);
    return new ConfigurationSnapshot(
        1, ConfigurationSource.DEFAULTS, values, Instant.now(), Set.of());
  }

  @Test
  void sameValuesReturnTheSameClient() {
    final ProviderHttpClients clients = new ProviderHttpClients(WebClient.builder());

    final WebClient first = clients.client("brevo", BASE_URL, snapshot(10_000, 5_000, 10_000));
    final WebClient second = clients.client("brevo", BASE_URL, snapshot(10_000, 5_000, 20_000));

    assertSame(first, second);
  }

  @Test
  void aDifferentResponseTimeoutProducesANewClientAndThePreviousOneStaysAvailable() {
    final ProviderHttpClients clients = new ProviderHttpClients(WebClient.builder());
    final WebClient previous = clients.client("brevo", BASE_URL, snapshot(10_000, 5_000, 10_000));

    final WebClient next = clients.client("brevo", BASE_URL, snapshot(20_000, 5_000, 10_000));

    assertNotSame(previous, next);
    assertSame(previous, clients.client("brevo", BASE_URL, snapshot(10_000, 5_000, 10_000)));
    assertSame(next, clients.client("brevo", BASE_URL, snapshot(20_000, 5_000, 10_000)));
  }

  @Test
  void aDifferentConnectTimeoutProducesANewClient() {
    final ProviderHttpClients clients = new ProviderHttpClients(WebClient.builder());

    final WebClient previous = clients.client("brevo", BASE_URL, snapshot(10_000, 5_000, 10_000));
    final WebClient next = clients.client("brevo", BASE_URL, snapshot(10_000, 6_000, 10_000));

    assertNotSame(previous, next);
  }

  @Test
  void eachProviderHasItsOwnClientEvenWithEqualValues() {
    final ProviderHttpClients clients = new ProviderHttpClients(WebClient.builder());
    final ConfigurationSnapshot snapshot = snapshot(10_000, 5_000, 10_000);

    assertNotSame(
        clients.client("brevo", BASE_URL, snapshot), clients.client("twilio", BASE_URL, snapshot));
  }

  @Test
  void rejectsNullArguments() {
    final ProviderHttpClients clients = new ProviderHttpClients(WebClient.builder());
    final ConfigurationSnapshot snapshot = snapshot(10_000, 5_000, 10_000);

    assertThrows(NullPointerException.class, () -> clients.client(null, BASE_URL, snapshot));
    assertThrows(NullPointerException.class, () -> clients.client("brevo", null, snapshot));
    assertThrows(NullPointerException.class, () -> clients.client("brevo", BASE_URL, null));
  }
}
