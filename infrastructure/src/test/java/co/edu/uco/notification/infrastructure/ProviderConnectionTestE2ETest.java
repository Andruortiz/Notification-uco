package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.infrastructure.adapter.out.provider.FakeProviderServer;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.GatedProbeProvider;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
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
      "notification.provider.probe.timeout=1500ms",
      "notification.provider.simulated.result=ACCEPTED",
      "notification.provider.brevo.api-key=" + ProviderConnectionTestE2ETest.SENTINEL,
      "notification.provider.brevo.sender-email=sender@example.com",
      "notification.catalog.channels.EMAIL.providers[0]=brevo",
      "notification.catalog.channels.EMAIL.providers[1]=simulated",
      "notification.catalog.channels.EMAIL.providers[2]=gated-probe",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class ProviderConnectionTestE2ETest {

  static final String SENTINEL = "sentinel-credential-9f3a7c";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final FakeProviderServer BREVO = FakeProviderServer.start();

  @DynamicPropertySource
  static void brevoDouble(final DynamicPropertyRegistry registry) {
    registry.add("notification.provider.brevo.base-url", BREVO::baseUrl);
  }

  @AfterAll
  static void stopBrevo() {
    BREVO.stop();
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
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    BREVO.reset();
    gatedProbe.rearm();
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    awaitCatalog();
    logs = new ConcurrentListAppender();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
  }

  private WebTestClient.ResponseSpec post(
      final String tenant, final Role role, final String channel, final String providerId) {
    return client
        .post()
        .uri("/provider-connection-tests")
        .header("Authorization", TestTokens.bearer(tenant, role))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("channel", channel, "providerId", providerId))
        .exchange();
  }

  private Map<String, Object> admin(final String channel, final String providerId) {
    final EntityExchangeResult<Map<String, Object>> result =
        post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, channel, providerId)
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult();
    assertNotNull(result.getResponseBody());
    return result.getResponseBody();
  }

  private String everythingLogged() {
    return logs.list.stream()
        .map(
            event ->
                event.getFormattedMessage() + event.getMarkerList() + event.getMDCPropertyMap())
        .collect(Collectors.joining("\n"));
  }

  @Test
  void simulatedAnswersOkInUnderOneSecondOnEveryChannel() {
    for (final String channel : List.of("EMAIL", "SMS", "PUSH")) {
      final Instant startedAt = Instant.now();
      final Map<String, Object> body = admin(channel, "simulated");
      final Duration elapsed = Duration.between(startedAt, Instant.now());

      assertEquals("OK", body.get("status"), channel);
      assertEquals("simulated", body.get("providerId"));
      assertEquals(channel, body.get("channel"));
      assertEquals(null, body.get("errorCode"));
      assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, channel + " took " + elapsed);
    }
  }

  @Test
  void aProbeThatNeverAnswersEndsInTimeoutWithinTheLimitPlusOneSecond() {
    admin("EMAIL", "simulated");
    final Instant startedAt = Instant.now();
    final Map<String, Object> body = admin("EMAIL", "gated-probe");
    final Duration elapsed = Duration.between(startedAt, Instant.now());

    assertEquals("FAILED", body.get("status"));
    assertEquals("TIMEOUT", body.get("errorCode"));
    assertTrue(elapsed.compareTo(Duration.ofMillis(1500 + 1000)) <= 0, "took " + elapsed);
    assertTrue(((Number) body.get("latencyMs")).longValue() >= 1400L);
  }

  @Test
  void aRealProviderIsProbedAgainstItsHttpDoubleAndItsRejectionLeaksNothing() {
    BREVO.nextResponse(200, "{\"email\":\"owner@example.com\"}");
    final Map<String, Object> ok = admin("EMAIL", "brevo");
    assertEquals("OK", ok.get("status"));
    assertEquals("/v3/account", BREVO.requests().get(0).path());

    BREVO.nextResponse(401, "{\"message\":\"invalid key " + SENTINEL + "\"}");
    final EntityExchangeResult<String> rejected =
        post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "EMAIL", "brevo")
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult();

    assertTrue(rejected.getResponseBody().contains("AUTH_REJECTED"));
    assertFalse(rejected.getResponseBody().contains(SENTINEL));
    assertFalse(rejected.getResponseHeaders().toString().contains(SENTINEL));
    assertFalse(everythingLogged().contains(SENTINEL));
  }

  @Test
  void aProviderWithoutCredentialsIsReportedWithoutAnyNetworkCall() {
    final Map<String, Object> body = admin("SMS", "twilio");

    assertEquals("FAILED", body.get("status"));
    assertEquals("CREDENTIALS_MISSING", body.get("errorCode"));
  }

  @Test
  void aProviderOutsideTheChannelAndAnUnknownChannelAreBadRequests() {
    post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "SMS", "brevo").expectStatus().isBadRequest();
    post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "FAX", "simulated")
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void clienteAndOperadorAreForbiddenAndNoProbeIsInvoked() {
    final int gatedBefore = gatedProbe.invocations();
    final int brevoBefore = BREVO.requests().size();

    admin("EMAIL", "simulated");
    for (final Role role : List.of(Role.CLIENTE, Role.OPERADOR)) {
      post("tenant-1", role, "EMAIL", "brevo").expectStatus().isForbidden();
      post("tenant-1", role, "EMAIL", "gated-probe").expectStatus().isForbidden();
    }

    assertEquals(gatedBefore, gatedProbe.invocations());
    assertEquals(brevoBefore, BREVO.requests().size());
  }

  @Test
  void aSecondTestOfTheSamePairIsRejectedWhileAnotherChannelAndTheNextTestSucceed() {
    final CompletableFuture<Map<String, Object>> first =
        CompletableFuture.supplyAsync(() -> admin("EMAIL", "gated-probe"));
    awaitInvocations(gatedProbe.invocations() + 1);

    post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "EMAIL", "gated-probe")
        .expectStatus()
        .isEqualTo(429)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-3060");
    assertEquals("OK", admin("SMS", "simulated").get("status"));

    gatedProbe.release(ProbeOutcome.ok());
    assertEquals("OK", first.join().get("status"));

    gatedProbe.rearm();
    gatedProbe.release(ProbeOutcome.ok());
    assertEquals("OK", admin("EMAIL", "gated-probe").get("status"));
  }

  @Test
  void twoTenantsGetTheSameResultAndNoNotificationIsCreated() {
    final Map<String, Object> first =
        post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "PUSH", "simulated")
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    final Map<String, Object> second =
        post("tenant-2", Role.ADMINISTRADOR_PLATAFORMA, "PUSH", "simulated")
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertEquals(first.get("status"), second.get("status"));
    assertEquals(first.get("errorCode"), second.get("errorCode"));
    for (final String tenant : List.of("tenant-1", "tenant-2")) {
      client
          .get()
          .uri("/notifications?limit=100")
          .header("Authorization", TestTokens.bearer(tenant, Role.OPERADOR))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(0);
    }
  }

  @Test
  void everyChannelOfTheTestProfileHasAtLeastTwoProbableProviders() {
    final Map<String, Object> catalog =
        client
            .get()
            .uri("/channels")
            .header("Authorization", TestTokens.bearer("tenant-1", Role.CLIENTE))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> items = (List<Map<String, Object>>) catalog.get("items");
    assertEquals(4, items.size());
    for (final Map<String, Object> channel : items) {
      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> providers =
          (List<Map<String, Object>>) channel.get("providers");
      assertTrue(providers.size() >= 2, String.valueOf(channel.get("channelType")));
      for (final Map<String, Object> provider : providers) {
        final Map<String, Object> result =
            admin(
                String.valueOf(channel.get("channelType")),
                String.valueOf(provider.get("providerId")));
        assertNotNull(result.get("status"));
        assertFalse("ADAPTER_MISSING".equals(result.get("errorCode")));
      }
    }
  }

  private void awaitCatalog() {
    final Instant deadline = Instant.now().plusSeconds(30);
    while (post("tenant-1", Role.ADMINISTRADOR_PLATAFORMA, "EMAIL", "simulated")
            .returnResult(String.class)
            .getStatus()
            .value()
        == 400) {
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

  private void awaitInvocations(final int expected) {
    final Instant deadline = Instant.now().plusSeconds(10);
    while (gatedProbe.invocations() < expected) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("the gated probe was never invoked");
      }
      try {
        Thread.sleep(20);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
