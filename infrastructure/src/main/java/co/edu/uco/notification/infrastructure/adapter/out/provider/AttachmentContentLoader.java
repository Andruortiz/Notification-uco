package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AttachmentContentLoader {

  private final AttachmentStoragePort storage;

  public AttachmentContentLoader(final AttachmentStoragePort storage) {
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
  }

  public record LoadedAttachment(String fileName, String contentBase64) {}

  public static final class AttachmentContentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttachmentContentException(final String message) {
      super(message);
    }
  }

  public Mono<List<LoadedAttachment>> load(final List<Attachment> attachments) {
    Preconditions.requireNonNull(attachments, "attachments must not be null");
    return Flux.fromIterable(attachments).concatMap(this::load).collectList();
  }

  private Mono<LoadedAttachment> load(final Attachment attachment) {
    final Mono<byte[]> content =
        switch (attachment.source()) {
          case AttachmentSource.EmbeddedContent embedded -> Mono.just(embedded.bytes());
          case AttachmentSource.StoredObject stored -> readStored(stored);
        };
    return content.map(bytes -> verified(attachment, bytes));
  }

  private Mono<byte[]> readStored(final AttachmentSource.StoredObject stored) {
    final String key = stored.objectKey();
    return storage
        .stat(key)
        .switchIfEmpty(
            Mono.error(new AttachmentContentException("the stored attachment no longer exists")))
        .flatMap(info -> storage.read(key, info.etag()))
        .onErrorMap(
            AttachmentObjectChangedException.class,
            error -> new AttachmentContentException("the stored attachment changed"));
  }

  private static LoadedAttachment verified(final Attachment attachment, final byte[] bytes) {
    if (!Sha256Digest.of(bytes).equals(attachment.sha256())) {
      throw new AttachmentContentException("the attachment does not match its recorded digest");
    }
    return new LoadedAttachment(attachment.fileName(), Base64.getEncoder().encodeToString(bytes));
  }
}
