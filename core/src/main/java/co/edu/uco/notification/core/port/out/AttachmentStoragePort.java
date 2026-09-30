package co.edu.uco.notification.core.port.out;

import java.time.Duration;
import java.util.Optional;
import reactor.core.publisher.Mono;

public interface AttachmentStoragePort {

  Mono<PresignedUpload> presignUpload(String key, Duration expiresIn);

  Mono<StoredObjectInfo> stat(String key);

  Mono<byte[]> read(String key, String etag);

  Mono<Void> copyIfMatch(String sourceKey, String targetKey, String etag);

  Mono<Void> delete(String key);

  Optional<String> parseUploadUrl(String url);
}
