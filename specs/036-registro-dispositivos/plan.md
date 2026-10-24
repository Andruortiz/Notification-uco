# Implementation Plan: Registro de dispositivos por destinatario (HU2-120)

**Branch**: `feature/HU2-120-registro-dispositivos` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación

**Versión del plan**: 1

## Summary

Agregado nuevo `RecipientIdentity` (con sus `Device`) en `core`, tres casos de uso (registrar, listar, dar de baja) y uno interno
(eliminar dispositivo inválido) invocado por el despacho cuando el proveedor reporta token no registrado. Persistencia Mongo con
token cifrado y huella HMAC; controlador REST con rutas literales. No se tocan `Recipient`, `RecipientId` ni el envío por dirección.

## Technical Context

Java 25, Spring Boot 4, WebFlux, Mongo reactivo. Cifrado con `javax.crypto` del JDK (AES-256-GCM y HmacSHA256): sin dependencias
nuevas. Pruebas: JUnit 5 + Mockito + StepVerifier (core), Testcontainers Mongo (adaptador), `@SpringBootTest` + `WebTestClient` (E2E).

## Constitution Check (2.0.1)

- I Hexagonal: modelo y puertos en `core` sin frameworks; cifrado y Mongo en adaptadores. `HexagonalArchitectureTest` y `ModularityTests` se mantienen verdes. Cumple.
- II Contrato primero: las operaciones se piden a Contrato (ver "Cambios para Contrato") antes del controlador; la tarea del controlador depende de ese cambio. Rutas literales. Cumple.
- III Sin comentarios en código nuevo. Cumple.
- IV Tarea E2E explícita (`DeviceRegistryE2ETest`) más pruebas dirigidas por SC-001 a SC-005; `verify` completo una vez al cierre. Cumple.
- V Commits de una línea. VII Excepciones en "Excepciones registradas". VIII/IX: ver abajo.
- Consumidores RabbitMQ: no se agregan; la limpieza ocurre en el despacho existente. No aplica.

## Decisiones

1. **Identidad separada**: `RecipientIdentity(recipientId, tenantId, subject?, createdAt)`. `recipientId` es UUID generado por el
   servicio. Es un tipo nuevo (`DeviceOwnerId` o `RecipientIdentityId`, a decidir en tasks) para no chocar con `RecipientId` actual,
   que sigue siendo la dirección. Índice único `(tenantId, subject)` parcial (solo si `subject` existe).
2. **Dispositivo**: `Device(deviceId, tenantId, recipientIdentityId, platform, protectedToken, tokenFingerprint, originComponent,
   createdAt, lastSeenAt)`. `platform` enum (`ANDROID`, `IOS`, `WEB`). Índice único `(tenantId, tokenFingerprint)`.
3. **Token protegido**: puerto `TokenProtectionPort` (`protect`, `reveal`, `fingerprint`); adaptador AES-256-GCM con IV aleatorio y
   HMAC-SHA256 con clave separada; claves por variables `DEVICE_TOKEN_KEY` y `DEVICE_FINGERPRINT_KEY` (nunca en repositorio). Arranque
   falla si faltan en perfiles no `test`. `reveal` solo lo usa el mapeo para ADMINISTRADOR.
4. **Máscara**: calculada en el controlador a partir del rol de `AuthenticatedPrincipal`; el caso de uso devuelve el dominio con el token revelable solo si se pide.
5. **Registro idempotente**: inserción con índice único; ante `DuplicateKeyException` se lee el existente y se compara el
   destinatario (mismo: 200; distinto: 409).
6. **Baja**: `POST /device-removals` `{deviceId}`; borrado físico filtrado por `(tenantId, deviceId)`; 404 si no borró nada.
7. **Token inválido**: `AttemptResult` gana el valor `INVALID_RECIPIENT`, tratado como fallo permanente en `Notification`. El
   `FcmNotificationProvider` lo devuelve solo para 410 o 404 cuyo `FcmErrorResponse` tenga estado `UNREGISTERED`/`NOT_FOUND` con
   `errorCode` `UNREGISTERED`. `DispatchNotificationService` llama a `RemoveInvalidDeviceUseCase` (fire-and-continue con
   `onErrorResume`, un fallo de limpieza nunca altera el resultado del intento) con `(tenantId, address)`; el caso de uso calcula la huella y borra.
   Alternativa descartada: que el adaptador FCM borre directo; acopla proveedor y registro.
