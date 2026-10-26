# Implementation Plan: Directorio de destinatarios por tenant (HU2-133)

**Branch**: `feature/HU2-133-directorio-destinatarios` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Pendiente de aprobación. **Versión del plan**: 2 (correcciones de la revisión cruzada del 2026-10-25).

## Summary

Colección propia `recipient_directory` (`userId`, referencia a la identidad E5, correo y teléfono cifrados), cuatro operaciones de
directorio, un envío por usuario que resuelve la dirección y delega en `SendNotificationService` / `SendNotificationBatchService`,
y un borrado por titular común (`EraseRecipientDataUseCase`) al que delegan E5 y F6. Dispositivos, formato, no entregable y cifrado
se reutilizan de E5 y E10.

## Base (verificar al rebasar)

Orden: E3 -> G3 -> G2 -> E10 -> E5 -> D3 -> F1 -> F6. Solo se leyeron las specs de E5 y E10 en forma resumida; con el código
integrado se verifica:

- E5: `RecipientIdentity(recipientIdentityId, tenantId, subject?, createdAt)`, `RecipientIdentityPort` (cuatro métodos, ver FR-004),
  `DeviceRegistryPort.findByIdentity`, `AesGcmTokenProtection`, `TokenMask`. Si E5 no expone algún método, se reporta en E5; F6 no
  define otro puerto.
- E10: `RecipientFormatPolicy`, `EmailAddresses`, `PhoneNumbers`, `RecipientUndeliverableException`, items `rejected` del lote.
- F1: `recipientIdentityId` en `SendNotificationCommand`. B1: `AuthenticatedPrincipal.tenantId` solo de la sesión.

## Technical Context

Java 25, Spring Boot 4, WebFlux, Mongo reactivo. Sin dependencias nuevas.

## Constitution Check (2.0.1)

- I Hexagonal: resolución, `EraseRecipientDataUseCase` y puertos en `core`; cifrado y Mongo en `infrastructure`.
- II Contrato primero: operaciones en Contrato (tabla abajo) antes de los controllers. Roles con nombres de B2; la enmienda de la
  constitución a 2.1.0 es prerrequisito de Contrato.
- III Sin comentarios. IV `RecipientDirectoryE2ETest` y `verify` completo al cierre. V Commits de una línea.
- VII Excepciones en su sección. Sin consumidores RabbitMQ nuevos: no aplica ack manual ni DLQ.

## Decisiones

1. **Colección propia, no ampliar `RecipientIdentity`**: el TTL de la entrada (`lastActivityAt`) no debe borrar una identidad con
   dispositivos activos (E5 retiene por `lastSeenAt` del dispositivo).
2. **Modelo**: `DirectoryEntry(tenantId, userId, recipientIdentityId, protectedEmail?, protectedPhone?, keyVersion, createdAt,
   updatedAt, lastActivityAt)`. Índices únicos `(tenantId, userId)` y `(tenantId, recipientIdentityId)`. TTL con
   `notification.directory.retention-days` (270) y `collMod` al arrancar si difiere (patrón de E5). Mongo no informa expiraciones:
   se observan con el gauge `directory.entries`.
3. **Upsert atómico** por `(tenantId, userId)` (`findAndModify` con upsert): 201 si creó, 200 si reemplazó. La identidad sale de
   `RecipientIdentityPort.findOrCreateBySubject(tenantId, subject?)`; si ya apunta a otra entrada: 409 `NTF-3044`. Al cambiar de
   `subject`, la entrada se reapunta; la identidad anterior queda para E5.
4. **Protección de campos**: `AesGcmTokenProtection` de E5 con la clave `DEVICE_TOKEN_KEY` y AAD `tenantId|userId|email` /
   `tenantId|userId|phone` (excepción de renombrado). Sin huella HMAC. Máscaras de E10. `fullData` lo calcula el controller con el
   rol; `core` no conoce roles.
5. **Resolución** (`ResolveRecipientAddressesService`): `resolve(tenantId, userId, channelType)` -> `Flux<ResolvedAddress(
   recipientIdentityId, address?, deviceId?)>`. `ChannelAddressSource`: EMAIL -> correo; SMS y WHATSAPP -> teléfono; PUSH ->
   `DeviceRegistryPort.findByIdentity`; IN_APP -> identidad, sin `address`. Otro canal: 400 `NTF-1003`. Dato vacío: 422 `NTF-3043`.
   Entrada ausente: 404 `NTF-3042`. Refresca `lastActivityAt` solo si supera 24 h y su fallo no cambia el resultado.
