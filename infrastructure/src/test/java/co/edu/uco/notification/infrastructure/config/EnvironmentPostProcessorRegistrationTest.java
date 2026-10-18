package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;

class EnvironmentPostProcessorRegistrationTest {

  @Test
  void bothPostProcessorsAreRegisteredUnderTheContractBootReads() {
    final List<String> registered =
        SpringFactoriesLoader.loadFactoryNames(
            EnvironmentPostProcessor.class, getClass().getClassLoader());

    assertTrue(
        registered.contains(KeyVaultEnvironmentPostProcessor.class.getName()),
        registered.toString());
    assertTrue(
        registered.contains(OtlpEndpointEnvironmentPostProcessor.class.getName()),
        registered.toString());
  }
}
