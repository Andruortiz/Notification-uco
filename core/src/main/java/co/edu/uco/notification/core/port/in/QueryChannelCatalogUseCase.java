package co.edu.uco.notification.core.port.in;

import java.util.List;
import reactor.core.publisher.Mono;

public interface QueryChannelCatalogUseCase {

  Mono<List<ChannelView>> listChannels();

  Mono<List<ProviderView>> listProviders();
}