6. **Envío por usuario** (`SendToUserService`): cada `SendNotificationCommand` recibe `recipientId` con la dirección resuelta
   (semántica existente, sin cambio) y `recipientIdentityId` con la identidad de la entrada. No se iguala uno al otro. En IN_APP no
   hay dirección: verificar al rebasar que el comando admite `recipientId` vacío.
   Uno -> `SendNotificationService`; varios (PUSH) -> `SendNotificationBatchService`. Preferencias de F1 y compuerta de D5 se
   aplican al delegar. 404 y 422 ocurren antes de delegar: nada persiste.
7. **Validación de escritura**: `RecipientFormatPolicy` con canal EMAIL y SMS; correo normalizado (recorte y minúsculas). Longitudes:
   `userId` <= 128, correo <= 254, `subject` <= el máximo de E5.
8. **Borrado por titular** (FR-014): `EraseRecipientDataUseCase.erase(tenantId, recipientIdentityId)` en `core`, con
   `RecipientDataEraser.erase(tenantId, recipientIdentityId): Mono<Void>`. Orden fijado en `UseCaseConfig`: E5 (dispositivos),
   E6, F1, F6 (entrada, localizada con `RecipientDirectoryPort.findByIdentity`) y, la última, E5 (identidad con
   `RecipientIdentityPort.delete`). Fallo de un contribuyente: 5xx; el reintento repite todo.
   - `/recipient-directory-erasures` es la única vía de borrado completo: busca la entrada por `userId` (404 si no existe), toma
     su identidad y delega. `/device-erasures` de E5 no cambia: ni contrato, ni pruebas, ni código.
   - Hueco residual: si falla el último paso, la entrada ya está borrada y el reintento responde 404; la identidad residual queda.
     Declarado en Excepciones.
   - La marca de no entregable de E10 no se borra.
9. **Auditoría**: cada escritura, baja y borrado registra en log estructurado `tenantId`, actor (identificador opaco del principal),
   operación, resultado y huella de 8 caracteres del `userId` (HMAC de E5). Ningún correo, teléfono ni token.
10. **Idempotencia del envío**: `externalId` derivado `<externalId>:<deviceId>` para PUSH; el índice único `(tenantId, externalId)`
    da la idempotencia.
11. **Roles de la sesión**: el principal debe traer sus roles, o consultarse `/api/v1/authorize` por operación. Sin esto, escritura
    y borrado no son alcanzables con sesión PDP (ver Excepciones).

## Project Structure

- `core`: `domain/DirectoryEntry`, `domain/valueobject/UserId`, `port/in/{UpsertDirectoryEntry,LookupDirectoryEntry,
  RemoveDirectoryEntry,EraseDirectoryEntry,SendToUser,EraseRecipientData}UseCase`, `port/out/{RecipientDirectoryPort,
  RecipientDataEraser}`, `usecase/*Service`, `domain/policy/ChannelAddressSource`, excepciones de la spec.
- `infrastructure`: `adapter/out/mongo/{DirectoryEntryDocument,RecipientDirectoryMongoAdapter}`, `adapter/in/rest/
  {RecipientDirectoryController,UserNotificationController,...}`, `config/DirectoryProperties`, mapeo de errores en
  `NotificationExceptionHandler`, wiring en `UseCaseConfig` (orden de contribuyentes) y `application.yml`.

## Cambios para Contrato (api-notificaciones.yaml; no se editan aquí)

| Operación | Ruta | x-status | x-allowed-roles |
|---|---|---|---|
| upsertDirectoryEntry | POST /recipient-directory | planned | ADMINISTRADOR_APLICACION |
| lookupDirectoryEntry | POST /recipient-directory/lookups | planned | CLIENTE, ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA |
| removeDirectoryEntry | POST /recipient-directory-removals | planned | ADMINISTRADOR_APLICACION |
| eraseDirectoryEntry | POST /recipient-directory-erasures | planned | ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA |
| sendToUser | POST /user-notifications | planned | CLIENTE, ADMINISTRADOR_APLICACION |

