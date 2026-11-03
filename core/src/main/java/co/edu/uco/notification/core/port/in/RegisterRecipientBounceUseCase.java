package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface RegisterRecipientBounceUseCase {

  Mono<Boolean> registerHardBounce(String providerMessageId, String reason);
}
