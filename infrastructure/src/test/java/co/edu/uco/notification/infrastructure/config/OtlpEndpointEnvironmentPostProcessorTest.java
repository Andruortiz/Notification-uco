package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OtlpEndpointEnvironmentPostProcessorTest {

  private final OtlpEndpointEnvironmentPostProcessor processor =
      new OtlpEndpointEnvironmentPostProcessor();

  private String endpointFor(final MockEnvironment environment) {
    processor.postProcessEnvironment(environment, null);
    return environment.getProperty("management.otlp.tracing.endpoint");
  }

  @Test
  void withoutAnyEndpointNothingIsConfiguredSoThereIsNoExporter() {
    assertNull(endpointFor(new MockEnvironment()));
  }

  @Test
  void aBlankVariableConfiguresNothing() {
    assertNull(
        endpointFor(new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "  ")));
  }

  @Test
  void theBaseUrlOfTheStandardVariableGetsTheTracesPath() {
    assertEquals(
        "http://collector:4318/v1/traces",
        endpointFor(
            new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318/")));
    assertEquals(
        "http://collector:4318/v1/traces",
        endpointFor(
            new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318")));
  }

  @Test
  void aFullTracesUrlIsKeptAsIs() {
    assertEquals(
        "http://collector:4318/v1/traces",
        endpointFor(
            new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318/v1/traces")));
  }

  @Test
  void anExplicitPropertyWinsOverTheVariable() {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://other:4318")
            .withProperty("management.otlp.tracing.endpoint", "http://explicit:4318/v1/traces");

    assertEquals("http://explicit:4318/v1/traces", endpointFor(environment));
  }
}
