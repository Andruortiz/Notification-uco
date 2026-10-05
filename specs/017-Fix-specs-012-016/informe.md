# Informe de revisión de las specs 011 a 016

**Fecha**: 2026-10-02
**Base**: `develop` en `ea79f56`. La 015 se leyó desde `feature/HU2-096-autenticacion-interina`, sin checkout.
**Método**: revisión manual de solo lectura por agentes independientes (familia Sonnet), más una verificación propia de los hallazgos marcados con ✔. No se ejecutó Maven, Docker, Spotless ni cobertura. No se corrió `/speckit-analyze`.

Leyenda de verificación: ✔ confirmado por la sesión principal leyendo el código; ◐ leído por el revisor, no re-leído; ✘ no verificado.

## 1. Estado de las historias

| Spec | Historia | Estado en git |
|------|----------|---------------|
| 011-consultar-catalogo | HU2-085 | Mergeada (#38) |
| 012-adjuntar-archivo-notificacion | HU2-092 | Mergeada (#43 y siguientes) |
| 012-reintentar-envio-manual | HU2-027 | Solo specs (#40). Sin código |
| 013-preferencias-destinatario | HU2-029 | Solo specs (#41). Sin código |
| 014-exponer-envio-lote | HU2-021 | Mergeada (#42) |
| 015-autenticacion-interina | HU2-096 | **No mergeada** (9 commits por delante; parte ya en develop vía `2c7ba7c`) |
| 016-logs-correlation-id | HU2-056 | Mergeada (#49) |

## 2. Problemas críticos y altos

| ID | Spec | Ubicación | Problema | Verif. |
|----|------|-----------|----------|--------|
| A-01 | 015 | `application.yml:38`, `.env.example`, `token.mjs` | El secreto HS256 tiene un valor por defecto público en el repo. Si falta `AUTH_JWT_HS256_SECRET`, el servicio arranca y cualquiera forja tokens de cualquier tenant y rol. No hay validación de arranque ni `@Validated`. Contradice FR-013 | ✔ |
| A-02 | 015 | `AuthenticationWebFilter.filter` (líneas 55-67) | `onErrorResume` va después del `flatMap` que continúa la cadena. Cualquier error de un controller o caso de uso se registra como rechazo de autenticación y se intenta responder 401, incluso con la respuesta ya iniciada (SSE). Enmascara errores 5xx | ✔ |
| A-03 | despacho | `RabbitRetryConfig`, `NotificationDispatchListener` | La constitución exige ack manual tras persistir; el listener de despacho queda en `AUTO`. Solo el listener de adjuntos usa `MANUAL`. La brecha ya está reconocida como preexistente en los planes | ✔ (solo `AttachmentScanRabbitConfig` fija `MANUAL`) |
| A-04 | despacho | `DispatchNotificationService.sendThrough` / `saveAndPublish` | El envío al proveedor ocurre antes de persistir. Si `save` o `publish` fallan tras un envío aceptado, el mensaje se reentrega y se reenvía (duplicado). No hay reserva atómica del estado previa al envío | ✔ |
| A-05 | despacho | `DispatchNotificationService:76`, `StatusTransitionPolicy` | `markQueued` lanza `InvalidStatusTransitionException` si la notificación ya está `IN_PROCESS`, `DELIVERED` o `FAILED`. Una reentrega inocua se reintenta 3 veces y acaba en la DLQ | ◐ |
| A-06 | despacho | `RequeuePendingNotificationsService.recoverOrphanedPending` | Un `PENDING` reencolado conserva sus intentos; si `enqueueForDispatch` falla, ya no cumple el filtro `deliveryAttempts().isEmpty()` y queda huérfano para siempre. El mismo método traga el error con `onErrorResume(error -> Mono.empty())` | ✔ |
| A-07 | 014 | `SendNotificationBatchService`, `NotificationBatchController`, OpenAPI | No hay tope de ítems por lote (solo `minItems: 1`, sin `maxItems`; solo el límite de 8 MB del cuerpo). Un tenant puede disparar miles de envíos por petición | ✔ (sin `maxItems` en el contrato) |
| A-08 | 014 | `SendNotificationBatchService.persistBatchRecord` | `onErrorResume(ex -> Mono.empty())` sin log: si falla el guardado del registro del lote, el cliente recibe 202 y no queda traza | ✔ |
| A-09 | 014 | `NotificationBatchDocumentMapper`, `NotificationBatchDocument` | Idempotencia de `batchId` incompleta: un reenvío con el mismo `batchId` reprocesa los ítems y el guardado falla por clave duplicada, que A-08 traga. No hay prueba del reenvío | ◐ |
| A-10 | 012-adjuntos | `ScanAttachmentUploadService` (~461-477, 511-516) | Un upload puede quedar en `PENDING_SCAN` para siempre: tres caminos terminan en `Mono.empty()` sin cambiar estado ni loguear (objeto ausente, tamaño distinto, fallo tras borrar el objeto). Falta un estado terminal de fallo | ◐ |
| A-11 | 012-adjuntos | `ScanAttachmentUploadService`, `MinioAttachmentStorageAdapter.read` | `readAllBytes()` sin tope. El escaneo no compara el tamaño real con el declarado ni con el máximo antes de leer. La URL presignada PUT no limita tamaño, así que un objeto enorme puede causar OOM | ✔ (`readAllBytes` sin tope; el resto ◐) |
| A-12 | 012-reintentar | spec, `tasks.md`, `quickstart.md` | La spec dice "no hay rol de operador" y usa `X-Tenant-Id`, pero `RouteAuthorizationPolicy` exige `OPERADOR` para `:retry`. Ninguna tarea prueba 403 (CLIENTE) ni 202 (OPERADOR) | ✔ |
| A-13 | 012-reintentar | `StatusTransitionPolicy`, `DispatchNotificationService` | `FAILED → PENDING` conserva los intentos acumulados. En un reintento manual, el primer fallo recuperable suma el intento 6 y vuelve a `FAILED` sin backoff. Falta definir si el reintento manual reinicia el contador | ◐ |

## 3. Severidad media

| ID | Spec | Ubicación | Problema | Verif. |
|----|------|-----------|----------|--------|
| M-01 | 015 | `LocalJwtTokenValidationAdapter` | El parser no exige `exp`, `iss` ni `aud`; un token firmado sin `exp` no caduca. Sin pruebas de `exp` ausente, `alg=none` ni `nbf` futuro | ◐ |
| M-02 | 015 | `AuthenticationWebFilter` (exenciones) | `startsWith("/actuator")`, `"/v3/api-docs"`, `"/swagger-ui"`, `"/openapi"` cubren también rutas como `/actuatorX` | ✔ |
| M-03 | 015 | `AuthenticationWebFilter` (SSE) | El JWT viaja en `?access_token=` para `/notifications:subscribe`; puede quedar en logs de proxy y de acceso. El token dura 12 h | ◐ |
| M-04 | 015 | `AuthenticationWebFilter.classify` | Clasifica por tipo de causa: un tenant inválido cae en `UNKNOWN_ROLE`; el `tenantId` del log solo se extrae de tokens expirados | ◐ |
| M-05 | 015 | `AuthJwtProperties` | Sin `@NotBlank`; con secreto vacío falla al arrancar con un mensaje poco claro. `ttlMinutes` no se valida | ◐ |
| M-06 | 015 | SC-004 | Sin prueba automatizada del p95 de 200 ms | ✔ |
| M-07 | 015 | `tasks.md` | T034, T041–T045 sin marcar: la rama no demuestra haber pasado la puerta final | ✔ |
| M-08 | 011 | `QueryChannelCatalogService`, `RouteAuthorizationPolicy` | Cualquier `CLIENTE` ve el estado interno de los proveedores y los motivos de configuración (nombres de variables de entorno, detalle de parseo de FCM). No se filtran valores de credenciales | ◐ |
| M-09 | 011 | `ChannelCatalogCache`, `MongoChannelCatalogAdapter` | Antes de la primera carga el snapshot es `Map.of()` y `GET /channels` responde `200` con lista vacía; no distingue "vacío" de "sin cargar" | ◐ |
| M-10 | 011 | `ChannelCatalogController` | Recibe `AuthenticatedPrincipal` sin usarlo | ✔ |
| M-11 | 014 | `SendNotificationBatchService` (líneas 80-85) | `BatchItemResult.failed/rejected(..., ex.getMessage())` devuelve y persiste mensajes de excepción arbitrarios (hosts, colecciones, datos del destinatario). El segundo `onErrorResume` captura `Throwable` | ✔ (el código; el riesgo de fuga ◐) |
| M-12 | 014 | `NotificationBatchController.toItem` | `item.attachments()` se recorre sin comprobar null: posible NPE → 500. Depende de si el record normaliza null | ◐ |
| M-13 | 014 | `NotificationBatchController` | El cuerpo no pasa por bean validation; todo depende de excepciones manuales mapeadas a 400 | ◐ |
| M-14 | 014 | `plan.md`, `tasks.md` T007–T009 | `Estado: Pendiente` y tareas de cierre sin marcar, con la historia ya mergeada | ✔ |
| M-15 | 016 | `LogContext` (MDC) en `AuthenticationWebFilter`, `NotificationController`, `AttachmentUploadController` | El MDC es por hilo; en operadores reactivos posteriores `tenantId` y `notificationId` pueden no viajar. Falta una prueba de `tenantId` dentro de un `flatMap` | ◐ |
| M-16 | 016 | `CorrelationContextConfig` | `Hooks.enableAutomaticContextPropagation()` es global de la JVM y se registra en `@PostConstruct`; el accessor llama `MDC.remove` al restaurar | ◐ |
| M-17 | 016 | `NotificationDispatchListener:51-54` | `.block()` con MDC fijado por `LogContext` y por el Context de Reactor; los valores pueden diferir al cambiar de hilo | ◐ |
| M-18 | 016 | `tasks.md` T036, T033 | `verify` completo con Docker sin marcar. T033 está marcada pero `RabbitRetryConfigCustomAttemptsTest` no menciona la correlación | ✔ (T036); ◐ (T033) |
| M-19 | 012-adjuntos | `CompleteAttachmentUploadService` | `expiresAt` nunca se compara en los servicios (solo se asigna en el dominio) y no hay limpieza de uploads abandonados. El `Boolean` de `transition` se ignora | ✔ (sin comparación); ◐ (resto) |
| M-20 | 012-adjuntos | `MinioAttachmentStorageAdapter` | La URL presignada PUT sigue vigente tras `complete` y no ata tipo ni tamaño; un re-PUT deja un objeto huérfano sin escanear. Usa `Instant.now()` en lugar del `Clock` inyectado | ◐ |
| M-21 | 012-adjuntos | `AttachmentScanListener` | `send`/`recover` y `basicAck` no son atómicos (duplicado si falla el ack). Un JSON ilegible o `AttachmentObjectChangedException` se reintenta hasta `maxAttempts`. Sin backoff ni confirmación de publicación visible | ✔ (orden y reintento de `IOException`) |
| M-22 | 012-adjuntos | `AttachmentScanListener.logVerdict` | `LogContext.open(null, tenantId, null)` anidado puede pisar el contexto de correlación; no se verificó que `close()` lo restaure | ✘ |
| M-23 | 012-reintentar | `plan.md`, contrato | Excepción del ack `AUTO` sin dueño ni fecha (Principio VII). La nueva `description` del contrato quitaría el texto de rol y de 401/403. Si falla el encolado tras persistir `PENDING`, el operador queda bloqueado y ninguna tarea lo cubre | ◐ |
| M-24 | despacho | `RequeuePendingNotificationsService` | Solo se ignora `NotificationVersionConflictException`; otro fallo corta el `Flux.merge` de toda la pasada. Un `PENDING` sin intentos se reencola cada 30 s tras el umbral (posibles duplicados), sin lock entre instancias | ◐ |
| M-25 | despacho | `DispatchNotificationService:68-70` | Sin failover de proveedor. Una excepción del sender deja la notificación `PENDING` sin intento ni evento, indistinguible de `RECOVERABLE_FAILURE` | ◐ |
| M-26 | despacho | `Notification` | `version` es `final` y solo la devuelve `save`: dos `save` sobre la misma instancia dan un conflicto falso. Los casos de uso nuevos (reintento, preferencias) deben usar la instancia devuelta | ◐ |
| M-27 | global | `NotificationExceptionHandler:81-86` | `IllegalArgumentException` genérico → 400 con `e.getMessage()`; no hay handler genérico `Exception` ni para conflicto de versión, clave duplicada, `ProviderNotAvailableException` o `InvalidTokenException` | ◐ |
| M-28 | global | 54 líneas de comentario en 8 archivos de `main` (`KeyVaultSecretsLoader`, `KeyVaultEnvironmentPostProcessor`, `BrevoNotificationProvider`, `BrevoEmailRequest`, `AttachmentContentLoader`, `ChannelCatalogSeeder`, `RequestEnums`, `ContentSchemaValidator`) | Incumplen la regla de cero comentarios (Principio III). `token.mjs` también tiene comentarios `//` | ✔ (conteo y archivos) |
| M-29 | global | `token.mjs` (en el índice git con estado `AM`) | Script que genera JWT de administrador de 12 h con el secreto por defecto; no debería llegar a `master` | ✔ |
| M-30 | 012–016 | Artefactos de 011, 012-reintentar, 013, 014, 016 | Siguen describiendo `X-Tenant-Id` y `400` si falta; el código vigente usa JWT interino y responde `401`. Los `curl` de los quickstart ya no funcionan | ✔ (011, 014); ◐ (resto) |
| M-31 | 013 | `tasks.md` | Las 19 tareas sin marcar; spec "Draft", plan "Pendiente", clarificaciones sin confirmar. FR-012 (lote) y FR-015 sin prueba planificada | ✔ (sin marcar) |

## 4. Severidad baja y malas prácticas

| ID | Spec | Ubicación | Observación | Verif. |
|----|------|-----------|-------------|--------|
| B-01 | 011 | `MongoChannelCatalogAdapter:33-35` | `ChannelType.of` dentro del `map`: una clave inválida rompe toda la consulta con 500 | ◐ |
| B-02 | 011 | `ChannelCatalogQueryE2ETest` | Bucles de espera sin pausa (spin) de hasta 25 s; la prueba de SC-005 usa refresco de 1 s y no comprueba que el valor viejo siga visible antes del refresco | ◐ |
| B-03 | 011 | `ChannelCatalogControllerTest` | Sin caso de error 500 ni de rol insuficiente | ◐ |
| B-04 | 015 | `RouteAuthorizationPolicy:45-51` | Ruta desconocida exige `ADMINISTRADOR`; sin pruebas de barra final ni rutas codificadas | ◐ |
| B-05 | 015, 016 | `AuthenticationWebFilter` (cuerpo JSON) | JSON de error armado por concatenación de strings; seguro hoy, frágil | ◐ |
| B-06 | 015 | `LocalJwtTokenValidationAdapter` | Dos constructores públicos y `@Component` sobre clase `final` con `@Autowired` solo en uno | ◐ |
| B-07 | 016 | `LogSanitizer` | Solo cubre patrones conocidos; un token en una URL sin palabra clave pasa sin enmascarar | ◐ |
| B-08 | 016 | `logback-spring.xml` | `AsyncAppender` con `neverBlock=true` y `discardingThreshold=0`: bajo carga se descartan logs sin métrica | ◐ |
| B-09 | 016 | `LogCorrelationE2ETest` | Arma su propio encoder y no ejercita `logback-spring.xml`; los centinelas no cubren credencial ni token | ◐ |
| B-10 | 016 | `spec.md` | FR-016, FR-017, FR-015 desordenados; política WARN para fallos recuperables solo en Assumptions. La excepción del Principio VII vence el 2026-10-15 | ✔ (fecha) |
| B-11 | 012-adjuntos | `AttachmentUploadController` | `UploadId.of` fuera de `Mono.defer`: lanza en el hilo reactivo | ◐ |
| B-12 | 012-adjuntos | `AttachmentPolicy` | La lista negra omite `.docm`, `.xlsm`, `.html`, `.svg`, `.iso`; `uploadIdFromKey` acepta `.` y `..` | ◐ |
| B-13 | 012-adjuntos | pruebas | `atLeast(2)` en lugar de intentos exactos; sin mensaje venenoso ni fallo del `send` de reintento | ◐ |
| B-14 | 012-adjuntos | `tasks.md` | Rutas `infra-main/` e `infra-test/` obsoletas (hoy `core`, `infrastructure`, `utils`) | ◐ |
| B-15 | despacho | `NotificationMongoAdapter.findByStatus` | `find` sin límite ni proyección, sin índice verificado sobre `status` | ◐ |
| B-16 | despacho | `NotificationRabbitPublisher` | Eventos en `forEach` sin confirmación ni outbox: Mongo y eventos pueden divergir | ◐ |
| B-17 | despacho | `DispatchNotificationService`, `RequeuePendingNotificationsService` | `.toList().size()` en vez de `count()` y lógica de conteo duplicada | ◐ |
| B-18 | global | `KeyVaultSecretsLoader` | Con prefijo vacío (por defecto) lee todos los secretos del vault | ◐ |
| B-19 | global | `ChannelCatalogSeeder` | `onErrorResume` traga el error: si el sembrado falla, el servicio arranca con catálogo incompleto | ◐ |
| B-20 | global | `core/pom.xml` | `core` importa Jackson y json-schema en el dominio; confirmar si la constitución lo permite. Sin límite de adjuntos visible en Twilio ni FCM | ◐ |
| B-21 | global | `CorsConfig` | `allowedHeaders("*")` con origen configurable; verificar `NOTIFICATION_CORS_ALLOWED_ORIGINS` en producción | ◐ |

## 5. Hallazgos descartados o rebajados

- Ruta desconocida exige `ADMINISTRADOR` (B-04): es fail-closed; se rebaja a observación de pruebas.
- Excepción del Principio VII de la 016 "vencida": la fecha es 2026-10-15, aún no vence.
- Comentario HTML en `plan.md` de la 015: es plantilla de spec-kit, no código.
- Desviaciones de proceso de la 011 y 014 (tareas fusionadas, E2E tras la revisión): están documentadas en `tasks.md`.
- `HashMap` que colapsa ids duplicados en la 011: no es defecto.
- Bloqueos en hilos reactivos: los `.block()` están en el arranque o en hilos de `@RabbitListener`; no hay `.block()` ni `Thread.sleep` en la ruta HTTP.
- CI, Dockerfile y Twilio/FCM/Brevo no tienen hallazgos: acciones fijadas por SHA, imagen multietapa con usuario no root y secretos fuera del Dockerfile.

## 6. Lo que no se verificó

- Ejecución de pruebas, E2E con Testcontainers, cobertura 80/70, Spotless, SpotBugs, `HexagonalArchitectureTest` y `ModularityTests`.
- Las cifras de cierre de `tasks.md` (por ejemplo 348 pruebas y 98,1 % / 86,3 % de la 011) son del autor.
- Contrato OpenAPI línea a línea contra los DTOs y controllers.
- `exposure.include` de actuator, logs de acceso de Netty o proxy, y vulnerabilidades de dependencias (Trivy).
- Aserciones de la mayoría de las pruebas; solo se comprobó su existencia y nombres.

## 7. Prioridad sugerida

1. A-01 y M-29: quitar el secreto por defecto, hacer que el arranque falle sin secreto y no versionar `token.mjs`.
2. A-02: limitar `onErrorResume` del filtro de autenticación a la validación del token.
3. A-07 y A-11: tope de ítems por lote (`maxItems`) y tope de lectura en el escaneo.
4. A-08 y A-06: registrar los errores tragados y revisar la recuperación de huérfanos.
5. A-03 a A-05 y A-13: ack manual y envío idempotente en el despacho, antes de implementar el reintento manual.
6. M-30: reconciliar los artefactos de las specs con el JWT interino.
7. M-28: eliminar los comentarios y ejecutar `spotless:apply`.
