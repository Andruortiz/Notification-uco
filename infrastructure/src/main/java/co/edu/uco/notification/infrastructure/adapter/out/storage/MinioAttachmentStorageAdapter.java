package co.edu.uco.notification.infrastructure.adapter.out.storage;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.PresignedUpload;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.Preconditions;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.PostPolicy;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private static final String LIFECYCLE_RULE_ID = "expire-orphaned-uploads";
  private static final String NO_LIFECYCLE_CONFIGURATION = "NoSuchLifecycleConfiguration";
  private static final Logger LOGGER = LoggerFactory.getLogger(MinioAttachmentStorageAdapter.class);

  private final MinioAsyncClient client;
  private final MinioAsyncClient signer;
  private final String bucket;
  private final URI publicEndpoint;
  private final Mono<Void> bucketReady;
  private final Clock clock;

  public MinioAttachmentStorageAdapter(final AttachmentProperties properties, final Clock clock) {
    Preconditions.requireNonNull(properties, "properties must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
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
  public Mono<PresignedUpload> presignUpload(
      final String key, final String contentType, final long sizeBytes, final Duration expiresIn) {
    Preconditions.requireNonBlank(key, "key must not be blank");
    Preconditions.requireNonBlank(contentType, "contentType must not be blank");
    Preconditions.requireNonNull(expiresIn, "expiresIn must not be null");
    return bucketReady
        .then(Mono.fromCallable(() -> signedForm(key, contentType, sizeBytes, expiresIn)))
        .onErrorMap(
            MinioAttachmentStorageAdapter::isUnexpected,
            MinioAttachmentStorageAdapter::unavailable);
  }

  private PresignedUpload signedForm(
      final String key, final String contentType, final long sizeBytes, final Duration expiresIn)
      throws Exception {
    final Instant expiresAt = clock.instant().plus(expiresIn);
    final PostPolicy policy =
        new PostPolicy(bucket, ZonedDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    policy.addEqualsCondition("key", key);
    policy.addEqualsCondition("Content-Type", contentType);
    policy.addContentLengthRangeCondition(sizeBytes, sizeBytes);
    final Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", key);
    fields.put("Content-Type", contentType);
    fields.putAll(signer().getPresignedPostFormData(policy));
    final String base = trimTrailingSlash(publicEndpoint.toString());
    return new PresignedUpload(
        base + "/" + bucket,
        fields,
        base + "/" + bucket + "/" + key.replace("%", "%25"),
        expiresAt);
  }

  private static String trimTrailingSlash(final String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
            MinioAttachmentStorageAdapter::isUnexpected, MinioAttachmentStorageAdapter::unavailable)
        .doOnError(
            error ->
                LOGGER.warn(
                    LogFields.failure(ErrorCode.ATTACHMENT_OBJECT_DELETE_FAILED, "objectKey", key),
                    "Attachment object could not be deleted",
                    error));
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
                            error -> Mono.empty()))
        .then(Mono.defer(this::applyUploadsLifecycle));
  }

  private Mono<Void> applyUploadsLifecycle() {
    final LifecycleRule rule =
        new LifecycleRule(
            Status.ENABLED,
            null,
            new Expiration((ZonedDateTime) null, 1, null),
            new RuleFilter(AttachmentUpload.UPLOADS_PREFIX),
            LIFECYCLE_RULE_ID,
            null,
            null,
            null);
    return existingLifecycleRules()
        .flatMap(
            existing -> {
              final List<LifecycleRule> rules = new ArrayList<>();
              existing.stream()
                  .filter(current -> !LIFECYCLE_RULE_ID.equals(current.id()))
                  .forEach(rules::add);
              rules.add(rule);
              return call(
                  () ->
                      client()
                          .setBucketLifecycle(
                              SetBucketLifecycleArgs.builder()
                                  .bucket(bucket)
                                  .config(new LifecycleConfiguration(rules))
                                  .build()));
            })
        .then();
  }

  private Mono<List<LifecycleRule>> existingLifecycleRules() {
    return call(() ->
            client().getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build()))
        .map(
            configuration ->
                configuration.rules() == null
                    ? List.<LifecycleRule>of()
                    : List.copyOf(configuration.rules()))
        .onErrorResume(
            error -> NO_LIFECYCLE_CONFIGURATION.equals(errorCode(error)),
            error -> Mono.just(List.<LifecycleRule>of()))
        .defaultIfEmpty(List.<LifecycleRule>of());
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
