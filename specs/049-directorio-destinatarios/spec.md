# Feature Specification: Directorio de destinatarios por tenant (HU2-133)

**Feature Branch**: `feature/HU2-133-directorio-destinatarios`

**Created**: 2026-10-25

**Status**: Draft (pendiente de aprobación de spec y plan)

## Contexto

Hoy quien envía debe conocer la dirección concreta (correo, teléfono o token FCM). Esta historia añade un directorio por tenant
que cada aplicación llena por API, y un envío por identificador de usuario: la plataforma resuelve la dirección según el canal.
Depende de B1 (spec 029), E5 (identidad y dispositivos, spec 036), E10 (formato y no entregable, spec 032), D3 (plantillas, spec
043) y F1 (preferencias, spec 048). Orden de integración: E3 -> G3 -> G2 -> E10 -> E5 -> D3 -> F1 -> F6.

Principio rector: **no duplicar**. Los dispositivos y la identidad los guarda E5; la marca de no entregable, E10. F6 solo guarda
el identificador de usuario de la aplicación, el correo y el teléfono.

## Clarifications

### Session 2026-10-25 (propuestas del autor, a aprobar con la spec)

- **Q1 - Clave**: `userId` es único por `(tenantId, userId)`. El tenant sale solo de la sesión (B1). Afecta FR-001, FR-009.
- **Q2 - Minimización**: solo `userId`, correo, teléfono y la referencia a la identidad de E5. Sin nombre, documento ni fecha de
  nacimiento. Los dispositivos viven solo en E5. Afecta FR-001, FR-004.
- **Q3 - Vínculo con E5**: cada entrada apunta a una identidad de E5 obtenida con `RecipientIdentityPort.findOrCreateBySubject`.
  Si la identidad ya apunta a otra entrada del tenant: 409 `NTF-3044`. Afecta FR-004.
- **Q4 - Escritura**: `POST /recipient-directory` es upsert por reemplazo del par correo/teléfono; un campo ausente o nulo borra
  ese dato. Sin importación masiva. Afecta FR-002.
- **Q5 - Envío por usuario**: `POST /user-notifications` (aprobado; `POST /notifications` no cambia). Por canal: EMAIL, una
  notificación al correo; SMS y WHATSAPP, al teléfono; PUSH, una por dispositivo; IN_APP, por identidad. Cada comando lleva
  `recipientIdentityId` y `recipientId` con ese valor, para que F1 evalúe preferencias. Afecta FR-007.
- **Q6 - Errores**: usuario ausente o de otro tenant: 404 `NTF-3042` uniforme; sin dato para el canal: 422 `NTF-3043` con el
  canal; nada se persiste ni se encola. Afecta FR-006.
- **Q7 - Roles** (nombres de B2): escribir (upsert y baja) es de `ADMINISTRADOR_APLICACION`; consultar, de `CLIENTE` (enmascarado),
  `ADMINISTRADOR_APLICACION` y `ADMINISTRADOR_PLATAFORMA` (completo); el borrado por titular, de los dos administradores. Pregunta
  menor abierta: si `CLIENTE` es productor legítimo de la escritura. Afecta FR-005, FR-010.
- **Q8 - Retención**: 270 días sin actividad, según la tabla única de retención del Nivel 2. Afecta FR-011.
- **Q9 - IN_APP**: se resuelve por identidad; no hay dato de contacto, así que no da 422 `NTF-3043`, pero exige la entrada (404
  `NTF-3042`). Afecta FR-007.
- **Q10 - Borrado por titular**: un único `EraseRecipientDataUseCase` en `core` con el puerto `RecipientDataEraser`; cada historia
  registra su contribuyente. Afecta FR-014.
- **Q11 - SC-003**: el límite de correo y teléfono en claro en documentos de notificación EMAIL y SMS queda declarado, no probado.

## User Scenarios & Testing

### User Story 1 - Llenar el directorio (Priority: P1)

**Independent Test**: `POST /recipient-directory` con dos tenants y el mismo `userId`.

1. **Given** una sesión de `ADMINISTRADOR_APLICACION`, **When** envía `userId`, correo y teléfono válidos, **Then** 201 con la
   entrada (datos enmascarados) y `recipientId` de la identidad E5.
2. **Given** una entrada existente, **When** repite el `userId` con otros datos, **Then** 200 y los datos quedan reemplazados.
3. **Given** un correo mal formado o un teléfono que no es E.164, **When** se escribe, **Then** 400 `NTF-1006` y no se guarda nada.
4. **Given** el mismo `userId` en otro tenant, **When** se escribe, **Then** son entradas independientes.
5. **Given** una identidad (`subject`) ya vinculada a otra entrada del tenant, **When** se escribe, **Then** 409 `NTF-3044`.

### User Story 2 - Enviar a un identificador de usuario (Priority: P1)

