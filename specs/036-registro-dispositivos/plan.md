# Implementation Plan: Registro de dispositivos por destinatario (HU2-120)

**Branch**: `feature/HU2-120-registro-dispositivos` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación

**Versión del plan**: 2

## Summary

Agregado `RecipientIdentity` + `Device` en `core`, casos de uso de registrar, listar, dar de baja y borrar por titular, y un único
puerto `DeviceLifecyclePort` que el despacho usa para limpiar tokens inválidos y refrescar `lastSeenAt`. Persistencia Mongo con
token cifrado (AES-GCM con AAD) y huella HMAC. El token PUSH de `notifications.recipientAddress` también se cifra.

## Orden de integración y base

Integra al final: E3 -> G3 -> G2 -> E10 -> E5. El código parte de los tipos finales de E3 (spec 031, "Tipos compartidos"):
`ProviderResult(result, providerId, latencyMillis, messageId, cause)`; `NotificationSenderPort.send` devuelve `Mono<ProviderResult>`;
`AttemptRecord(result, origin, providerId, failedOver, latencyMillis, failureCause, correlationId, messageId)`; `Notification.mark*`
reciben un `AttemptRecord`; `Outcome(ProviderResult last, List<ProviderResult> failedOver)`. E3 ya revisa todo `switch` y mapeo sobre
`AttemptResult`. Esta historia solo añade `INVALID_RECIPIENT` al enum, el cambio del clasificador FCM y `DeviceLifecyclePort`.

## Technical Context

Java 25, Spring Boot 4, WebFlux, Mongo reactivo. `javax.crypto` del JDK (AES-256-GCM, HmacSHA256), sin dependencias nuevas.

## Constitution Check (2.0.1)

- I Hexagonal: modelo y puertos en `core`; cifrado y Mongo en adaptadores; `HexagonalArchitectureTest` y `ModularityTests` verdes.
- II Contrato primero: operaciones pedidas a Contrato (tabla abajo) antes del controlador. Rutas literales.
- III Sin comentarios. IV Tarea E2E explícita (`DeviceRegistryE2ETest`) y `verify` completo al cierre. V Commits de una línea.
- VII Excepciones en su sección. VIII/IX: Decisiones 8 y 10. Sin consumidores RabbitMQ nuevos.

## Decisiones

1. **Identidad**: `RecipientIdentity(recipientIdentityId UUID, tenantId, subject?, createdAt)`; tipo nuevo, distinto de `RecipientId`
   (la dirección). Único parcial `(tenantId, subject)`. Se crea al primer registro de la sesión; CLIENTE/OPERADOR siempre usan el
   `subject` de la sesión (si el cuerpo trae otro, 403). ADMINISTRADOR puede indicar `subject`.
2. **Dispositivo**: `Device(deviceId, tenantId, recipientIdentityId, platform {ANDROID, IOS, WEB}, protectedToken, tokenFingerprint,
   keyVersion, fingerprintKeyVersion, originComponent, createdAt, lastSeenAt)`. Único `(tenantId, tokenFingerprint)`.
3. **Registro sin sondeo**: upsert atómico por `(tenantId, tokenFingerprint)` que fija la identidad de la sesión; la respuesta es
   la misma tanto si el token era nuevo para la sesión como si era de otra identidad (200/201 según creado o no, nunca 409).
   Baja y borrado filtran por `(tenantId, deviceId, recipientIdentityId)`; 404 uniforme para ajeno, de otro tenant o inexistente.
4. **Cifrado**: `TokenProtectionPort` (`protect`, `reveal`, `fingerprint`, `mask`). AES-256-GCM con IV aleatorio y AAD
   `tenantId|deviceId` (el token de la notificación usa `tenantId|notificationId`); HMAC-SHA256 con clave aparte. Claves
   `DEVICE_TOKEN_KEY` y `DEVICE_FINGERPRINT_KEY`; el arranque falla si faltan en cualquier perfil excepto `test`, y la E2E corre
   con perfil `test` y claves fijas de prueba. Rotación: `keyVersion` y `fingerprintKeyVersion` por documento; una huella con otra
   clave no coincide con el índice único, así que la rotación de la clave HMAC exige recalcular todas las huellas (excepción).
