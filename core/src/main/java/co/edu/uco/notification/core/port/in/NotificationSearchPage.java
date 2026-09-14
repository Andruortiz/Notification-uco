package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.utils.Preconditions;
import java.util.List;

public record NotificationSearchPage(
    List<NotificationSearchResult> items, int limit, int offset, boolean hasNext) {

  public NotificationSearchPage {
    Preconditions.requireNonNull(items, "items must not be null");
    items = List.copyOf(items);
  }
}
