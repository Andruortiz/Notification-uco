package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import reactor.core.publisher.Mono;

final class StorageCleanup {

  private StorageCleanup() {}

  static Mono<Void> bestEffort(final Mono<Void> deletion) {
    return deletion.onErrorResume(
        AttachmentInspectionUnavailableException.class, e -> Mono.empty());
  }
}
