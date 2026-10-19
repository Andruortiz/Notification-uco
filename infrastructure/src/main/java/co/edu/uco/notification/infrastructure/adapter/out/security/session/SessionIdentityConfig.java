package co.edu.uco.notification.infrastructure.adapter.out.security.session;

import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.core.port.out.SessionIdentityPort;
import co.edu.uco.notification.infrastructure.config.AuthSessionProperties;
import co.edu.uco.notification.infrastructure.config.PepDelegation;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(AuthSessionProperties.class)
public class SessionIdentityConfig {

  private static final int CONNECT_TIMEOUT_MS = 2_000;

  @Bean
  public SessionIdentityPort sessionIdentityPort(
      final AuthSessionProperties properties, final Environment environment) {
    if (!PepDelegation.isActive(environment)) {
      return credential ->
          Mono.error(
              new InvalidTokenException(
                  "session cookie requires the PEP to be active",
                  InvalidTokenException.Reason.INVALID_SESSION,
                  null,
                  null));
    }
    if (properties.url().isEmpty()) {
      throw new IllegalStateException(
          "notification.auth.session.url is required when the PEP is active");
    }
    final HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(properties.timeout());
    final WebClient webClient =
        WebClient.builder()
            .baseUrl(stripTrailingSlash(properties.url()))
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    return new PdpSessionIdentityAdapter(
        webClient, properties.path(), properties.timeout().plus(Duration.ofSeconds(1)));
  }

  private static String stripTrailingSlash(final String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }
}