5. **Token en `notifications.recipientAddress`**: el `NotificationDocumentMapper` cifra la dirección cuando el canal es PUSH
   (prefijo `enc:v1:`, AAD `tenantId|notificationId`) y la descifra al leer; `core` ve siempre la dirección real, así que el
   despacho y los reintentos (manual y automático) funcionan aunque el `Device` ya no exista. Hoy `recipientId` también puede ser el
   token (el contexto lo usa como dirección) y es lo único que devuelven las consultas, no `recipientAddress`: para PUSH se aplica
   el mismo cifrado a `recipientId` y se guarda `recipientIdFingerprint` (HMAC) con un índice `(tenantId, recipientIdFingerprint)`
   que sustituye al de `recipientId` para PUSH; es el consumidor del filtro `recipientId` de las consultas. `recipientAddress` no
   guarda huella (la limpieza de tokens recalcula la huella en memoria). Los documentos sin prefijo se leen en claro mientras
   existan. Las respuestas enmascaran `recipientId` PUSH salvo para ADMINISTRADOR; el evento y el log nunca llevan el token.
   Endpoints que lo heredan (verificado con Grep en `adapter/in/rest`): `GET /notifications` (`NotificationSearchResponse` ->
   `NotificationHistoryItemResponse.recipientId`) y, como filtro por `recipientId`, `GET /notifications/stream`
   (`NotificationLiveUpdatesController`; su `NotificationLiveUpdateResponse` no devuelve el destinatario). Ninguna respuesta
   devuelve `recipientAddress`. El estado y el detalle de una notificación no exponen el campo hoy; si una historia futura lo
   añade, hereda la máscara por `TokenProtectionPort.mask`.
6. **Clasificador FCM**: `FcmResponseClassifier.classifyStatus(int)` pasa a `classify(int status, FcmErrorResponse error)` (instancia o
   estático con ambos parámetros). Devuelve `INVALID_RECIPIENT` si `status == 410` o `error.errorCode() == "UNREGISTERED"`; cualquier
   otro 404 y el 400 `INVALID_ARGUMENT` siguen como `PERMANENT_FAILURE` y nunca limpian. Se ajustan los llamadores y pruebas
   existentes de spec 010. `classify` sigue devolviendo `AttemptResult`; `FcmNotificationProvider` lo envuelve con `ProviderResult.of(result)`.
7. **`INVALID_RECIPIENT` en el código**: valor nuevo de `AttemptResult`, tratado como fallo permanente donde se trata
   `PERMANENT_FAILURE` (`DispatchNotificationService`; `Notification` recibe el `AttemptRecord`, no el resultado suelto).
   `MicrometerNotificationMetrics.resultLabel` (switch exhaustivo sin default) lo mapea a `failed`, igual que `providerCalled` y
   `dispatchAttempted`. Los demás puntos (`ProviderLogs`, `applyOutcome`, `DeliveryAttemptDocument`) ya los cubre E3; al rebasar
   se confirma con una búsqueda acotada que el valor nuevo no deja ningún `switch` sin cubrir.
8. **Un solo puerto en el despacho**: `DeviceLifecyclePort` con `invalidate(tenantId, address)` y `touch(tenantId, address)`;
   `DispatchNotificationService` recibe solo ese puerto, con `onErrorResume` (un fallo de limpieza se registra y no cambia el
   resultado del intento). Los casos de uso de usuario no pasan por él. Implementación en `infrastructure` sobre el repositorio.
9. **`lastSeenAt` acotado**: `touch` actualiza con filtro `lastSeenAt < ahora - 24 h`, una escritura condicionada como máximo al día.
10. **Retención**: índice TTL sobre `lastSeenAt` con `notification.devices.retention-days` (270). Cambiar la propiedad no modifica un
    TTL existente, así que al arrancar el adaptador compara y ejecuta `collMod` si difiere. Mongo no informa de las expiraciones,
    por lo que el contador `devices.removed{reason}` solo cubre `manual`, `invalid` y `erasure`; la retención se observa por el
    conteo periódico de documentos (gauge `devices.active`). Logs con `tenantId`, `deviceId` y huella de 8 caracteres.
11. **Borrado por titular**: `POST /device-erasures {subject}`, ADMINISTRADOR; borra la identidad y sus dispositivos, 204 también
    si no existían (el administrador ya ve todo el tenant).
12. **Cifrado de histórico**: barrido en segundo plano tras el arranque, activado por propiedad; no bloquea el arranque ni la
    disponibilidad. Procesa por lotes (tamaño configurable) los documentos PUSH cuyo `recipientAddress` no empieza por `enc:v1:`,
    cada uno con actualización condicionada a ese prefijo ausente, así que es idempotente y reanudable (al reiniciar continúa
    donde quedó). Mientras corre, el servicio lee en claro los documentos sin prefijo y cifra los que guarda; sin carrera con
    escrituras de despacho gracias a la condición. Cifra también `recipientId` y calcula `recipientIdFingerprint`. Tarea de esta
    historia, sin excepción.
