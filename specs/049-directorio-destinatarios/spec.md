# Feature Specification: Directorio de destinatarios por tenant (HU2-133)

**Feature Branch**: `feature/HU2-133-directorio-destinatarios`

**Created**: 2026-10-25

**Status**: Draft (pendiente de aprobación de spec y plan)

## Contexto

Hoy quien envía debe conocer la dirección concreta (correo, teléfono o token FCM). Esta historia añade un directorio por
tenant que cada aplicación llena por API, y un envío por identificador de usuario: la plataforma resuelve la dirección según el
canal pedido. Depende de B1 (tenant solo desde la sesión, spec 029), E5 (identidad de destinatario y dispositivos, spec 036) y
E10 (formato y no entregable, spec 032). Este documento asume las tres integradas (orden E3 -> G3 -> G2 -> E10 -> E5 -> F6).

Principio rector: **no duplicar**. Los dispositivos y la identidad ya los guarda E5; la marca de no entregable ya la guarda E10.
F6 solo guarda lo que ninguna de las dos tiene: el identificador de usuario de la aplicación, el correo y el teléfono.

## Clarifications

### Session 2026-10-25 (propuestas del autor, a aprobar con la spec)

- **Q1 - Clave**: `userId` es el identificador que la aplicación usa para su usuario; único por `(tenantId, userId)`. El tenant
  sale solo de la sesión (B1); ningún cuerpo ni parámetro lo fija. Afecta FR-001, FR-009.
- **Q2 - Qué se guarda de más**: solo `userId`, correo y teléfono, más la referencia a la identidad de E5. No se guarda nombre,
  documento ni fecha de nacimiento (minimización). Los dispositivos viven solo en E5. Afecta FR-001, FR-003.
- **Q3 - Vínculo con E5**: cada entrada apunta a una `RecipientIdentity` (E5). Si el cuerpo trae `subject`, se reutiliza o crea la
  identidad de ese `subject`, de modo que los dispositivos que el usuario registró desde su sesión quedan alcanzables; sin
  `subject` se crea una identidad sin `subject`. Si el `subject` ya pertenece a otra entrada: 409. Afecta FR-004.
- **Q4 - Semántica de escritura**: `POST /recipient-directory` es upsert por reemplazo del par correo/teléfono; un campo ausente
  o nulo borra ese dato. Sin importación masiva en esta historia. Afecta FR-002.
- **Q5 - Fan-out**: el envío por usuario es un endpoint nuevo `POST /user-notifications`; PUSH genera una notificación por
  dispositivo registrado (E5, Q5), correo y SMS una sola. El envío por dirección (`POST /notifications`) no cambia. Afecta FR-007.
- **Q6 - Errores explícitos**: usuario ausente del tenant o de otro tenant: 404 `NTF-3042` uniforme; usuario existente sin dato
  para el canal (sin correo, sin teléfono, sin dispositivos): 422 `NTF-3043` con el canal; nada se persiste ni se encola. Afecta FR-006.
- **Q7 - Roles** (los tres del código hasta B2): CLIENTE escribe, consulta, borra la entrada y envía; ADMINISTRADOR ve correo y
  teléfono completos y ejecuta el borrado por titular. Respuestas enmascaradas para el resto. Afecta FR-005, FR-010.
- **Q8 - Retención**: 270 días sin actividad (mismo plazo que E5); la actividad es un upsert o una resolución. Afecta FR-011.

## User Scenarios & Testing

### User Story 1 - Llenar el directorio (Priority: P1)

**Independent Test**: `POST /recipient-directory` con dos tenants y el mismo `userId`.

1. **Given** una sesión de aplicación, **When** envía `userId`, correo y teléfono válidos, **Then** 201 con la entrada (correo y
   teléfono enmascarados) y `recipientId` de la identidad E5.
2. **Given** una entrada existente, **When** repite el `userId` con otros datos, **Then** 200 y los datos quedan reemplazados.
3. **Given** un correo mal formado o un teléfono que no es E.164, **When** se escribe, **Then** 400 `NTF-1006` y no se guarda nada.
4. **Given** el mismo `userId` en otro tenant, **When** se escribe, **Then** son entradas independientes.
5. **Given** un `subject` ya vinculado a otra entrada del tenant, **When** se escribe, **Then** 409.

