package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.domain.configuration.ConfigurationChangeOutcome;
import co.edu.uco.notification.core.port.in.ReceivePublishedConfigurationUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.scheduler.ConfigurationEventLogger;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.config.ParametersEventsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import reactor.core.publisher.Mono;

class ParametersEventListenerTest {

  private static final long TAG = 7L;
  private static final String QUEUE = "parameters.queue";

  private final ReceivePublishedConfigurationUseCase useCase =
      mock(ReceivePublishedConfigurationUseCase.class);
  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final MessageRecoverer recoverer = mock(MessageRecoverer.class);
  private final Channel channel = mock(Channel.class);
  private final ConfigurationEventLogger eventLogger = new ConfigurationEventLogger();
  private ListAppender<ILoggingEvent> logAppender;

  private ParametersEventListener listener;

  @BeforeEach
  void setUp() {
    when(rabbitTemplate.invoke(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<RabbitOperations.OperationsCallback<?>>getArgument(0)
                    .doInRabbit(rabbitTemplate));
    listener =
        new ParametersEventListener(
            useCase,
            eventLogger,
            new ObjectMapper(),
            rabbitTemplate,
            recoverer,
            new ParametersEventsProperties(
                "exchange", null, "key", QUEUE, null, null, null, 3, null));
    logAppender = new ListAppender<>();
    logAppender.list = new CopyOnWriteArrayList<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
  }

  private static Message message(final String body, final Integer attempt) {
    final MessageProperties properties = new MessageProperties();
    if (attempt != null) {
      properties.setHeader(ParametersEventListener.ATTEMPT_HEADER, attempt);
    }
    return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
  }

  private String logs() {
    return String.join("\n", logAppender.list.stream().map(LogLines::render).toList());
  }

  private static ConfigurationChangeOutcome applied() {
    return ConfigurationChangeOutcome.applied(Set.of("dispatch.max-attempts"), Set.of(), 1, 2);
  }

  @Test
  void aValidMessageIsAppliedLoggedWithTheEventTransportAndAcknowledged() throws IOException {
    final ArgumentCaptor<ConfigurationChange> received =
        ArgumentCaptor.forClass(ConfigurationChange.class);
    when(useCase.receive(received.capture())).thenReturn(Mono.just(applied()));

    listener.onMessage(
        message("{\"version\":2,\"values\":{\"dispatch.max-attempts\":5}}", null), channel, TAG);

    assertEquals(2, received.getValue().version());
    assertEquals(5, received.getValue().values().get("dispatch.max-attempts"));
    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
    final String logs = logs();
    assertTrue(logs.contains("CONFIG_APPLIED"), logs);
    assertTrue(logs.contains("transport=event"), logs);
    assertTrue(logs.contains("param-"), logs);
  }

  @Test
  void aRejectedChangeIsAcknowledgedWithoutDeadLetterAndLogsTheReasonWithoutValues()
      throws IOException {
    when(useCase.receive(any()))
        .thenReturn(
            Mono.just(
                ConfigurationChangeOutcome.rejected(
                    "dispatch.max-attempts must be between 1 and 20",
                    Set.of("dispatch.max-attempts"),
                    1)));

    listener.onMessage(
        message("{\"version\":2,\"values\":{\"dispatch.max-attempts\":98765}}", null),
        channel,
        TAG);

    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
    final String logs = logs();
    assertTrue(logs.contains("CONFIG_REJECTED"), logs);
    assertTrue(logs.contains("transport=event"), logs);
    assertTrue(!logs.contains("98765"), logs);
  }

  @Test
  void aStaleVersionIsAcknowledgedAndLoggedAsIgnored() throws IOException {
    when(useCase.receive(any()))
        .thenReturn(Mono.just(ConfigurationChangeOutcome.ignoredStale(5, 4)));

    listener.onMessage(message("{\"version\":4,\"values\":{}}", null), channel, TAG);

    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
    assertTrue(logs().contains("CONFIG_IGNORED"), logs());
  }

  @Test
  void aMessageThatIsNotJsonGoesToTheDeadLetterQueueAndNeverReachesTheUseCase() throws IOException {
    listener.onMessage(message("not json", null), channel, TAG);

    verify(recoverer).recover(any(Message.class), any(Throwable.class));
    verify(channel).basicAck(TAG, false);
    verify(useCase, never()).receive(any());
    assertTrue(logs().contains("NTF-3012"), logs());
  }

  @Test
  void aMessageWithoutVersionOrWithoutValuesGoesToTheDeadLetterQueue() throws IOException {
    listener.onMessage(message("{\"values\":{}}", null), channel, TAG);
    listener.onMessage(message("{\"version\":3}", null), channel, TAG + 1);

    verify(recoverer, org.mockito.Mockito.times(2))
        .recover(any(Message.class), any(Throwable.class));
    verify(channel).basicAck(TAG, false);
    verify(channel).basicAck(TAG + 1, false);
    verify(useCase, never()).receive(any());
  }

  @Test
  void anUnexpectedFailureBelowTheLimitIsRepublishedToTheQueueWithTheAttemptIncremented()
      throws IOException {
    when(useCase.receive(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

    listener.onMessage(message("{\"version\":2,\"values\":{}}", null), channel, TAG);

    final ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(""), eq(QUEUE), republished.capture());
    assertEquals(
        1,
        republished
            .getValue()
            .getMessageProperties()
            .getHeaders()
            .get(ParametersEventListener.ATTEMPT_HEADER));
    verify(channel).basicAck(TAG, false);
    verify(recoverer, never()).recover(any(), any());
  }

  @Test
  void anUnexpectedFailureOnTheLastAttemptGoesToTheDeadLetterQueue() throws IOException {
    when(useCase.receive(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

    listener.onMessage(message("{\"version\":2,\"values\":{}}", 2), channel, TAG);

    verify(recoverer).recover(any(Message.class), any(Throwable.class));
    verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void aFailedRepublicationRejectsTheMessageWithoutRequeueSoTheBrokerDeadLetters()
      throws IOException {
    when(useCase.receive(any())).thenReturn(Mono.error(new IllegalStateException("boom")));
    doThrow(new org.springframework.amqp.AmqpException("broker down"))
        .when(rabbitTemplate)
        .invoke(any());

    listener.onMessage(message("{\"version\":2,\"values\":{}}", null), channel, TAG);

    verify(channel).basicNack(TAG, false, false);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void anIgnoredCorrelationHeaderFromTheSenderNeverReplacesTheGeneratedParamId()
      throws IOException {
    final MessageProperties properties = new MessageProperties();
    properties.setHeader("x-correlation-id", "attacker-controlled");
    when(useCase.receive(any())).thenReturn(Mono.just(applied()));

    listener.onMessage(
        new Message("{\"version\":2,\"values\":{}}".getBytes(StandardCharsets.UTF_8), properties),
        channel,
        TAG);

    final List<String> lines = logAppender.list.stream().map(LogLines::render).toList();
    assertTrue(lines.stream().anyMatch(line -> line.contains("param-")), lines.toString());
    assertTrue(
        lines.stream().noneMatch(line -> line.contains("attacker-controlled")), lines.toString());
  }
}
