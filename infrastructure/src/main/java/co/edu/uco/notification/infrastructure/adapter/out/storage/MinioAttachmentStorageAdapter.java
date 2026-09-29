package co.edu.uco.notification.infrastructure.adapter.out.storage;

import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.PresignedUpload;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.utils.Preconditions;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class MinioAttachmentStorageAdapter implements AttachmentStoragePort {

  private static final String REGION = "us-east-1";
  private static final Set<String> MISSING = Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket");
  private static final Set<String> ALREADY_EXISTS =
      Set.of("BucketAlreadyOwnedByYou", "BucketAlreadyExists");
  private static final String PRECONDITION_FAILED = "PreconditionFailed";

  private final MinioAsyncClient client;
  private final MinioAsyncClient signer;
  private final String bucket;
  private final URI publicEndpoint;
  private final Mono<Void> bucketReady;

  public MinioAttachmentStorageAdapter(final AttachmentProperties properties) {
    Preconditions.requireNonNull(properties, "properties must not be null");
    final AttachmentProperties.Storage storage = properties.storage();
    this.bucket = Preconditions.requireNonBlank(storage.bucket(), "bucket must not be blank");
    this.publicEndpoint = URI.create(storage.publicEndpoint());
    this.client = isConfigured(storage) ? clientFor(storage.endpoint(), storage) : null;
    this.signer = isConfigured(storage) ? clientFor(storage.publicEndpoint(), storage) : null;
    this.bucketReady =
        Mono.defer(this::createBucketIfMissing)
            .cache(ready -> Duration.ofDays(1), error -> Duration.ZERO, () -> Duration.ofDays(1));
  }

  private static boolean isConfigured(final AttachmentProperties.Storage storage) {
    return storage.accessKey() != null
        && !storage.accessKey().isBlank()
        && storage.secretKey() != null
        && !storage.secretKey().isBlank();
  }

  private MinioAsyncClient client() {
    return requireConfigured(client);
  }

  private MinioAsyncClient signer() {
    return requireConfigured(signer);
  }

  private static MinioAsyncClient requireConfigured(final MinioAsyncClient configured) {
    if (configured == null) {
      throw new AttachmentInspectionUnavailableException("the file storage is not configured");
    }
    return configured;
  }

  private static MinioAsyncClient clientFor(
      final String endpoint, final AttachmentProperties.Storage storage) {
    return MinioAsyncClient.builder()
        .endpoint(endpoint)
        .region(REGION)
        .credentials(storage.accessKey(), storage.secretKey())
        .build();
  }

  @Override
  public Mono<PresignedUpload> presignUpload(final String key, final Duration expiresIn) {
    Preconditions.requireNonBlank(key, "key must not be blank");
    Preconditions.requireNonNull(expiresIn, "expiresIn must not be null");
    return bucketReady
        .then(
            Mono.fromCallable(
                () ->
                    new PresignedUpload(
                        signer()
                            .getPresignedObjectUrl(
                                GetPresignedObjectUrlArgs.builder()
                                    .method(Method.PUT)
                                    .bucket(bucket)
                                    .object(key)
                                    .expiry((int) expiresIn.toSeconds())
                                    .build()),
                        Instant.now().plus(expiresIn))))
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  @Override
  public Mono<StoredObjectInfo> stat(final String key) {
    return call(() ->
            client().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()))
        .map(response -> new StoredObjectInfo(response.size(), response.etag()))
        .onErrorResume(MinioAttachmentStorageAdapter::isMissing, error -> Mono.empty())
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  @Override
  public Mono<byte[]> read(final String key, final String etag) {
    return call(() ->
            client()
                .getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).matchETag(etag).build()))
        .publishOn(Schedulers.boundedElastic())
        .map(MinioAttachmentStorageAdapter::readAll)
        .onErrorMap(
            MinioAttachmentStorageAdapter::isPreconditionFailed,
            error -> new AttachmentObjectChangedException())
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  @Override
  public Mono<Void> copyIfMatch(final String sourceKey, final String targetKey, final String etag) {
    return call(() ->
            client()
                .copyObject(
                    CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(targetKey)
                        .source(
                            CopySource.builder()
                                .bucket(bucket)
                                .object(sourceKey)
                                .matchETag(etag)
                                .build())
                        .build()))
        .then()
        .onErrorMap(
            MinioAttachmentStorageAdapter::isPreconditionFailed,
            error -> new AttachmentObjectChangedException())
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  @Override
  public Mono<Void> delete(final String key) {
    return call(() ->
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()))
        .then()
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  public Mono<Boolean> ping() {
    return call(() -> client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        .map(exists -> Boolean.TRUE)
        .onErrorReturn(false);
  }

  @Override
  public Optional<String> parseUploadUrl(final String url) {
    if (url == null) {
      return Optional.empty();
    }
    try {
      final URI uri = new URI(url);
      if (!sameOrigin(uri) || uri.getPath() == null) {
        return Optional.empty();
      }
      final String prefix = "/" + bucket + "/";
      final String path = uri.getPath();
      if (!path.startsWith(prefix) || path.length() == prefix.length()) {
        return Optional.empty();
      }
      return Optional.of(path.substring(prefix.length()));
    } catch (final URISyntaxException e) {
      return Optional.empty();
    }
  }

  private boolean sameOrigin(final URI uri) {
    return uri.getScheme() != null
        && uri.getScheme().toLowerCase(Locale.ROOT).equals(publicEndpoint.getScheme())
        && uri.getHost() != null
        && uri.getHost().equalsIgnoreCase(publicEndpoint.getHost())
        && effectivePort(uri) == effectivePort(publicEndpoint)
        && uri.getRawUserInfo() == null;
  }

  private static int effectivePort(final URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private Mono<Void> createBucketIfMissing() {
    return call(() -> client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        .flatMap(
            exists ->
                Boolean.TRUE.equals(exists)
                    ? Mono.<Void>empty()
                    : call(() ->
                            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build()))
                        .then()
                        .onErrorResume(
                            MinioAttachmentStorageAdapter::isAlreadyExisting,
                            error -> Mono.empty()));
  }

  private static <T> Mono<T> call(final FutureCall<T> operation) {
    return Mono.defer(
            () -> {
              try {
                return Mono.fromFuture(operation.get());
              } catch (final Exception e) {
                return Mono.error(e);
              }
            })
        .onErrorMap(
            CompletionException.class,
            error -> error.getCause() == null ? error : error.getCause());
  }

  private static byte[] readAll(final GetObjectResponse response) {
    try (InputStream stream = response) {
      return stream.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String errorCode(final Throwable error) {
    return error instanceof ErrorResponseException response && response.errorResponse() != null
        ? response.errorResponse().code()
        : null;
  }

  private static boolean isMissing(final Throwable error) {
    return MISSING.contains(errorCode(error));
  }

  private static boolean isAlreadyExisting(final Throwable error) {
    return ALREADY_EXISTS.contains(errorCode(error));
  }

  private static boolean isPreconditionFailed(final Throwable error) {
    return PRECONDITION_FAILED.equals(errorCode(error));
  }

  private static boolean isUnexpected(final Throwable error) {
    return !(error instanceof AttachmentInspectionUnavailableException)
        && !(error instanceof AttachmentObjectChangedException);
  }

  private static Throwable unavailable(final Throwable error) {
    return new AttachmentInspectionUnavailableException("the file storage is not available", error);
  }

  @FunctionalInterface
  private interface FutureCall<T> {
    CompletableFuture<T> get() throws Exception;
  }
}
