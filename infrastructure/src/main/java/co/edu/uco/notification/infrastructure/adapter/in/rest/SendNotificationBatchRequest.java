package co.edu.uco.notification.infrastructure.adapter.in.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SendNotificationBatchRequest(String batchId, List<SendNotificationRequest> items) {

  public SendNotificationBatchRequest {
    items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
  }
}
