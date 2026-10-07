package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.utils.Preconditions;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Component
public class ProviderHttpClients {

  private record ClientKey(
      String providerId, String baseUrl, long responseTimeoutMs, long connectTimeoutMs) {}

  private final Map<ClientKey, WebClient> clients = new ConcurrentHashMap<>();
  private final WebClient.Builder webClientBuilder;

  public ProviderHttpClients(final WebClient.Builder webClientBuilder) {
    this.webClientBuilder =
        Preconditions.requireNonNull(webClientBuilder, "webClientBuilder must not be null");
  }

  public WebClient client(
      final String providerId, final String baseUrl, final ConfigurationSnapshot snapshot) {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    Preconditions.requireNonNull(baseUrl, "baseUrl must not be null");
    Preconditions.requireNonNull(snapshot, "snapshot must not be null");
    final ClientKey key =
        new ClientKey(
            providerId,
            baseUrl,
            snapshot.providerTimeoutMs(providerId),
            snapshot.providerConnectTimeoutMs(providerId));
    return clients.computeIfAbsent(key, this::build);
  }

  static Supplier<WebClient> providerClientSource(
      final String providerId,
      final String baseUrl,
      final ProviderHttpClients providerHttpClients,
      final ConfigurationView configurationView) {
    Preconditions.requireNonNull(providerHttpClients, "providerHttpClients must not be null");
    Preconditions.requireNonNull(configurationView, "configurationView must not be null");
    return () -> providerHttpClients.client(providerId, baseUrl, configurationView.snapshot());
  }

  static Supplier<WebClient> fixedClientSource(final WebClient fixedClient) {
    Preconditions.requireNonNull(fixedClient, "fixedClient must not be null");
    return () -> fixedClient;
  }

  private WebClient build(final ClientKey key) {
    final HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(key.connectTimeoutMs()))
            .responseTimeout(Duration.ofMillis(key.responseTimeoutMs()));
    return webClientBuilder
        .clone()
        .baseUrl(key.baseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
