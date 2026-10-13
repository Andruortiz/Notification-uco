package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import co.edu.uco.notification.core.port.in.SearchNotificationsQuery;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.repository.NotificationSearchCriteria;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SearchNotificationsServiceTest {

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");

  private final NotificationRepository notificationRepository = mock(NotificationRepository.class);

  private SearchNotificationsService service;

  @BeforeEach
  void setUp() {
    service = new SearchNotificationsService(notificationRepository);
  }

  private static Notification acceptedNotification() {
    return Notification.accept(
        new NotificationRouting(
            TENANT_ID,
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  private static SearchNotificationsQuery aQuery(final int limit, final int offset) {
    return new SearchNotificationsQuery(TENANT_ID, null, null, null, null, null, limit, offset);
  }

  @Test
  void searchMapsRepositoryResultsToThePage() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.search(any())).thenReturn(Flux.just(notification));

    final NotificationSearchPage page = service.search(aQuery(50, 0)).block();

    assertNotNull(page);
    assertEquals(1, page.items().size());
    assertEquals(notification.notificationId(), page.items().getFirst().notificationId());
    assertFalse(page.hasNext());
    assertEquals(50, page.limit());
    assertEquals(0, page.offset());
  }

  @Test
  void searchSetsHasNextWhenRepositoryReturnsOneMoreThanTheLimit() {
    when(notificationRepository.search(any()))
        .thenReturn(Flux.just(acceptedNotification(), acceptedNotification()));

    final NotificationSearchPage page = service.search(aQuery(1, 0)).block();

    assertNotNull(page);
    assertEquals(1, page.items().size());
    assertTrue(page.hasNext());
  }

  @Test
  void searchReturnsAnEmptyPageWhenNothingMatches() {
    when(notificationRepository.search(any())).thenReturn(Flux.empty());

    final NotificationSearchPage page = service.search(aQuery(50, 0)).block();

    assertNotNull(page);
    assertTrue(page.items().isEmpty());
    assertFalse(page.hasNext());
  }

  @Test
  void searchBuildsTheCriteriaFromTheQuery() {
    when(notificationRepository.search(any())).thenReturn(Flux.empty());
    final SearchNotificationsQuery query =
        new SearchNotificationsQuery(
            TENANT_ID,
            RecipientId.of("recipient-1"),
            ChannelType.of("EMAIL"),
            NotificationStatus.FAILED,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-12-31T00:00:00Z"),
            25,
            10);

    service.search(query).block();

    final NotificationSearchCriteria expected =
        new NotificationSearchCriteria(
            TENANT_ID,
            RecipientId.of("recipient-1"),
            ChannelType.of("EMAIL"),
            NotificationStatus.FAILED,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-12-31T00:00:00Z"),
            25,
            10);
    verify(notificationRepository).search(expected);
  }

  @Test
  void searchRejectsFromAfterTo() {
    final SearchNotificationsQuery query =
        new SearchNotificationsQuery(
            TENANT_ID,
            null,
            null,
            null,
            Instant.parse("2026-09-20T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:00Z"),
            50,
            0);

    assertThrows(IllegalArgumentException.class, () -> service.search(query));
  }

  @Test
  void searchRejectsLimitOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> service.search(aQuery(0, 0)));
    assertThrows(IllegalArgumentException.class, () -> service.search(aQuery(201, 0)));
  }

  @Test
  void searchRejectsNegativeOffset() {
    assertThrows(IllegalArgumentException.class, () -> service.search(aQuery(50, -1)));
  }

  @Test
  void searchRejectsNullQuery() {
    assertThrows(NullPointerException.class, () -> service.search(null));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(NullPointerException.class, () -> new SearchNotificationsService(null));
  }
}
