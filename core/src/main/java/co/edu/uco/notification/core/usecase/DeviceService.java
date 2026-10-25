package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.exception.DeviceNotFoundException;
import co.edu.uco.notification.core.exception.DeviceSubjectForbiddenException;
import co.edu.uco.notification.core.port.in.EraseRecipientDevicesUseCase;
import co.edu.uco.notification.core.port.in.ListDevicesUseCase;
import co.edu.uco.notification.core.port.in.RegisterDeviceUseCase;
import co.edu.uco.notification.core.port.in.RemoveDeviceUseCase;
import co.edu.uco.notification.core.port.out.DeviceRegistryPort;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class DeviceService
    implements RegisterDeviceUseCase,
        ListDevicesUseCase,
        RemoveDeviceUseCase,
        EraseRecipientDevicesUseCase {

  private final DeviceRegistryPort registry;

  public DeviceService(final DeviceRegistryPort registry) {
    this.registry = Preconditions.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public Mono<Registration> register(
      final AuthenticatedPrincipal principal,
      final DevicePlatform platform,
      final String token,
      final String subject) {
    return Mono.defer(
        () -> {
          Preconditions.requireNonNull(platform, "platform must not be null");
          Preconditions.requireNonBlank(token, "token must not be blank");
          Preconditions.requireTrue(token.length() <= Device.MAX_TOKEN_LENGTH, "token is too long");
          final String target = targetSubject(principal, subject);
          return registry
              .findOrCreateIdentity(principal.tenantId(), target)
              .flatMap(
                  identity ->
                      registry.save(
                          principal.tenantId(), identity, platform, token, principal.subject()))
              .map(saved -> new Registration(saved.device(), saved.created()));
        });
  }

  @Override
  public Flux<Device> list(final AuthenticatedPrincipal principal, final String subject) {
    return Flux.defer(
        () -> registry.findBySubject(principal.tenantId(), targetSubject(principal, subject)));
  }

  @Override
  public Mono<Void> remove(final AuthenticatedPrincipal principal, final String deviceId) {
    return Mono.defer(
        () -> {
          Preconditions.requireNonBlank(deviceId, "deviceId must not be blank");
          final String scope = isAdmin(principal) ? null : principal.subject();
          return registry
              .remove(principal.tenantId(), deviceId, scope)
              .flatMap(
                  removed ->
                      removed ? Mono.<Void>empty() : Mono.error(new DeviceNotFoundException()));
        });
  }

  @Override
  public Mono<Void> erase(final AuthenticatedPrincipal principal, final String subject) {
    return Mono.defer(
        () -> {
          Preconditions.requireNonBlank(subject, "subject must not be blank");
          if (!isAdmin(principal)) {
            return Mono.error(new DeviceSubjectForbiddenException());
          }
          return registry.eraseBySubject(principal.tenantId(), subject);
        });
  }

  private static String targetSubject(
      final AuthenticatedPrincipal principal, final String subject) {
    if (subject == null || subject.isBlank() || subject.equals(principal.subject())) {
      return principal.subject();
    }
    if (!isAdmin(principal)) {
      throw new DeviceSubjectForbiddenException();
    }
    return subject;
  }

  private static boolean isAdmin(final AuthenticatedPrincipal principal) {
    return principal.role() == Role.ADMINISTRADOR;
  }
}
