package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FcmProviderProperties.class)
public class FcmProviderConfig {

  @Bean
  FcmCredentials fcmCredentials(final FcmProviderProperties properties) {
    return FcmCredentials.load(properties);
  }
}
