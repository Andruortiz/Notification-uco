package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParametersEventsPropertiesTest {

  private static ParametersEventsProperties of(
      final String exchange, final String routingKey, final String queue) {
    return new ParametersEventsProperties(
        exchange, null, routingKey, queue, null, null, null, null, null);
  }

  @Test
  void withoutExchangeTheSubscriptionIsInactiveAndNothingElseIsRequired() {
    final ParametersEventsProperties properties = of(null, null, null);

    assertFalse(properties.isActive());
    assertEquals("", properties.queue());
    assertEquals("", properties.dlqQueue());
  }

  @Test
  void aBlankExchangeIsTreatedAsInactive() {
    assertFalse(of("   ", null, null).isActive());
  }

  @Test
  void anExchangeWithoutRoutingKeyFailsNamingTheProperty() {
    final IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> of("exchange", " ", "queue"));

    assertTrue(failure.getMessage().contains("notification.parameters.events.routing-key"));
  }

  @Test
  void anExchangeWithoutQueueFailsNamingTheProperty() {
    final IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> of("exchange", "key", null));

    assertTrue(failure.getMessage().contains("notification.parameters.events.queue"));
  }

  @Test
  void anActiveConfigurationAppliesTheDocumentedDefaultsAndDerivesTheDeadLetterNames() {
    final ParametersEventsProperties properties = of(" exchange ", "key", "events");

    assertTrue(properties.isActive());
    assertEquals("exchange", properties.exchange());
    assertEquals("topic", properties.exchangeType());
    assertEquals("events.dlq", properties.dlqQueue());
    assertEquals("events.dlq", properties.dlqExchange());
    assertEquals("events.dlq", properties.dlqRoutingKey());
    assertEquals(3, properties.maxAttempts());
    assertEquals(1, properties.consumerConcurrency());
  }

  @Test
  void explicitValuesOverrideTheDefaults() {
    final ParametersEventsProperties properties =
        new ParametersEventsProperties(
            "exchange", "direct", "key", "events", "dx", "dk", "dq", 5, 2);

    assertEquals("direct", properties.exchangeType());
    assertEquals("dx", properties.dlqExchange());
    assertEquals("dk", properties.dlqRoutingKey());
    assertEquals("dq", properties.dlqQueue());
    assertEquals(5, properties.maxAttempts());
    assertEquals(2, properties.consumerConcurrency());
  }

  @Test
  void nonPositiveLimitsFallBackToTheDefaults() {
    final ParametersEventsProperties properties =
        new ParametersEventsProperties("e", null, "k", "q", null, null, null, 0, -1);

    assertEquals(3, properties.maxAttempts());
    assertEquals(1, properties.consumerConcurrency());
  }
}
