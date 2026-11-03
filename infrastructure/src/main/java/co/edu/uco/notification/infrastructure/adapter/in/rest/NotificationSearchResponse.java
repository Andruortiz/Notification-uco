package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import java.util.List;

public record NotificationSearchResponse(
    List<NotificationHistoryItemResponse> items, int limit, int offset, boolean hasNext) {

  public NotificationSearchResponse {
    items = List.copyOf(items);
  }

  static NotificationSearchResponse from(
      final NotificationSearchPage page, final boolean unmasked) {
    return new NotificationSearchResponse(
        page.items().stream()
            .map(item -> NotificationHistoryItemResponse.from(item, unmasked))
            .toList(),
        page.limit(),
        page.offset(),
        page.hasNext());
  }
}
