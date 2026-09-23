package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.Preconditions;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(BrevoProviderProperties.class)
public class BrevoWebClientConfig {

  @Bean
  WebClient brevoWebClient(final BrevoProviderProperties properties) {
    Preconditions.requireNonNull(properties, "properties must not be null");
    final HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs().intValue())
            .responseTimeout(Duration.ofMillis(properties.timeoutMs()));
    return WebClient.builder()
        .baseUrl(properties.baseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
