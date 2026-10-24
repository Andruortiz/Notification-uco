# Feature Specification: Reintento manual y reenvío por otro canal

**Feature Branch**: `feature/HU2-117-reintento-manual-reenvio`

**Created**: 2026-10-24

**Status**: Clarificado (respuestas recomendadas adoptadas; ver Clarifications)

**Input**: HU2-117 — Como OPERADOR quiero reintentar una notificación fallida desde la consola y reenviarla por otro canal.

**Trazabilidad**: CU-06, carril Despacho y proveedores. Sustituye y absorbe la spec 018 (HU2-027/HU2-028, nunca implementada), adaptada al contrato vigente: rutas literales, sesión del PDP y roles. El front (`Front-Notification-mfe`) es otro repositorio; aquí solo se fija el contrato que consume.

## Clarifications

### Session 2026-10-24

No se puede consultar al usuario durante la redacción; cada pregunta lleva la respuesta adoptada. Si alguna cambia al revisar, se indica qué afecta.

- Q: ¿El reintento manual reinicia el contador de intentos? → A: **No** (decisión del usuario). Todo fallo recuperable cuenta contra el mismo presupuesto, venga de donde venga. Afecta a FR-007 y Edge Cases. Consecuencia: el método de dominio `retryManually` (que abre un ciclo nuevo y reinicia el conteo) no se usa y se retira (ver plan, Decisión 2).
- Q: ¿Qué recibe quien pierde una carrera entre dos reintentos? → A: **409** (el contrato ya declara `Conflict` para modificación concurrente). Un estado que no admite reintento sigue siendo **400**. Cambia respecto a la 018 (que usaba 400 porque no había 409). Afecta a FR-006, SC-003.
- Q: ¿Qué estados admiten reenvío por otro canal? → A: **solo FAILED**. En RECOVERABLE el original aún puede entregarse y el reenvío duplicaría el mensaje; DELIVERED, PENDING, IN_PROCESS y DISCARDED no lo admiten. Afecta a FR-009.
- Q: ¿El reenvío modifica la notificación original? → A: **No**. Crea una notificación nueva (otro `notificationId`, canal elegido, mismo destinatario y contenido) que sigue el flujo normal; la original queda FAILED. El vínculo se deja en el `externalId` derivado `<externalId original>-resend-<canal>`, que además hace el reenvío idempotente por canal. Sin campo nuevo persistido. Afecta a FR-010, FR-012.
- Q: ¿De dónde sale la dirección en el canal nuevo? → A: **Del cuerpo de la solicitud**: el agregado guarda una sola dirección (`Recipient.address`), sin contacto alternativo almacenado. Canal igual al original, dirección inválida para el canal o canal no disponible: **400** con motivo, sin crear nada. La dirección nunca se devuelve ni se registra completa. Afecta a FR-009, FR-011, FR-014.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reintentar una notificación FAILED o RECOVERABLE (Priority: P1)

Un OPERADOR ve en la consola una notificación de su tenant en FAILED (o RECOVERABLE) y, corregida la causa, pide un nuevo intento sin que el sistema cliente la reenvíe.

**Independent Test**: provocar un FAILED, pedir el reintento, comprobar el 202 con PENDING y que termina DELIVERED tras un intento real.

**Acceptance Scenarios**:

1. **Given** una notificación del tenant en FAILED, **When** el OPERADOR pide el reintento, **Then** recibe 202 con el estado PENDING y la notificación se intenta de nuevo.
2. **Given** una en RECOVERABLE, **When** pide el reintento, **Then** 202 y el intento ocurre de inmediato, sin esperar el plazo automático.
3. **Given** el reintento aceptado y el proveedor disponible, **When** lo acepta, **Then** pasa a DELIVERED.
4. **Given** PENDING, IN_PROCESS, DELIVERED o DISCARDED, **When** pide el reintento, **Then** 400 con el estado actual y nada cambia ni se encola.
5. **Given** un id inexistente, **When** pide el reintento, **Then** 404.
6. **Given** una notificación que agotó su presupuesto de reintentos, **When** el intento manual vuelve a fallar de forma recuperable, **Then** regresa a FAILED (el contador no se reinició).

---

### User Story 2 - Reenviar por otro canal (Priority: P1)

