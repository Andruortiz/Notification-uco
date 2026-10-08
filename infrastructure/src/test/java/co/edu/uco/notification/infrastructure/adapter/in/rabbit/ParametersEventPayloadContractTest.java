package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.infrastructure.adapter.out.parameters.HttpParametersSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParametersEventPayloadContractTest {

  private static final String ASSUMED_CONTRACT_JSON =
      "{\"version\":12,\"values\":{\"dispatch.max-attempts\":5,"
          + "\"provider.brevo.timeout-ms\":8000,\"requeue.interval-ms\":20000}}";

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void theAssumedContractJsonIsReadByTheEventPayloadAndByTheHttpResponseWithTheSameChange()
      throws IOException {
    final ConfigurationChange fromEvent =
        mapper.readValue(ASSUMED_CONTRACT_JSON, ParametersEventPayload.class).toChange();
    final HttpParametersSource.StateResponse http =
        mapper.readValue(ASSUMED_CONTRACT_JSON, HttpParametersSource.StateResponse.class);

    assertEquals(12, fromEvent.version());
    assertEquals(http.version(), fromEvent.version());
    assertEquals(http.values(), fromEvent.values());
    assertEquals(3, fromEvent.values().size());
  }

  @Test
  void aPayloadSerializedByTheServiceKeepsTheContractShape() throws IOException {
    final Map<String, Object> values = new LinkedHashMap<>();
    values.put("requeue.interval-ms", 20_000);

    final String json = mapper.writeValueAsString(new ParametersEventPayload(7L, values));

    assertEquals("{\"version\":7,\"values\":{\"requeue.interval-ms\":20000}}", json);
    assertEquals(7, mapper.readValue(json, ParametersEventPayload.class).toChange().version());
  }

  @Test
  void unknownTopLevelFieldsAreToleratedSoThePublisherCanAddMetadata() throws IOException {
    final ObjectMapper tolerant =
        new ObjectMapper()
            .configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);

    final ConfigurationChange change =
        tolerant
            .readValue(
                "{\"version\":1,\"values\":{},\"publishedBy\":\"parameters\"}",
                ParametersEventPayload.class)
            .toChange();

    assertEquals(1, change.version());
    assertTrue(change.values().isEmpty());
  }

  @Test
  void aPayloadWithoutVersionOrValuesIsRejected() throws IOException {
    final ParametersEventPayload withoutVersion =
        mapper.readValue("{\"values\":{}}", ParametersEventPayload.class);
    final ParametersEventPayload withoutValues =
        mapper.readValue("{\"version\":1}", ParametersEventPayload.class);

    assertThrows(IllegalArgumentException.class, withoutVersion::toChange);
    assertThrows(IllegalArgumentException.class, withoutValues::toChange);
  }

  @Test
  void theValuesMapOfAPayloadCannotBeMutatedFromOutside() {
    final Map<String, Object> source = new LinkedHashMap<>();
    source.put("dispatch.max-attempts", 4);
    final ParametersEventPayload payload = new ParametersEventPayload(1L, source);

    source.put("injected", 1);

    assertEquals(1, payload.values().size());
    assertThrows(UnsupportedOperationException.class, () -> payload.values().put("x", 1));
  }
}
