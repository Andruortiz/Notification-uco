package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthJwtProperties.class, AuthPlatformProperties.class})
public class SecurityConfig {}
