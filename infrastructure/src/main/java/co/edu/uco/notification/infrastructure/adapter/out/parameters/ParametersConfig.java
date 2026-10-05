package co.edu.uco.notification.infrastructure.adapter.out.parameters;

import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.infrastructure.config.ParametersProperties;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(ParametersProperties.class)
public class ParametersConfig {

  @Bean
  public ParametersSourcePort parametersSourcePort(final ParametersProperties properties) {
    if (!properties.hasSource()) {
      return new NoParametersSource();
    }
    final HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs().intValue())
            .responseTimeout(properties.timeout());
    final WebClient webClient =
        WebClient.builder()
            .baseUrl(stripTrailingSlash(properties.baseUrl()))
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    return new HttpParametersSource(webClient, properties.timeout().plus(Duration.ofSeconds(1)));
  }

  private static String stripTrailingSlash(final String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }
}