**Independent Test**: `POST /user-notifications` por EMAIL, SMS, PUSH e IN_APP con entradas completas e incompletas.

1. **Given** un usuario con correo, **When** se envía por EMAIL, **Then** se acepta una notificación a ese correo con las guardas
   de E10.
2. **Given** un usuario con tres dispositivos, **When** se envía por PUSH, **Then** 202 con tres items, uno por dispositivo, con
   `externalId` derivado y estable (idempotente ante reintento).
3. **Given** un usuario sin teléfono, **When** se pide SMS, **Then** 422 `NTF-3043` y no existe notificación ni mensaje en cola.
4. **Given** un `userId` inexistente o de otro tenant, **When** se envía, **Then** la misma 404 `NTF-3042`.
5. **Given** una dirección resuelta no entregable por E10, **When** se envía, **Then** el item queda `rejected` con
   `RECIPIENT_UNDELIVERABLE`; los demás items se aceptan.
6. **Given** un usuario con entrada, **When** se envía por IN_APP, **Then** la notificación va a su identidad; sin entrada, 404.

### User Story 3 - Consultar y borrar (Priority: P1)

1. **Given** una entrada, **When** `POST /recipient-directory/lookups` con `userId`, **Then** correo y teléfono enmascarados
   (completos para los dos administradores).
2. **Given** una entrada del tenant, **When** `POST /recipient-directory-removals`, **Then** 204: se elimina la entrada con su
   correo y teléfono; la identidad y los dispositivos de E5 permanecen.
3. **Given** una solicitud de borrado del titular, **When** un administrador invoca `POST /recipient-directory-erasures`,
   **Then** 204 y se borran entrada, identidad, dispositivos y datos de los demás contribuyentes (FR-014); un `CLIENTE` recibe 403.
4. **Given** un `userId` ajeno, de otro tenant o inexistente, **When** se consulta, se borra o se pide borrado por titular,
   **Then** la misma 404.

### User Story 4 - Retención (Priority: P2)

1. **Given** una entrada sin actividad durante 270 días, **When** vence, **Then** el sistema la elimina sola.
2. **Given** una resolución para un envío, **When** `lastActivityAt` tiene más de 24 h, **Then** se actualiza; con menos, no hay
   escritura.

### Edge Cases

- Upsert concurrente del mismo `(tenant, userId)`: el índice único decide; ambos reciben 200 o 201 coherentes.
- Canal sin regla de resolución (fuera de EMAIL, SMS, WHATSAPP, PUSH e IN_APP): 400 `NTF-1003`.
- `externalId` mayor de 200 caracteres en el envío por usuario: 400 (el derivado por dispositivo debe caber en 255).
- Dispositivo dado de baja entre resolución y despacho: el despacho ya lo tolera (E5, fallo permanente).
- Identidad con dispositivos pero sin entrada: el borrado por titular responde 404; sus dispositivos se borran con
  `POST /device-erasures`.

## Requirements

- **FR-001**: Entrada `(tenantId, userId, recipientIdentityId, correo?, teléfono?, createdAt, updatedAt, lastActivityAt)`; único
  `(tenantId, userId)` y `recipientIdentityId` único por tenant, por índice en base de datos. No guarda dispositivos ni marcas.
- **FR-002**: `POST /recipient-directory` hace upsert (Q4); `POST /recipient-directory/lookups` consulta;
  `POST /recipient-directory-removals` borra la entrada; `POST /recipient-directory-erasures` borra por titular (FR-014).
- **FR-003**: Correo y teléfono se validan y normalizan con las reglas de E10 (`EmailAddresses`, `PhoneNumbers.isE164`); si no
  cumplen, 400 `NTF-1006` y nada se guarda.
- **FR-004**: La entrada referencia una identidad de E5 mediante `RecipientIdentityPort` (`findById`, `findBySubject`,
  `findOrCreateBySubject`, `delete`). F6 no define otro puerto de identidad ni copia dispositivos.
- **FR-005**: Correo y teléfono se guardan cifrados con el mecanismo de E5 (AES-256-GCM, AAD `tenantId|userId|campo`, versión de
  clave por documento); nunca aparecen en logs, métricas, eventos ni errores. Las respuestas los enmascaran (máscaras de E10)
  salvo para `ADMINISTRADOR_APLICACION` y `ADMINISTRADOR_PLATAFORMA`. No hay búsqueda inversa, así que no se guarda huella.
- **FR-006**: Errores de resolución según Q6; ninguno persiste ni encola.
- **FR-007**: `POST /user-notifications` resuelve la dirección y delega en el envío existente (unitario o lote), de modo que
  validaciones, guardas de E10, preferencias (F1) y plantillas (D3) se aplican igual que en el envío por dirección. Canal -> dato:
  EMAIL -> correo; SMS y WHATSAPP -> teléfono; PUSH -> dispositivos de E5; IN_APP -> identidad. Cada comando generado lleva
  `recipientId` y `recipientIdentityId` con el `recipientIdentityId` de la entrada.
