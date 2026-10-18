package co.edu.uco.notification.infrastructure.adapter.out.storage;

import co.edu.uco.notification.utils.Preconditions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("attachmentStorageHealthIndicator")
public class AttachmentStorageHealthIndicator implements ReactiveHealthIndicator {

  private final MinioAttachmentStorageAdapter storage;

  public AttachmentStorageHealthIndicator(final MinioAttachmentStorageAdapter storage) {
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
  }

  @Override
  public Mono<Health> health() {
    return storage.ping().map(up -> up ? Health.up().build() : Health.down().build());
  }
}
