package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import co.edu.uco.notification.core.port.in.ExpireAbandonedUploadsUseCase;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class AbandonedUploadsSchedulerAdapter {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AbandonedUploadsSchedulerAdapter.class);

  private final ExpireAbandonedUploadsUseCase expireAbandonedUploadsUseCase;

  public AbandonedUploadsSchedulerAdapter(
      final ExpireAbandonedUploadsUseCase expireAbandonedUploadsUseCase) {
    this.expireAbandonedUploadsUseCase =
        Preconditions.requireNonNull(
            expireAbandonedUploadsUseCase, "expireAbandonedUploadsUseCase must not be null");
  }

  @Scheduled(fixedDelayString = "${notification.attachments.sweeper.interval-ms:60000}")
  public void expireAbandonedUploads() {
    expireAbandonedUploadsUseCase
        .expire()
        .doOnNext(
            failed -> {
              if (failed > 0) {
                LOGGER.info(LogFields.fields("failedUploads", failed), "Abandoned uploads failed");
              }
            })
        .onErrorResume(
            error -> {
              LOGGER.warn(
                  LogFields.failure(ErrorCode.ABANDONED_UPLOADS_SWEEP_FAILED),
                  "Abandoned uploads sweep failed",
                  error);
              return Mono.empty();
            })
        .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "sweep-" + UUID.randomUUID()))
        .subscribe();
  }
}
