package co.edu.uco.notification.infrastructure.config;

import io.micrometer.observation.ObservationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

  @Bean
  ObservationFilter observationCorrelationFilter() {
    return new ObservationCorrelationFilter();
  }
}