Cuando el canal original no funciona (por ejemplo, el correo rebota), el OPERADOR reenvía el mismo contenido por otro canal indicando la dirección en ese canal.

**Independent Test**: con una notificación FAILED por correo y un teléfono indicado, pedir el reenvío por SMS y comprobar que existe una notificación nueva por SMS que se entrega y que la original no cambió.

**Acceptance Scenarios**:

1. **Given** una notificación FAILED del tenant y un canal distinto habilitado con una dirección válida para él, **When** el OPERADOR pide el reenvío con ambos, **Then** recibe 202 con la notificación nueva en PENDING (otro id y canal) y la original sigue FAILED.
2. **Given** un estado distinto de FAILED, **When** pide el reenvío, **Then** 400 sin crear nada.
3. **Given** el mismo canal que el original, o una dirección inválida para el canal, o un canal inexistente o deshabilitado, **When** pide el reenvío, **Then** 400 con motivo y sin crear nada.
4. **Given** un reenvío ya hecho por ese canal, **When** se pide otra vez, **Then** 409 y no se crea una segunda notificación.
5. **Given** un id inexistente, **When** pide el reenvío, **Then** 404.

---

### User Story 3 - Aislamiento, rol y datos personales (Priority: P1)

El tenant sale de la sesión del PDP, nunca de la solicitud. Un tenant no reintenta ni reenvía notificaciones de otro, y la respuesta es indistinguible de un id inexistente. Solo OPERADOR o ADMINISTRADOR operan; CLIENTE recibe 403. Ninguna respuesta, error ni log de estas operaciones expone datos personales sin enmascarar (correo, teléfono, token) salvo a ADMINISTRADOR.

**Independent Test**: dos tenants; el tenant B pide reintento y reenvío sobre la FAILED del A y recibe 404 idéntico al de un id inexistente; control positivo: el dueño recibe 202.

**Acceptance Scenarios**:

1. **Given** una FAILED del tenant A, **When** la sesión del tenant B pide reintento o reenvío, **Then** 404 con el mismo mensaje que un id inexistente, sin cambios ni llamadas al proveedor.
2. **Given** una sesión CLIENTE, **When** invoca cualquiera de las dos operaciones, **Then** 403.
3. **Given** la dirección enviada en un reenvío, **When** el OPERADOR recibe la respuesta, un error o se emite el log, **Then** la dirección completa no aparece.

---

### User Story 4 - Distinguir manual de automático en la auditoría (Priority: P2)

El histórico de intentos muestra cuáles disparó una persona (MANUAL) y cuáles el sistema (AUTOMATIC).

**Acceptance Scenarios**:

1. **Given** un intento automático fallido seguido de un reintento manual, **When** se consulta el histórico, **Then** el primero figura AUTOMATIC y el segundo MANUAL.
2. **Given** un reintento manual que falla de forma recuperable y luego se reintenta solo, **Then** el intento posterior es AUTOMATIC.
3. **Given** cualquier despacho sin intervención, **Then** todos sus intentos son AUTOMATIC.

---

### Edge Cases

