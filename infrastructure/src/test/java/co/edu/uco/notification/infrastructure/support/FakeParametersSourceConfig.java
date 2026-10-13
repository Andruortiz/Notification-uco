package co.edu.uco.notification.infrastructure.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeParametersSourceConfig {

  @Bean
  @Primary
  FakeParametersSource fakeParametersSource() {
    return new FakeParametersSource();
  }
}