- Esquemas: `UpsertDirectoryEntryRequest {userId, email?, phone?, subject?}`; `DirectoryEntryResponse {userId, recipientId, email,
  phone, createdAt, updatedAt}` (enmascarados salvo administradores); `DirectoryUserRequest {userId}`;
  `SendToUserRequest {externalId (<=200), userId, channelType, subject?, body, priority, attachments?}` más los campos de plantilla
  de D3, sin `recipientId` ni `recipientAddress`; respuesta: esquema de lote.
- `ErrorCode` nuevos: `NTF-3042 DIRECTORY_ENTRY_NOT_FOUND` (404), `NTF-3043 RECIPIENT_CHANNEL_DATA_MISSING` (422),
  `NTF-3044 DIRECTORY_SUBJECT_CONFLICT` (409). Se reutilizan `NTF-1006` y `NTF-1003`.
- Respuestas: 201/200 upsert, 200 consulta, 202 envío, 204 baja y borrado, 400, 401, 403, 404, 409, 422, 503 `PepUnavailable`.
- Regenerar `protected-resources.json` y aprovisionar el PDP (spec 028).

## Puntos compartidos

- F1: la clave de preferencias es `recipientIdentityId`. D3: `SendToUserRequest` reutiliza los campos de plantilla sin redefinirlos.
- F2 y F5: necesitan `RecipientDirectoryPort.findByIdentity(tenantId, recipientIdentityId)` (solo lectura); se define aquí.
- E6 y F1 registran su `RecipientDataEraser` en sus historias.

## Excepciones registradas (Principio VII)

- Precondición de roles por sesión (B2): dueño Seguridad, fecha 2026-12-15, bloqueante antes de producción.
- Rotación de claves y renombrado de `DEVICE_TOKEN_KEY`: dueño Seguridad, fecha 2026-12-15.
- Base legal, retención de 270 días (tabla de retención aprobada, Nivel 2, sección 6 del lote) y papel de encargado: dueño
  Responsable de datos, fecha 2026-12-15.
- Hueco de reintento del borrado por titular (identidad residual si falla el último paso): dueño Equipo de backend, fecha
  2026-12-15.
- Borrado por titular de la marca de no entregable (E10): dueño Equipo de backend, fecha 2026-12-15.
- Límite de SC-003: correo y teléfono en claro en documentos de notificación EMAIL y SMS (E5 solo cifra PUSH). Dueño Equipo de
  backend, fecha 2026-12-15. Opción futura: cifrar o enmascarar en el documento de notificación (cambio en E5 y E10).

Pregunta menor abierta (no es excepción): si `CLIENTE` es productor legítimo de la escritura del directorio.

## Pruebas planeadas

- Unitarias (`StepVerifier`): resolución por canal (correo, teléfono, dispositivos, IN_APP), 404 uniforme, 422 sin persistir, 409
  de identidad, refresco condicionado de `lastActivityAt`, `EraseRecipientDataUseCase` con fallo de un contribuyente y la entrada
  borrada la última.
- Integración Mongo (Testcontainers): 20 upserts concurrentes (SC-004), mismo `userId` en dos tenants, `collMod` al cambiar la
  retención, documento sin correo ni teléfono en claro (SC-003).
- Seguridad: máscara por rol con control positivo; captura de logs (SC-003); `userId` ajeno con la misma 404 (SC-002); `CLIENTE`
  recibe 403 en el borrado por titular.
- Observabilidad (FR-012): captura de logs con `tenantId`, huella de 8 caracteres y sin correo ni teléfono; registro de actor,
  operación y resultado en cada escritura, baja y borrado; contadores `directory.upserts`, `directory.resolutions{channel,outcome}`,
  `directory.removed{reason}` y gauge `directory.entries` verificados con el registro de métricas.
- Rendimiento: p95 de 50 iteraciones (SC-001); `Duration` de 1 s para la 422 (SC-005).
- **E2E** `RecipientDirectoryE2ETest` (perfil `test`, Mongo + RabbitMQ + `WebTestClient`): upsert, consulta, EMAIL, PUSH con 3
  dispositivos (3 notificaciones), 422 por dato ausente, no entregable E10 como `rejected`, baja, borrado por titular (SC-006), dos
  tenants. Mensajes con el productor real y dispositivos con `POST /devices` real.

## Pendientes fuera de esta rama

- Wiring en `UseCaseConfig` y `application.yml`; claves en `.env.example`.
- Pantalla de administración del directorio en Front-Notification-mfe (otra historia).
