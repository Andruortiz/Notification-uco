package co.edu.uco.notification.core.port.out;

import reactor.core.publisher.Mono;

public interface ContentTypeDetectorPort {

  Mono<String> detect(byte[] content, String fileName);
}
