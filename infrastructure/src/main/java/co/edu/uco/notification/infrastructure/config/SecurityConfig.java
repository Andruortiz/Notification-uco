package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties({AuthJwtProperties.class, AuthPlatformProperties.class})
public class SecurityConfig {

  @Bean("authJwtSecretGuard")
  @ConditionalOnProperty(
      name = "notification.auth.mode",
      havingValue = "local",
      matchIfMissing = true)
  AuthJwtSecretGuard authJwtSecretGuard(
      final AuthJwtProperties properties, final Environment environment) {
    return new AuthJwtSecretGuard(properties, environment);
  }
}
