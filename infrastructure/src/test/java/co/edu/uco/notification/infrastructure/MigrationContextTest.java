package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationDelivered;
import co.edu.uco.notification.core.domain.event.NotificationDiscarded;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.domain.event.NotificationRecoverable;
import co.edu.uco.notification.core.domain.event.NotificationRequeued;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.adapter.out.rabbit.AttachmentScanRequest;
import co.edu.uco.notification.utils.CorrelationId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.WebFilter;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "RABBITMQ_USERNAME=test",
      "RABBITMQ_PASSWORD=test"
    })
class MigrationContextTest {

  @Autowired private ObjectMapper mapper;
  @Autowired private ApplicationContext context;

  private static Map<String, String> reference() throws IOException {
    final Map<String, String> events = new LinkedHashMap<>();
    final String content =
        new String(
            new ClassPathResource("reference/events.tsv").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    for (final String line : content.split("\r?\n")) {
      if (!line.isBlank()) {
        final int tab = line.indexOf('\t');
        events.put(line.substring(0, tab), line.substring(tab + 1));
      }
    }
    return events;
  }

  @Test
  void theContextExposesAJacksonThreeMapperWithoutAlphabeticalOrdering() {
    assertFalse(mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
  }

  @Test
  void theEventsAndMessagesSerializeExactlyAsBeforeTheMigration() throws IOException {
    final NotificationId id = NotificationId.of("notif-1");
    final TenantId tenant = TenantId.of("tenant-1");
    final CorrelationId correlation = CorrelationId.of("corr-1");
    final Instant at = Instant.parse("2026-01-02T03:04:05.123456Z");
    final Map<String, String> actual = new LinkedHashMap<>();
    actual.put(
        "NotificationAccepted",
        mapper.writeValueAsString(new NotificationAccepted(id, tenant, correlation, at)));
    actual.put(
        "NotificationQueued",
        mapper.writeValueAsString(new NotificationQueued(id, tenant, correlation, at)));
    actual.put(
        "NotificationDelivered",
        mapper.writeValueAsString(new NotificationDelivered(id, tenant, correlation, at)));
    actual.put(
        "NotificationFailed",
        mapper.writeValueAsString(new NotificationFailed(id, tenant, correlation, at)));
    actual.put(
        "NotificationRecoverable",
        mapper.writeValueAsString(new NotificationRecoverable(id, tenant, correlation, at)));
    actual.put(
        "NotificationRequeued",
        mapper.writeValueAsString(new NotificationRequeued(id, tenant, correlation, at)));
    actual.put(
        "NotificationDiscarded",
        mapper.writeValueAsString(new NotificationDiscarded(id, tenant, null, at)));
    actual.put(
        "AttachmentScanRequest",
        mapper.writeValueAsString(new AttachmentScanRequest("tenant-1", "upload-1")));

    assertEquals(reference(), actual);
  }

  @Test
  void aMessageProducedByTheOldVersionStillDeserializes() throws IOException {
    final AttachmentScanRequest request =
        mapper.readValue(reference().get("AttachmentScanRequest"), AttachmentScanRequest.class);

    assertEquals(new AttachmentScanRequest("tenant-1", "upload-1"), request);
  }

  @Test
  void theMapperToleratesUnknownFieldsInConsumedMessages() throws IOException {
    final AttachmentScanRequest request =
        mapper.readValue(
            "{\"tenantId\":\"t\",\"uploadId\":\"u\",\"extra\":1}", AttachmentScanRequest.class);

    assertEquals("t", request.tenantId());
  }

  @Test
  void thePepFilterIsNotRegisteredWhenEnforcementIsDisabled() {
    final List<String> filters =
        context.getBeansOfType(WebFilter.class).values().stream()
            .map(filter -> filter.getClass().getName())
            .toList();

    assertTrue(filters.stream().noneMatch(name -> name.contains("PepEnforcementWebFilter")));
  }
}
