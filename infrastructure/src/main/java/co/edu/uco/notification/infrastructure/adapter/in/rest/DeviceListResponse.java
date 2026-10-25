package co.edu.uco.notification.infrastructure.adapter.in.rest;

import java.util.List;

public record DeviceListResponse(List<DeviceResponse> items) {

  public DeviceListResponse {
    items = List.copyOf(items);
  }
}
