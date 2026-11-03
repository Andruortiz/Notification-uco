package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.port.in.EraseRecipientDevicesUseCase;
import co.edu.uco.notification.core.port.in.ListDevicesUseCase;
import co.edu.uco.notification.core.port.in.RegisterDeviceUseCase;
import co.edu.uco.notification.core.port.in.RemoveDeviceUseCase;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class DeviceController {

  private final RegisterDeviceUseCase registerUseCase;
  private final ListDevicesUseCase listUseCase;
  private final RemoveDeviceUseCase removeUseCase;
  private final EraseRecipientDevicesUseCase eraseUseCase;

  public DeviceController(
      final RegisterDeviceUseCase registerUseCase,
      final ListDevicesUseCase listUseCase,
      final RemoveDeviceUseCase removeUseCase,
      final EraseRecipientDevicesUseCase eraseUseCase) {
    this.registerUseCase =
        Preconditions.requireNonNull(registerUseCase, "registerUseCase must not be null");
    this.listUseCase = Preconditions.requireNonNull(listUseCase, "listUseCase must not be null");
    this.removeUseCase =
        Preconditions.requireNonNull(removeUseCase, "removeUseCase must not be null");
    this.eraseUseCase = Preconditions.requireNonNull(eraseUseCase, "eraseUseCase must not be null");
  }

  @PostMapping("/devices")
  public Mono<ResponseEntity<DeviceResponse>> register(
      final AuthenticatedPrincipal principal, @RequestBody final RegisterDeviceRequest request) {
    return registerUseCase
        .register(
            principal, DevicePlatform.of(request.platform()), request.token(), request.subject())
        .map(
            registration ->
                ResponseEntity.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(
                        DeviceResponse.from(registration.device(), principal.seesUnmaskedData())));
  }

  @GetMapping("/devices")
  public Mono<DeviceListResponse> list(
      final AuthenticatedPrincipal principal,
      @RequestParam(name = "subject", required = false) final String subject) {
    return listUseCase
        .list(principal, subject)
        .map(device -> DeviceResponse.from(device, principal.seesUnmaskedData()))
        .collectList()
        .map(DeviceListResponse::new);
  }

  @PostMapping("/device-removals")
  public Mono<ResponseEntity<Void>> remove(
      final AuthenticatedPrincipal principal, @RequestBody final RemoveDeviceRequest request) {
    return removeUseCase
        .remove(principal, request.deviceId())
        .thenReturn(ResponseEntity.noContent().build());
  }

  @PostMapping("/device-erasures")
  public Mono<ResponseEntity<Void>> erase(
      final AuthenticatedPrincipal principal, @RequestBody final EraseDevicesRequest request) {
    return eraseUseCase
        .erase(principal, request.subject())
        .thenReturn(ResponseEntity.noContent().build());
  }
}
