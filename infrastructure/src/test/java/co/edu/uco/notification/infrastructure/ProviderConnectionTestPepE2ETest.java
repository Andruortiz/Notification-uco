package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.infrastructure.support.GatedProbeProvider;
import co.edu.uco.notification.infrastructure.support.StubSecurityPlatform;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "security.enabled=true",
      "security.pep.enforcement.enabled=true",
      "security.pep.enforcement.allow-insecure-http=true",
      "security.pep.enforcement.application-name=notification-service",
      "security.pep.enforcement.environment=test",
      "security.pep.enforcement.application-credential=test-credential",
      "notification.catalog.channels.EMAIL.providers[0]=gated-probe",
      "notification.catalog.channels.EMAIL.providers[1]=simulated",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderConnectionTestPepE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final StubSecurityPlatform PLATFORM = new StubSecurityPlatform();

  @DynamicPropertySource
  static void platformUrls(final DynamicPropertyRegistry registry) {
    registry.add("security.pep.enforcement.pep-url", PLATFORM::baseUrl);
    registry.add("notification.auth.session.url", PLATFORM::baseUrl);
  }

  @AfterAll
  static void stopPlatform() {
    PLATFORM.close();
  }

  @TestConfiguration
  static class ProbeDoubles {

    @Bean
    GatedProbeProvider gatedProbe() {
      return new GatedProbeProvider("gated-probe");
    }
  }

  @LocalServerPort private int port;

  @Autowired private GatedProbeProvider gatedProbe;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    PLATFORM.reset();
    PLATFORM
        .withSession("cookie-admin", "tenant-1")
        .withSession("cookie-cliente", "tenant-1")
        .withSession("cookie-operador", "tenant-1")
        .denyDecisionsFor("cookie-cliente")
        .denyDecisionsFor("cookie-operador");
    gatedProbe.rearm();
    gatedProbe.release(ProbeOutcome.ok());
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    awaitCatalog();
  }

  private void awaitCatalog() {
    final Instant deadline = Instant.now().plusSeconds(30);
    while (post("cookie-admin").returnResult(String.class).getStatus().value() == 400) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("the channel catalog was never loaded");
      }
      try {
        Thread.sleep(100);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private WebTestClient.ResponseSpec post(final String cookie) {
    return client
        .post()
        .uri("/provider-connection-tests")
        .cookie(StubSecurityPlatform.COOKIE_NAME, cookie)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("channel", "EMAIL", "providerId", "gated-probe"))
        .exchange();
  }

  @Test
  void clienteAndOperadorSessionsAreForbiddenByThePdpAndNoProbeRuns() {
    final int before = gatedProbe.invocations();

    for (int attempt = 0; attempt < 5; attempt++) {
      post("cookie-cliente").expectStatus().isForbidden();
      post("cookie-operador").expectStatus().isForbidden();
    }

    assertEquals(before, gatedProbe.invocations());
  }

  @Test
  void anAdministratorSessionIsAllowedAndTheProbeRuns() {
    final int before = gatedProbe.invocations();

    post("cookie-admin").expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("OK");

    assertEquals(before + 1, gatedProbe.invocations());
  }
}