- Dos reintentos simultáneos (o uno manual contra el automático): uno recibe 202 y el otro 409; un solo intento ante el proveedor. Canal deshabilitado al reintentar: se acepta y se comporta como cualquier despacho con canal deshabilitado.
- Reenvío con adjuntos que el canal destino no soporta: 400.
- Id con forma inválida: 404, igual que la consulta de estado.
- Dos reenvíos simultáneos al mismo canal: uno 202 y el otro 409 (por el `externalId` derivado único).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio MUST exponer el reintento manual de una notificación (operación `retryNotification` del contrato, hoy `planned`) con rol mínimo OPERADOR.
- **FR-002**: El servicio MUST exponer el reenvío por otro canal como operación nueva, con rol mínimo OPERADOR.
- **FR-003**: El reintento MUST aceptarse solo desde FAILED o RECOVERABLE según `StatusTransitionPolicy`; devuelve la notificación a PENDING, la persiste y la encola por la cola de despacho antes de responder 202 con la vista de estado.
- **FR-004**: Un estado que no admite reintento o reenvío MUST responder 400 con el estado actual, sin modificar ni encolar nada.
- **FR-005**: Notificación inexistente o de otro tenant MUST responder 404 con el mismo mensaje en ambos casos, sin efectos.
- **FR-006**: Ante concurrencia sobre la misma notificación, a lo sumo una solicitud se acepta y produce a lo sumo un intento; la perdedora MUST recibir 409.
- **FR-007**: El reintento manual MUST NOT reiniciar el contador de intentos: los fallos recuperables cuentan contra el mismo presupuesto sin mirar el origen.
- **FR-008**: El intento resultante de un reintento manual MUST registrarse con origen MANUAL; todo otro intento, AUTOMATIC. El histórico expone el origen.
- **FR-009**: El reenvío MUST aceptarse solo desde FAILED y con un canal distinto al original, habilitado en el catálogo y con una dirección válida para él recibida en la solicitud.
- **FR-010**: El reenvío MUST crear una notificación nueva (mismo tenant, `recipientId` y contenido; canal y dirección recibidos) por el flujo normal de aceptación y despacho, y MUST NOT modificar la original.
- **FR-011**: El reenvío con canal igual, desconocido o deshabilitado, o dirección inválida para el canal, MUST responder 400 con motivo, sin crear nada.
- **FR-012**: El `externalId` de la notificación reenviada MUST derivarse de la original y del canal de forma determinista, de modo que repetir el reenvío por el mismo canal responda 409.
- **FR-013**: El tenant MUST salir de la sesión del PDP; ninguna operación acepta tenant en cabecera, ruta o cuerpo.
- **FR-014**: Respuestas, mensajes de error y logs de ambas operaciones MUST NOT contener datos personales sin enmascarar, salvo lo que un ADMINISTRADOR vea en consultas de detalle ya existentes.
- **FR-015**: Cada operación MUST emitir un log estructurado con correlación, tenant y `notificationId` (el de la original y, en reenvío, el de la nueva), sin secretos.

### Key Entities

- **Notificación**: agregado con estado e histórico de intentos; el reintento la mueve de FAILED/RECOVERABLE a PENDING; el reenvío no la altera.
- **Intento de entrega**: registro inmutable con resultado, proveedor y **origen** (MANUAL/AUTOMATIC).
- **Solicitud de despacho**: unidad encolada; lleva el origen del intento que producirá.
- **Reenvío**: notificación nueva derivada de una FAILED, identificada por su `externalId` derivado.

## Success Criteria *(mandatory)*

- **SC-001**: Una FAILED reintentada con el proveedor disponible termina entregada en **5 s o menos** desde la aceptación.
- **SC-002**: El 100 % de los intentos producidos por reintento manual figuran MANUAL; el 100 % de los demás, AUTOMATIC.
- **SC-003**: Dos solicitudes simultáneas sobre la misma notificación producen exactamente **una** aceptación y **un** intento ante el proveedor; lo mismo para dos reenvíos al mismo canal (una notificación nueva).
- **SC-004**: 0 cruces entre tenants en reintento y reenvío; la respuesta es indistinguible de un id inexistente.
- **SC-005**: Ninguna solicitud sobre un estado no admitido produce efectos ni llamadas al proveedor.
- **SC-006**: Un reintento manual de una notificación con presupuesto agotado que vuelve a fallar de forma recuperable termina en FAILED (el contador no se reinició).
- **SC-007**: Reenviar una FAILED por correo indicando un teléfono entrega una notificación nueva por SMS en **5 s o menos** y deja la original sin cambios.
- **SC-008**: 0 apariciones de correo o teléfono completos en respuestas, errores y logs de ambas operaciones para un OPERADOR.

## Assumptions

- Autenticación y roles ya existen (specs 025–028): el principal de la sesión aporta tenant y rol; las rutas protegidas se registran desde el contrato.
- El contrato (`api-notificaciones.yaml`) lo actualiza el carril Contrato; esta historia lista las rutas en `plan.md` y no edita el archivo.
- La consola enmascara según el rol con los datos que reciba; el servidor no devuelve contacto en estas operaciones.
- Mensajes ya encolados sin origen se tratan como AUTOMATIC.
- Persiste un hueco preexistente: una PENDING con intentos y sin mensaje en la cola (encolado fallido tras guardar) no se recupera sola (ver plan, Pendiente explícito).

## Out of Scope

- Lote, proveedor forzado, reiniciar el presupuesto, registrar la persona que opera (solo tenant y correlación), campo persistido que vincule original y reenvío (se deriva del `externalId`).
- La interfaz de la consola (repositorio aparte).
