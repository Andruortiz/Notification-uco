package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.retry.interceptor.StatefulRetryOperationsInterceptor;

class RabbitRetryConfigTest {

  private static Message poisonedMessage(final String messageId) {
    final MessageProperties properties = new MessageProperties();
    properties.setMessageId(messageId);
    properties.setRedelivered(false);
    return new Message("payload".getBytes(StandardCharsets.UTF_8), properties);
  }

  private static void markRedelivered(final Message message) {
    message.getMessageProperties().setRedelivered(true);
  }

  private static MethodInvocation redeliveryOf(final Message message, final Object result)
      throws Throwable {
    final MethodInvocation invocation = mock(MethodInvocation.class);
    when(invocation.getArguments()).thenReturn(new Object[] {null, message});
    final Method method = Object.class.getMethod("toString");
    when(invocation.getMethod()).thenReturn(method);
    when(invocation.getStaticPart()).thenReturn((AccessibleObject) method);
    if (result instanceof Throwable throwable) {
      when(invocation.proceed()).thenThrow(throwable);
    } else {
      when(invocation.proceed()).thenReturn(result);
    }
    return invocation;
  }

  @Test
  void exhaustingMaxAttemptsMovesTheMessageToTheRecovererExactlyOnce() throws Throwable {
    final MessageRecoverer recoverer = mock(MessageRecoverer.class);
    final StatefulRetryOperationsInterceptor interceptor =
        RetryInterceptorBuilder.stateful().maxAttempts(3).recoverer(recoverer).build();
    final Message message = poisonedMessage("poison-1");
    final RuntimeException failure = new RuntimeException("boom");

    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    markRedelivered(message);
    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));

    verify(recoverer).recover(same(message), eq(failure));
  }

  @Test
  void aSuccessBeforeExhaustingAttemptsNeverReachesTheRecoverer() throws Throwable {
    final MessageRecoverer recoverer = mock(MessageRecoverer.class);
    final StatefulRetryOperationsInterceptor interceptor =
        RetryInterceptorBuilder.stateful().maxAttempts(3).recoverer(recoverer).build();
    final Message message = poisonedMessage("poison-2");
    final RuntimeException failure = new RuntimeException("transient");

    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    markRedelivered(message);
    final Object result = interceptor.invoke(redeliveryOf(message, "processed"));

    assertEquals("processed", result);
    verify(recoverer, never()).recover(same(message), eq(failure));
  }

  @Test
  void aFailureInTheRecovererItselfPropagatesInsteadOfSwallowingTheMessage() throws Throwable {
    final MessageRecoverer recoverer = mock(MessageRecoverer.class);
    final RuntimeException recovererFailure = new IllegalStateException("broker unavailable");
    final Message message = poisonedMessage("poison-3");
    final RuntimeException failure = new RuntimeException("boom");
    org.mockito.Mockito.doThrow(recovererFailure)
        .when(recoverer)
        .recover(same(message), eq(failure));
    final StatefulRetryOperationsInterceptor interceptor =
        RetryInterceptorBuilder.stateful().maxAttempts(3).recoverer(recoverer).build();

    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    markRedelivered(message);
    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    assertThrows(RuntimeException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
    assertThrows(
        IllegalStateException.class, () -> interceptor.invoke(redeliveryOf(message, failure)));
  }
}
