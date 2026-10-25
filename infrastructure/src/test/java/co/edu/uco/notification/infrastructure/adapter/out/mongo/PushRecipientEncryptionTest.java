package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class PushRecipientEncryptionTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final TenantId OTHER = TenantId.of("tenant-2");
  private static final String TOKEN = "fcm-device-token-ZZZZ-9999";
  private static final String RECIPIENT = "recipient-secret-0001";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private AesGcmTokenProtection protection;
  private NotificationMongoAdapter protectedAdapter;
  private NotificationMongoAdapter legacyAdapter;

  private static byte[] key(final int fill) {
    final byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  @BeforeEach
  void setUp() {
    protection = new AesGcmTokenProtection(key(1), 1, key(2), 1);
    protectedAdapter = new NotificationMongoAdapter(mongoTemplate, protection);
    legacyAdapter = new NotificationMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(NotificationDocument.class).block();
  }

  private static Notification notification(
      final TenantId tenant, final String channel, final String externalId) {
    return Notification.accept(
        new NotificationRouting(
            tenant,
            ExternalId.of(externalId),
            ChannelType.of(channel),
            RecipientId.of(RECIPIENT),
            Recipient.of(TOKEN)),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  private NotificationDocument raw(final Notification notification) {
    return mongoTemplate
        .findById(notification.notificationId().value(), NotificationDocument.class)
        .block();
  }

  @Test
  void aPushNotificationIsStoredEncryptedAndReadBackInTheClear() {
    final Notification saved =
        protectedAdapter.save(notification(TENANT, "PUSH", "push-1")).block();

    final NotificationDocument stored = raw(saved);
    assertTrue(stored.recipientAddress().startsWith("enc:v1:"));
    assertTrue(stored.recipientId().startsWith("enc:v1:"));
    assertFalse(stored.recipientAddress().contains(TOKEN));
    assertFalse(stored.recipientId().contains(RECIPIENT));
    assertEquals(protection.fingerprint(RECIPIENT), stored.recipientIdFingerprint());
    final Notification read = protectedAdapter.findById(saved.notificationId()).block();
    assertEquals(TOKEN, read.recipient().address());
    assertEquals(RECIPIENT, read.recipientId().value());
  }

  @Test
  void otherChannelsAreStoredAsBefore() {
    final Notification saved = protectedAdapter.save(notification(TENANT, "SMS", "sms-1")).block();

    final NotificationDocument stored = raw(saved);
    assertEquals(TOKEN, stored.recipientAddress());
    assertEquals(RECIPIENT, stored.recipientId());
    assertNull(stored.recipientIdFingerprint());
  }

  @Test
  void theRecipientFilterFindsEncryptedAndLegacyDocumentsWithinTheTenantOnly() {
    protectedAdapter.save(notification(TENANT, "PUSH", "push-1")).block();
    legacyAdapter.save(notification(TENANT, "PUSH", "push-legacy")).block();
    protectedAdapter.save(notification(OTHER, "PUSH", "push-other")).block();

    final long found =
        protectedAdapter
            .search(
                new NotificationSearchCriteria(
                    TENANT, RecipientId.of(RECIPIENT), null, null, null, null, 50, 0))
            .count()
            .block();

    assertEquals(2, found);
  }

  @Test
  void theProviderMessageLookupRevealsTheProtectedAddress() {
    final Notification saved = notification(TENANT, "PUSH", "push-msg");
    saved.markQueued();
    protectedAdapter.save(saved).block();
    mongoTemplate
        .updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id")
                    .is(saved.notificationId().value())),
            new org.springframework.data.mongodb.core.query.Update()
                .set(
                    "deliveryAttempts",
                    java.util.List.of(new org.bson.Document("messageId", "m-1"))),
            NotificationDocument.class)
        .block();

    final var ref = protectedAdapter.findByProviderMessageId("m-1").block();

    assertEquals(TOKEN, ref.address());
  }

  @Test
  void theSweepEncryptsLegacyPushDocumentsInBatchesAndIsIdempotent() {
    final Notification legacy1 =
        legacyAdapter.save(notification(TENANT, "PUSH", "legacy-1")).block();
    final Notification legacy2 =
        legacyAdapter.save(notification(TENANT, "PUSH", "legacy-2")).block();
    final Notification legacy3 =
        legacyAdapter.save(notification(TENANT, "PUSH", "legacy-3")).block();
    final Notification sms = legacyAdapter.save(notification(TENANT, "SMS", "legacy-sms")).block();
    final PushRecipientEncryptionSweep sweep =
        new PushRecipientEncryptionSweep(mongoTemplate, protection);

    assertEquals(3L, sweep.run(2).block());
    assertEquals(0L, sweep.run(2).block());

    for (final Notification legacy : java.util.List.of(legacy1, legacy2, legacy3)) {
      final NotificationDocument stored = raw(legacy);
      assertTrue(stored.recipientAddress().startsWith("enc:v1:"));
      assertTrue(stored.recipientId().startsWith("enc:v1:"));
      assertEquals(protection.fingerprint(RECIPIENT), stored.recipientIdFingerprint());
      assertEquals(
          TOKEN, protectedAdapter.findById(legacy.notificationId()).block().recipient().address());
    }
    assertEquals(TOKEN, raw(sms).recipientAddress());
  }
}