### User Story 2 - Enviar a un identificador de usuario (Priority: P1)

**Independent Test**: `POST /user-notifications` por EMAIL, SMS y PUSH con entradas completas e incompletas.

1. **Given** un usuario con correo, **When** se envía por EMAIL, **Then** se acepta una notificación a ese correo con el flujo y
   las guardas habituales (formato y no entregable de E10).
2. **Given** un usuario con tres dispositivos, **When** se envía por PUSH, **Then** 202 con tres items, uno por dispositivo, con
   `externalId` derivado y estable (idempotente ante reintento).
3. **Given** un usuario sin teléfono, **When** se pide SMS, **Then** 422 `NTF-3043` y no existe notificación ni mensaje en cola.
4. **Given** un `userId` inexistente o de otro tenant, **When** se envía, **Then** la misma 404 en ambos casos.
5. **Given** una dirección resuelta marcada no entregable por E10, **When** se envía, **Then** el item queda `rejected` con
   `RECIPIENT_UNDELIVERABLE`; los demás items se aceptan.

### User Story 3 - Consultar y borrar (Priority: P1)

1. **Given** una entrada, **When** `POST /recipient-directory/lookups` con `userId`, **Then** correo y teléfono enmascarados
   (completos para ADMINISTRADOR).
2. **Given** una entrada propia del tenant, **When** `POST /recipient-directory-removals`, **Then** 204: se elimina la entrada
   con su correo y teléfono; la identidad y los dispositivos de E5 permanecen.
3. **Given** una solicitud de borrado del titular, **When** un ADMINISTRADOR invoca `POST /recipient-directory-erasures`,
   **Then** 204: se elimina la entrada, la identidad y todos sus dispositivos; un CLIENTE recibe 403.
4. **Given** un `userId` ajeno, de otro tenant o inexistente, **When** se consulta o se borra, **Then** la misma 404.

### User Story 4 - Retención (Priority: P2)

1. **Given** una entrada sin actividad durante 270 días, **When** vence, **Then** el sistema la elimina sola.
2. **Given** una resolución para un envío, **When** `lastActivityAt` tiene más de 24 h, **Then** se actualiza; con menos no hay escritura.

### Edge Cases

- Upsert concurrente del mismo `(tenant, userId)`: el índice único decide; ambos reciben 200 o 201 coherentes.
- Canal sin regla de resolución (distinto de EMAIL, SMS, WHATSAPP y PUSH): 400 `NTF-1003`.
- `externalId` mayor de 200 caracteres en el envío por usuario: 400 (el derivado por dispositivo debe caber en 255).
- Un dispositivo dado de baja entre la resolución y el despacho: el despacho ya lo tolera (E5, fallo permanente).

## Requirements

- **FR-001**: Existe una entrada de directorio `(tenantId, userId, recipientIdentityId, correo?, teléfono?, createdAt, updatedAt,
  lastActivityAt)`; único `(tenantId, userId)` por índice en base de datos. No guarda dispositivos ni marcas de entregabilidad.
- **FR-002**: `POST /recipient-directory` hace upsert (Q4); `POST /recipient-directory/lookups` consulta;
  `POST /recipient-directory-removals` borra la entrada; `POST /recipient-directory-erasures` borra por titular (solo ADMINISTRADOR).
- **FR-003**: Correo y teléfono se validan y normalizan con las reglas de E10 (`EmailAddresses`, `PhoneNumbers.isE164`) antes de
  guardar; se rechaza con 400 `NTF-1006`.
- **FR-004**: La entrada referencia una identidad E5 (Q3); F6 no crea otro tipo de identidad ni copia dispositivos.
- **FR-005**: Correo y teléfono se guardan cifrados con el mismo mecanismo que E5 (AES-256-GCM, AAD
  `tenantId|userId|campo`, versión de clave por documento); nunca aparecen en logs, métricas, eventos ni errores. Las respuestas
  los enmascaran (máscaras de E10) salvo para ADMINISTRADOR. Sin búsqueda inversa por correo o teléfono, por lo que no se guarda huella.