8. **`lastSeenAt`**: el despacho llama a `TouchDeviceUseCase` tras `ACCEPTED` en PUSH; mismo patrón de tolerancia a fallos.
9. **Retención**: índice TTL de Mongo sobre `lastSeenAt` con `expireAfter` desde `notification.devices.retention-days` (270). La
   creación del índice vive en el adaptador, junto a los demás índices.
10. **Extensibilidad**: `RecipientIdentity` no contiene dispositivos embebidos; F1, F6 y E10 referencian `recipientIdentityId`.
11. **Observabilidad (IX)**: logs con `tenantId`, `deviceId` y `recipientId`; la huella solo con 8 caracteres; contadores `devices.registered`,
    `devices.removed{reason=manual|invalid|retention}`.
12. **Confiabilidad (VIII)**: registro y baja son operaciones atómicas de un solo documento; la limpieza por token inválido es
    idempotente y su fallo se registra en log sin perder el estado del intento.

## Project Structure

- `core`: `domain/RecipientIdentity`, `domain/Device`, `domain/valueobject/{DevicePlatform,DeviceId,DeviceToken,RecipientIdentityId}`,
  `port/in/{RegisterDevice,ListDevices,RemoveDevice,RemoveInvalidDevice,TouchDevice}UseCase`, `port/out/{DeviceRegistryPort,TokenProtectionPort}`,
  `usecase/*Service`. Modifica `AttemptResult`, `Notification` (transición permanente) y `DispatchNotificationService`.
- `infrastructure`: `adapter/out/mongo/{DeviceDocument,RecipientIdentityDocument,DeviceMongoAdapter}`, `adapter/out/security/AesGcmTokenProtection`,
  `adapter/in/rest/{DeviceController,RegisterDeviceRequest,DeviceResponse,RemoveDeviceRequest}`, `config/DeviceProperties`. Modifica `FcmNotificationProvider`/`FcmResponseClassifier`.

## Cambios para Contrato (api-notificaciones.yaml; no se editan aquí)

| Operación | Ruta | x-status | x-minimum-role |
|---|---|---|---|
| registerDevice | POST /devices | available | CLIENTE |
| listDevices | GET /devices?recipientId= | available | CLIENTE |
| removeDevice | POST /device-removals | available | CLIENTE |

- Esquemas: `RegisterDeviceRequest {platform, token, recipientId?, subject?}`, `DeviceResponse {deviceId, recipientId, platform, token (enmascarado salvo ADMINISTRADOR), originComponent, createdAt, lastSeenAt}`, `RemoveDeviceRequest {deviceId}`.
- Respuestas: 201/200 registro, 204 baja, 400, 401, 403, 404, 409, 503 `PepUnavailable`. Cabeceras `CorrelationId` y `TraceParent` como el resto.
- Regeneración de `protected-resources.json` y aprovisionamiento del PDP (spec 028) cubrirán las rutas nuevas.

## Pendientes fuera de esta rama

- Wiring en `UseCaseConfig` y propiedades en `application.yml`: lo hace quien tenga esa restricción levantada; las tareas lo listan como paso explícito.
- Claves `DEVICE_TOKEN_KEY` y `DEVICE_FINGERPRINT_KEY` en el gestor de secretos y en `.env.example` del despliegue.

## Excepciones registradas

- Rotación de claves de cifrado no incluida: excepción con dueño Seguridad (por asignar) y fecha límite 2026-12-15. Se guarda `keyVersion` en el documento para habilitarla.
- Base legal sujeta a confirmación del responsable de datos antes del paso a producción.

## Pruebas planeadas

- Unitarias (`StepVerifier`): registro idempotente y 409, baja por tenant, eliminación por token inválido incluso con token no registrado, tolerancia a fallo de limpieza.
- Integración Mongo (Testcontainers): índice único con 20 registros concurrentes (SC-005), TTL configurado, dos tenants con el mismo token.
- Clasificación FCM: 404 `UNREGISTERED` y 410 devuelven `INVALID_RECIPIENT`; 404 de configuración y 429 no.
- Máscara y logs: ADMINISTRADOR ve el token, CLIENTE no; captura de logs sin el token (SC-004).
- **E2E** `DeviceRegistryE2ETest` (Mongo + RabbitMQ + `WebTestClient`, FCM simulado): registrar, listar, enviar PUSH al token con 410 y comprobar que el dispositivo desaparece en menos de 5 s; baja manual; dos tenants (SC-001 a SC-003).
