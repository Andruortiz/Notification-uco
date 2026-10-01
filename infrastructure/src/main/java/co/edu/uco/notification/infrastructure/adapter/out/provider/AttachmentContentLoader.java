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

/**
 * Obtiene el contenido de los adjuntos de una notificacion al momento de despacharla: el embebido
 * ya trae sus bytes y el subido se lee del almacen. Antes de entregarlo comprueba que coincide con
 * la huella SHA-256 que se registro al aceptarlo, de modo que un archivo alterado nunca llega al
 * proveedor.
 */
@Component
public class AttachmentContentLoader {

  private final AttachmentStoragePort storage;

  public AttachmentContentLoader(final AttachmentStoragePort storage) {
    this.storage = Preconditions.requireNonNull(storage, "storage must not be null");
  }

  /** Adjunto listo para enviar: su nombre y su contenido en Base64. */
  public record LoadedAttachment(String fileName, String contentBase64) {}

  /**
   * El adjunto no se puede entregar y reintentar no lo arregla: el archivo ya no existe, cambio o
   * no coincide con su huella. Cualquier otro error (por ejemplo, el almacen caido) se propaga tal
   * cual para que el despacho lo trate como recuperable.
   */
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