13. **Extensibilidad**: `Device` referencia la identidad; la identidad no embebe dispositivos y su documento tolera atributos desconocidos.

## Project Structure

- `core`: `domain/{RecipientIdentity,Device}`, `domain/valueobject/{DeviceId,DevicePlatform,RecipientIdentityId,DeviceToken}`,
  `port/in/{RegisterDevice,ListDevices,RemoveDevice,EraseRecipientDevices}UseCase`, `port/out/{DeviceRegistryPort,TokenProtectionPort,DeviceLifecyclePort}`,
  `usecase/*Service`. Modifica `AttemptResult` y `DispatchNotificationService`.
- `infrastructure`: `adapter/out/mongo/{DeviceDocument,RecipientIdentityDocument,DeviceMongoAdapter}` y cifrado en `NotificationDocumentMapper`,
  `adapter/out/security/AesGcmTokenProtection`, `adapter/in/rest/{DeviceController,...Request,DeviceResponse}`, `config/DeviceProperties`.
  Modifica `FcmNotificationProvider`/`FcmResponseClassifier`, `MicrometerNotificationMetrics`, respuestas de consulta de notificaciones.

## Cambios para Contrato (api-notificaciones.yaml; no se editan aquí)

| Operación | Ruta | x-status | x-minimum-role |
|---|---|---|---|
| registerDevice | POST /devices | available | CLIENTE |
| listDevices | GET /devices | available | CLIENTE |
| removeDevice | POST /device-removals | available | CLIENTE |
| eraseRecipientDevices | POST /device-erasures | available | ADMINISTRADOR |

- Esquemas: `RegisterDeviceRequest {platform, token, subject? (solo ADMINISTRADOR)}`, `DeviceResponse {deviceId, recipientId, platform,
  token (enmascarado salvo ADMINISTRADOR), originComponent, createdAt, lastSeenAt}`, `RemoveDeviceRequest {deviceId}`,
  `EraseDevicesRequest {subject}`. `GET /devices` acepta `subject` solo para ADMINISTRADOR.
- Respuestas: 201/200 registro, 204 baja y borrado, 400, 401, 403, 404, 503 `PepUnavailable`. Sin 409.
- Las consultas de notificaciones existentes documentan que la dirección PUSH sale enmascarada salvo ADMINISTRADOR.
- Regenerar `protected-resources.json` y aprovisionar el PDP (spec 028).

## Pendientes fuera de esta rama

- Wiring en `UseCaseConfig` y propiedades de `application.yml`: lo hace quien tenga esa restricción levantada; tarea explícita.
- Claves `DEVICE_TOKEN_KEY` y `DEVICE_FINGERPRINT_KEY` en el gestor de secretos y en `.env.example`.

## Excepciones registradas

- Rotación de claves de cifrado y de huella: dueño Seguridad, fecha 2026-12-15.
- Confirmación de base legal y retención de 270 días por el responsable de datos: dueño Equipo de backend, fecha 2026-12-15 (antes de producción).

## Pruebas planeadas

- Unitarias (`StepVerifier`): registro con tokens propio/ajeno (misma respuesta), 403 por `subject` ajeno, baja 404 uniforme, `invalidate`
  con token no registrado, tolerancia a fallo del puerto, `touch` condicionado, `AttemptResult.INVALID_RECIPIENT` en `MicrometerNotificationMetrics`.
- Clasificador FCM: 410 y 404 `UNREGISTERED` -> `INVALID_RECIPIENT`; 404 de configuración, 400 `INVALID_ARGUMENT`, 429 y 5xx no.
- Integración Mongo (Testcontainers): 20 registros concurrentes (SC-005), mismo token en dos tenants, `collMod` al cambiar la retención,
  identidad sin `subject` con atributos extra (FR-011), documento de notificación PUSH cifrado y descifrado con reintento sin `Device`.
- Seguridad: máscara por rol; captura de logs con control positivo (SC-004).
- Rendimiento: 50 iteraciones y cálculo de p95 en la prueba (SC-001).
- **E2E** `DeviceRegistryE2ETest` (perfil `test`, Mongo + RabbitMQ + `WebTestClient`, FCM simulado): registrar, listar, enviar PUSH
  al token con 410 y comprobar que el dispositivo desaparece en menos de 5 s; baja manual; borrado por titular; dos tenants y dos sujetos (SC-002, SC-003).
