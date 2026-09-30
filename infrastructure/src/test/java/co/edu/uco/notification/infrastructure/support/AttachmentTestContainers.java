package co.edu.uco.notification.infrastructure.support;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class AttachmentTestContainers {

  public static final String MINIO_USER = "attachments-test";
  public static final String MINIO_PASSWORD = "attachments-test-secret";
  public static final String BUCKET = "notification-attachments-test";

  private static final int CLAMAV_PORT = 3310;

  private AttachmentTestContainers() {}

  public static GenericContainer<?> clamAv() {
    return ClamAvHolder.CONTAINER;
  }

  public static MinIOContainer minio() {
    return MinioHolder.CONTAINER;
  }

  public static String minioEndpoint() {
    return "http://" + minio().getHost() + ":" + minio().getMappedPort(9000);
  }

  public static void registerClamAv(final DynamicPropertyRegistry registry) {
    registry.add("notification.attachments.clamav.host", () -> clamAv().getHost());
    registry.add("notification.attachments.clamav.port", () -> clamAv().getMappedPort(CLAMAV_PORT));
  }

  public static void registerMinio(final DynamicPropertyRegistry registry) {
    registry.add(
        "notification.attachments.storage.endpoint", AttachmentTestContainers::minioEndpoint);
    registry.add(
        "notification.attachments.storage.public-endpoint",
        AttachmentTestContainers::minioEndpoint);
    registry.add("notification.attachments.storage.bucket", () -> BUCKET);
    registry.add("MINIO_ACCESS_KEY", () -> MINIO_USER);
    registry.add("MINIO_SECRET_KEY", () -> MINIO_PASSWORD);
  }

  private static final class ClamAvHolder {
    private static final GenericContainer<?> CONTAINER = startClamAv();

    private static GenericContainer<?> startClamAv() {
      final GenericContainer<?> container =
          new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.4"))
              .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
              .withExposedPorts(CLAMAV_PORT)
              .waitingFor(
                  Wait.forLogMessage(".*socket found, clamd started.*", 1)
                      .withStartupTimeout(Duration.ofMinutes(4)));
      container.start();
      return container;
    }
  }

  private static final class MinioHolder {
    private static final MinIOContainer CONTAINER = startMinio();

    private static MinIOContainer startMinio() {
      final MinIOContainer container =
          new MinIOContainer(
                  DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                      .asCompatibleSubstituteFor("minio/minio"))
              .withUserName(MINIO_USER)
              .withPassword(MINIO_PASSWORD);
      container.start();
      return container;
    }
  }
}
