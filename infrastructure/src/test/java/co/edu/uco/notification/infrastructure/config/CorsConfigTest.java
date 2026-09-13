package co.edu.uco.notification.infrastructure.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigTest {

  @LocalServerPort private int port;

  @MockBean private GetNotificationStatusUseCase getNotificationStatusUseCase;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void allowedOriginReceivesAccessControlHeaders() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(
            Mono.just(
                new NotificationStatusView(
                    id, NotificationStatus.PENDING, ChannelType.of("EMAIL"), null, Instant.now())));

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
  }

  @Test
  void disallowedOriginIsRejected() {
    webTestClient
        .get()
        .uri("/notifications/{id}", NotificationId.newId().value())
        .header(HttpHeaders.ORIGIN, "http://localhost:9999")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
  }
}
