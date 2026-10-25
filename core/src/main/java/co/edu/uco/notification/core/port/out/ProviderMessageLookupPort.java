package co.edu.uco.notification.core.port.out;

import reactor.core.publisher.Mono;

public interface ProviderMessageLookupPort {

  Mono<MessageRecipientRef> findByProviderMessageId(String messageId);
}
