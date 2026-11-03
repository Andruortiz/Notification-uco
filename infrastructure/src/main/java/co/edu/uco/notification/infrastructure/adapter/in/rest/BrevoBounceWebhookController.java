package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.RegisterRecipientBounceUseCase;
import co.edu.uco.notification.infrastructure.adapter.out.provider.SimulatedNotificationProvider;
import co.edu.uco.notification.infrastructure.config.SimulatedProviderProperties;
import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/providers/brevo/bounce-events")
public class BrevoBounceWebhookController {

  public static final String SECRET_HEADER = "X-Brevo-Webhook-Secret";

  private static final Set<String> HARD_BOUNCES = Set.of("hard_bounce", "invalid_email", "blocked");

  private final RegisterRecipientBounceUseCase registerBounce;
  private final byte[] secret;
  private final boolean simulatedEnabled;

  public BrevoBounceWebhookController(
      final RegisterRecipientBounceUseCase registerBounce,
      final SimulatedProviderProperties simulatedProperties,
      @Value("${notification.brevo.webhook-secret:}") final String secret) {
    this.registerBounce =
        Preconditions.requireNonNull(registerBounce, "registerBounce must not be null");
    this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    this.simulatedEnabled = simulatedProperties.enabled();
  }

  @PostMapping
  public Mono<ResponseEntity<Void>> receive(
      @RequestHeader(value = SECRET_HEADER, required = false) final String provided,
      @RequestBody final Mono<BrevoBounceEvent> body) {
    if (!secretMatches(provided)) {
      return Mono.error(new WebhookSecretInvalidException());
    }
    return body.flatMap(this::register).thenReturn(ResponseEntity.ok().build());
  }

  private Mono<Boolean> register(final BrevoBounceEvent event) {
    if (event.event() == null || !HARD_BOUNCES.contains(event.event())) {
      return Mono.empty();
    }
    return registerBounce.registerHardBounce(resolvableMessageId(event.messageId()), event.event());
  }

  private String resolvableMessageId(final String messageId) {
    if (!simulatedEnabled
        && messageId != null
        && messageId.startsWith(SimulatedNotificationProvider.MESSAGE_ID_PREFIX)) {
      return null;
    }
    return messageId;
  }

  private boolean secretMatches(final String provided) {
    if (secret.length == 0 || provided == null) {
      return false;
    }
    return MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8));
  }
}