- **FR-006**: Errores de resolución según Q6; ninguno persiste ni encola.
- **FR-007**: `POST /user-notifications` resuelve la dirección y delega en el envío existente (unitario o lote), de modo que
  validaciones, guardas de E10, preferencias (F1) y plantillas (D3) se aplican igual que en el envío por dirección. Canal -> dato:
  EMAIL -> correo; SMS y WHATSAPP -> teléfono; PUSH -> dispositivos E5.
- **FR-008**: PUSH deriva `externalId` como `<externalId>:<deviceId>`; los demás canales usan el `externalId` recibido.
- **FR-009**: Aislamiento por tenant en todo acceso; el 404 es uniforme entre inexistente y ajeno.
- **FR-010**: Roles y mínimos según Q7; `x-status` y `x-minimum-role` en cada operación del contrato.
- **FR-011**: Retención de 270 días por TTL sobre `lastActivityAt`, propiedad `notification.directory.retention-days`; baja y
  borrado son físicos y sin copia recuperable.
- **FR-012**: Logs con `tenantId`, huella de 8 caracteres del `userId` y sin correo ni teléfono; métricas `directory.upserts`,
  `directory.resolutions{channel,outcome}` y `directory.removed{reason}`.
- **FR-013**: Base legal, retención y borrado documentados en la sección siguiente.

## Datos personales

- **Datos tratados de más respecto de E5**: correo y teléfono del usuario, y el `userId` de la aplicación (puede ser un
  identificador con significado; se recomienda que sea opaco). Identifican a una persona.
- **Base legal (supuesto a confirmar por el responsable de datos)**: autorización del titular ante la aplicación que los
  recolectó, Ley 1581 de 2012; la aplicación es la responsable de esa autorización y el servicio, el encargado. Finalidad única:
  entregar las notificaciones que la aplicación solicita.
- **Por qué se guardan**: sin ellos el envío por identificador exige a la aplicación conocer la dirección, que es el problema que
  F6 resuelve. Alternativa descartada: consultar el correo a un sistema externo en cada envío (acopla la disponibilidad del envío).
- **Retención**: 270 días sin actividad; eliminación inmediata al borrar la entrada.
- **Borrado**: físico por baja de la entrada, retención o `POST /recipient-directory-erasures`; este último arrastra la identidad y
  los dispositivos de E5. La auditoría conserva solo la huella de 8 caracteres.
- **Acceso**: correo y teléfono completos solo para ADMINISTRADOR.

## Success Criteria

- **SC-001**: Con 50 ejecuciones secuenciales de upsert, consulta y envío por usuario, el p95 calculado en la prueba es menor de 300 ms.
- **SC-002**: Con dos tenants y el mismo `userId`, y con un `userId` ajeno, ninguna operación revela ni modifica datos del otro
  y el 404 es idéntico (prueba automatizada).
- **SC-003**: Ni los logs, ni los documentos de notificación ni las respuestas no administrativas contienen correo o teléfono
  completos, y el documento del directorio no los guarda en claro; la prueba incluye control positivo (el administrador sí los ve).
- **SC-004**: 20 upserts concurrentes del mismo `(tenant, userId)` dejan exactamente un documento.
- **SC-005**: El envío PUSH a un usuario con 3 dispositivos produce 3 notificaciones; con datos ausentes responde 422 sin crear
  ninguna, con aserción de `Duration` de 1 s para la respuesta.
- **SC-006**: Tras el borrado por titular no queda entrada, identidad ni dispositivo del usuario.

## Out of Scope

Importación masiva, búsqueda inversa por correo o teléfono, sincronización con un directorio externo, interfaz (vive en
Front-Notification-mfe), preferencias (F1), enlace de baja (F5), bandeja del destinatario (F2) y edición de
`api-notificaciones.yaml` (lo hace Contrato).
