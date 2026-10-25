# Implementation Plan: Directorio de destinatarios por tenant (HU2-133)

**Branch**: `feature/HU2-133-directorio-destinatarios` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación

**Versión del plan**: 1

## Summary

Colección propia `recipient_directory` (`userId`, referencia a la identidad E5, correo y teléfono cifrados), cuatro operaciones de
directorio y un endpoint de envío por usuario que resuelve la dirección y delega en `SendNotificationService` /
`SendNotificationBatchService`. Todo lo demás (dispositivos, formato, no entregable, cifrado) se reutiliza de E5 y E10.

## Orden de integración y base

Integra después de E5 (orden E3 -> G3 -> G2 -> E10 -> E5 -> F6). Parte de los tipos finales de esas specs; lo que sigue se
verifica al rebasar con una búsqueda acotada, porque aquí solo se leyeron sus spec y plan:

- E5 (036): `RecipientIdentity(recipientIdentityId, tenantId, subject?, createdAt)`, `Device`, `AesGcmTokenProtection` (en
  `infrastructure`) y `TokenMask` (en `core`), `DeviceRegistryPort`.
- E10 (032): `RecipientFormatPolicy`, `EmailAddresses` y `PhoneNumbers` en `utils`, `InvalidRecipientException`,
  `RecipientUndeliverableException`, y los items `rejected` de `SendNotificationBatchService`.
- B1 (029): `AuthenticatedPrincipal.tenantId` solo de la sesión; no hay encabezado de tenant.

## Technical Context

Java 25, Spring Boot 4, WebFlux, Mongo reactivo. Sin dependencias nuevas.

## Constitution Check (2.0.1)

- I Hexagonal: entrada, resolución y puertos en `core`; cifrado y Mongo en `infrastructure`. `HexagonalArchitectureTest` y
  `ModularityTests` verdes.
- II Contrato primero: las operaciones se piden a Contrato (tabla abajo) antes de los controllers; rutas literales sin `/api/v2`.
- III Sin comentarios. IV E2E explícito `RecipientDirectoryE2ETest`; `verify` completo al cierre. V Commits de una línea.
- VII Excepciones en su sección. VIII/IX: Decisión 9. Sin consumidores RabbitMQ nuevos: no aplica ack manual ni DLQ.

## Decisiones

1. **Colección propia, no ampliar `RecipientIdentity`**: la retención de la entrada (TTL por `lastActivityAt`) no debe borrar una
   identidad que aún tiene dispositivos activos (E5 retiene por `lastSeenAt` del dispositivo). La entrada referencia la identidad
   por `recipientIdentityId`; E5 sigue siendo dueño de identidad y dispositivos.
2. **Modelo**: `DirectoryEntry(tenantId, userId, recipientIdentityId, protectedEmail?, protectedPhone?, keyVersion, createdAt,
   updatedAt, lastActivityAt)`. Único `(tenantId, userId)`. TTL sobre `lastActivityAt` con `notification.directory.retention-days`
   (270); al arrancar el adaptador compara y ejecuta `collMod` si difiere (mismo patrón que E5, Decisión 10). Mongo no informa las
   expiraciones: `directory.removed{reason}` cubre solo `removal` y `erasure`; la retención se observa con el gauge
   `directory.entries`.
3. **Upsert atómico** por `(tenantId, userId)` (`findAndModify` con upsert sobre el índice único): 201 si creó, 200 si reemplazó;
   la identidad se obtiene con `RecipientIdentityPort.ensure(tenantId, subject?)`, que E5 expone sobre su repositorio. Con `subject`
   ya vinculado a otra entrada: 409 `NTF-3044`. Al reemplazar con otro `subject` la entrada conserva su identidad si el `subject` no
   cambia; si cambia a uno libre, se reapunta a esa identidad (la anterior permanece para E5).
4. **Protección de campos**: se reutiliza `AesGcmTokenProtection` de E5 con claves `DEVICE_TOKEN_KEY` y AAD
   `tenantId|userId|email` / `tenantId|userId|phone`. Es otra clave lógica de dominio solo por AAD; renombrar las variables a un
   nombre neutro se anota en la excepción de rotación. Sin huella HMAC: no hay búsqueda inversa. Máscaras: `EmailAddresses.mask` y
   `PhoneNumbers.mask` (E10), `TokenMask` solo para tokens. `fullData` lo calcula el controller con el rol de la sesión y lo pasa al
   mapper de respuesta; `core` no conoce roles (patrón de E10).