- **FR-008**: PUSH deriva `externalId` como `<externalId>:<deviceId>`; los demás canales usan el `externalId` recibido.
- **FR-009**: Aislamiento por tenant en todo acceso; el 404 es uniforme entre inexistente y ajeno.
- **FR-010**: Cada operación nueva declara `x-status: planned` y `x-allowed-roles` según Q7 y la tabla de plan.md.
- **FR-011**: Retención de 270 días por TTL sobre `lastActivityAt`, propiedad `notification.directory.retention-days`; baja y
  borrado son físicos y sin copia recuperable.
- **FR-012**: Logs con `tenantId`, huella de 8 caracteres del `userId` y sin correo ni teléfono. Cada escritura, baja y borrado por
  titular registra actor (identificador opaco del principal), operación, resultado y huella, sin datos personales. Este registro
  es de F6, porque B6 no audita datos de usuario. Métricas `directory.upserts`, `directory.resolutions{channel,outcome}`,
  `directory.removed{reason}` y `directory.entries`.
- **FR-013**: Base legal, retención y borrado documentados en la sección siguiente.
- **FR-014**: El borrado por titular usa `EraseRecipientDataUseCase`, que recibe `(tenantId, recipientIdentityId)` y llama a cada
  `RecipientDataEraser` registrado, en este orden: E5 (dispositivos e identidad), E6 (bandeja), F1 (preferencias) y F6 (entrada,
  la última). Los contribuyentes son idempotentes; la entrada va al final para que un reintento tras fallo encuentre la entrada y
  repita todo. `POST /recipient-directory-erasures` y `POST /device-erasures` (E5) delegan en él. La marca de no entregable de E10
  no se borra: es dato de la dirección, no del titular.

## Datos personales

- **Datos tratados de más respecto de E5**: correo y teléfono del usuario, y el `userId` de la aplicación (se recomienda que sea
  opaco). Identifican a una persona.
- **Base legal (supuesto a confirmar por el responsable de datos)**: autorización del titular ante la aplicación que los
  recolectó, Ley 1581 de 2012; la aplicación es la responsable y el servicio, el encargado. Finalidad única: entregar las
  notificaciones que la aplicación solicita.
- **Por qué se guardan**: sin ellos el envío por identificador exige a la aplicación conocer la dirección. Alternativa descartada:
  consultar el correo a un sistema externo en cada envío (acopla la disponibilidad del envío).
- **Retención**: 270 días sin actividad; eliminación inmediata al borrar la entrada.
- **Borrado**: físico por baja, retención o borrado por titular (FR-014), que arrastra identidad y dispositivos de E5. La auditoría
  conserva solo la huella de 8 caracteres.
- **Acceso**: correo y teléfono completos solo para `ADMINISTRADOR_APLICACION` y `ADMINISTRADOR_PLATAFORMA`.
- **Límite declarado (SC-003)**: los documentos de notificación EMAIL y SMS conservan la dirección en claro, porque E5 solo cifra
  el token PUSH. Dueño: Equipo de backend; fecha: 2026-12-15. Opción futura: cifrar o enmascarar la dirección en el documento de
  notificación.

## Success Criteria

- **SC-001**: Con 50 ejecuciones secuenciales de upsert, consulta y envío por usuario, el p95 calculado en la prueba es menor de
  300 ms.
- **SC-002**: Con dos tenants y el mismo `userId`, y con un `userId` ajeno, ninguna operación revela ni modifica datos del otro
  y el 404 es idéntico (prueba automatizada).
- **SC-003**: Ni los logs ni las respuestas no administrativas contienen correo o teléfono completos, y el documento del directorio
  no los guarda en claro; la prueba incluye control positivo (el administrador sí los ve). Los documentos de notificación EMAIL y
  SMS quedan fuera, según el límite declarado.
- **SC-004**: 20 upserts concurrentes del mismo `(tenant, userId)` dejan exactamente un documento.
- **SC-005**: El envío PUSH a un usuario con 3 dispositivos produce 3 notificaciones; con datos ausentes responde 422 sin crear
  ninguna, con aserción de `Duration` de 1 s para la respuesta.
- **SC-006**: Tras el borrado por titular no queda entrada, identidad ni dispositivo del usuario.

## Out of Scope

Importación masiva, búsqueda inversa por correo o teléfono, sincronización con un directorio externo, interfaz (vive en
Front-Notification-mfe), preferencias (F1), enlace de baja (F5), bandeja del destinatario (F2) y edición de
`api-notificaciones.yaml` (lo hace Contrato).
