package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import reactor.core.publisher.Mono;

public interface TestProviderConnectionUseCase {

  Mono<ProviderProbeResult> test(ChannelType channel, ProviderId providerId);
}