5. **Resolución** (`ResolveRecipientAddressesService` en `core`): `resolve(tenantId, userId, channelType)` -> `Flux<ResolvedAddress(
   recipientIdentityId, address, deviceId?)>`. Reglas por canal en un `ChannelAddressSource` de `core`: EMAIL -> correo; SMS y WHATSAPP
   -> teléfono; PUSH -> `DeviceRegistryPort.findByIdentity(tenantId, recipientIdentityId)` (token real descifrado en `infrastructure`);
   otro canal -> `UnsupportedRecipientChannelException` (400 `NTF-1003`). Vacío -> `RecipientChannelDataMissingException` (422
   `NTF-3043`); entrada ausente -> `DirectoryEntryNotFoundException` (404 `NTF-3042`). Refresca `lastActivityAt` solo si supera 24 h
   (actualización condicionada, una escritura al día como máximo) y sin que su fallo cambie el resultado del envío.
6. **Envío por usuario** (`SendToUserService`): arma uno o varios `SendNotificationCommand` y delega: uno -> `SendNotificationService`;
   varios (PUSH) -> `SendNotificationBatchService`, cuyo resultado por item ya cubre `rejected` por E10. `recipientId` del comando es
   el `recipientIdentityId` (clave estable para F1 y F5); `recipientAddress` es la dirección resuelta. La respuesta usa el esquema de
   lote existente. No se duplican validación ni persistencia: la 422 ocurre antes de delegar, así que no queda nada persistido.
7. **Validación de escritura**: `RecipientFormatPolicy` de E10 se invoca con canal EMAIL y SMS sobre los datos del upsert; el correo se
   guarda normalizado (recorte y minúsculas). Longitudes: `userId` <= 128, correo <= 254, `subject` <= el máximo de E5.
8. **Borrado**: `removal` borra solo la entrada. `erasure` (ADMINISTRADOR) borra la entrada y llama `EraseRecipientDevicesUseCase` de E5
   con el `subject` de la identidad (identidad sin `subject`: `RecipientIdentityPort.delete(tenantId, recipientIdentityId)` y sus
   dispositivos). Orden: primero la entrada, después E5; si E5 falla se responde 5xx y repetir la solicitud completa el borrado
   (idempotente: 204 también si la entrada ya no existe, pero solo para ADMINISTRADOR, que ya ve todo el tenant). No se toca la
   marca de no entregable de E10: es un dato de la dirección, no del titular, y su borrado por titular queda como pregunta abierta.
9. **Observabilidad**: logs con `tenantId`, huella de 8 caracteres del `userId` (HMAC de E5) y `notificationId` cuando existe;
   ningún correo, teléfono ni token. Contadores de FR-012. `userId` se acepta tal cual; no se enmascara en respuestas porque el
   llamador acaba de enviarlo.
10. **Idempotencia del envío**: `externalId` derivado `<externalId>:<deviceId>` para PUSH; el índice único `(tenantId, externalId)`
    existente da la idempotencia. Un reintento tras añadir un dispositivo crea solo la notificación nueva.

## Project Structure

- `core`: `domain/DirectoryEntry`, `domain/valueobject/UserId`, `port/in/{UpsertDirectoryEntry,LookupDirectoryEntry,RemoveDirectoryEntry,EraseDirectoryEntry,SendToUser}UseCase`,
  `port/out/{RecipientDirectoryPort,RecipientIdentityPort}`, `usecase/*Service`, `domain/policy/ChannelAddressSource`, excepciones
  `DirectoryEntryNotFoundException`, `RecipientChannelDataMissingException`, `DirectorySubjectConflictException`.
- `infrastructure`: `adapter/out/mongo/{DirectoryEntryDocument,RecipientDirectoryMongoAdapter}`, `adapter/in/rest/{RecipientDirectoryController,
  UserNotificationController,...Request,...Response}`, `config/DirectoryProperties`, mapeo de errores en `NotificationExceptionHandler`.
  Wiring en `UseCaseConfig` y propiedades en `application.yml`: tarea explícita.

## Cambios para Contrato (api-notificaciones.yaml; no se editan aquí)

| Operación | Ruta | x-status | x-minimum-role |
|---|---|---|---|
| upsertDirectoryEntry | POST /recipient-directory | available | CLIENTE |
| lookupDirectoryEntry | POST /recipient-directory/lookups | available | CLIENTE |
| removeDirectoryEntry | POST /recipient-directory-removals | available | CLIENTE |
| eraseDirectoryEntry | POST /recipient-directory-erasures | available | ADMINISTRADOR |
| sendToUser | POST /user-notifications | available | CLIENTE |

- Esquemas: `UpsertDirectoryEntryRequest {userId, email?, phone?, subject?}`; `DirectoryEntryResponse {userId, recipientId, email,
  phone (enmascarados salvo ADMINISTRADOR), createdAt, updatedAt}`; `DirectoryUserRequest {userId}` para consulta, baja y borrado;
  `SendToUserRequest {externalId (<=200), userId, channelType, subject?, body, priority, attachments?}`, mismo molde que
  `SendNotificationRequest` sin `recipientId` ni `recipientAddress`; respuesta: el esquema de lote.
- Códigos nuevos en `ErrorCode`: `NTF-3042 DIRECTORY_ENTRY_NOT_FOUND` (404), `NTF-3043 RECIPIENT_CHANNEL_DATA_MISSING` (422),
  `NTF-3044 DIRECTORY_SUBJECT_CONFLICT` (409). Se reutiliza `NTF-1006` y `NTF-1003`. Verificar que F1 y D no usen esos números.
- Respuestas: 201/200 upsert, 200 consulta, 202 envío, 204 baja y borrado, 400, 401, 403, 404, 409, 422, 503 `PepUnavailable`.
- `SendNotificationRequest.recipientAddress` no cambia: el envío por usuario es una operación aparte.
- Regenerar `protected-resources.json` y aprovisionar el PDP (spec 028).

## Tipos y puntos compartidos

- Con F1 (preferencias, spec 048): la clave de preferencias es `recipientIdentityId`, que el envío por usuario pasa como `recipientId`.
  F6 no consulta preferencias; el envío delegado las aplica cuando F1 esté integrada.
- Con F2 y F5 (nivel 3): ambos parten de la sesión o del token de baja y llegan a la misma identidad; el vínculo sesión -> entrada es
  `subject`. Necesitan `RecipientDirectoryPort.findByIdentity(tenantId, recipientIdentityId)` (solo lectura); se define aquí.
- Con D3 (plantillas): el cuerpo de `SendToUserRequest` admitirá `templateId` y variables cuando D3 integre, por el mismo
  `SendNotificationCommand`.
- Con E5: `RecipientIdentityPort` (`ensure`, `delete`) y `DeviceRegistryPort.findByIdentity` deben existir; si E5 no los expone, se
  añaden en la rama de F6 y se avisa. Con E10: reutilización, sin cambios.

## Excepciones registradas (Principio VII)

- Rotación de claves de cifrado y renombrado neutro de `DEVICE_TOKEN_KEY`: dueño Seguridad, fecha 2026-12-15.
- Base legal, retención de 270 días y papel de encargado frente a la aplicación responsable: dueño Equipo de backend, fecha
  2026-12-15 (antes de producción).
- Borrado por titular de la marca de no entregable (E10): dueño Equipo de backend, fecha 2026-12-15.

## Pruebas planeadas

- Unitarias (`StepVerifier`): resolución por canal (correo, teléfono, dispositivos), 404 uniforme, 422 sin persistir, conflicto de
  `subject`, refresco condicionado de `lastActivityAt`, tolerancia a fallo del refresco, orden y orquestación de `erasure` con fallo de E5.
- Integración Mongo (Testcontainers): 20 upserts concurrentes (SC-004), mismo `userId` en dos tenants, `collMod` al cambiar la
  retención, documento sin correo ni teléfono en claro (SC-003).
- Seguridad: máscara por rol con control positivo; captura de logs (SC-003); `userId` ajeno y de otro tenant con la misma 404 (SC-002).
- Rendimiento: 50 iteraciones y p95 en la prueba (SC-001); `Duration` de 1 s para la 422 (SC-005).
- **E2E** `RecipientDirectoryE2ETest` (perfil `test`, Mongo + RabbitMQ + `WebTestClient`): upsert, consulta, envío EMAIL, envío PUSH
  con 3 dispositivos registrados por E5 (3 notificaciones), 422 por dato ausente, no entregable E10 como item `rejected`, baja,
  borrado por titular que arrastra identidad y dispositivos (SC-006), dos tenants.
- Producir los mensajes con el productor real y los dispositivos con `POST /devices` real, no con documentos armados a mano.

## Pendientes fuera de esta rama

- Wiring en `UseCaseConfig` y `application.yml` si quien implemente tiene esa restricción; claves en `.env.example`.
- Pantalla de administración del directorio en Front-Notification-mfe (otra historia).
