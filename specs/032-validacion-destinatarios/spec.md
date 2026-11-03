# Feature Specification: Validacion de destinatarios y rebotes duros (HU2-116)

**Feature Branch**: `feature/HU2-116-validacion-destinatarios`
**Created**: 2026-10-24
**Status**: Draft

## Contexto

Hoy `SendNotificationService` acepta cualquier `recipientAddress` no vacio y la direccion solo se valida al
despachar (`TwilioNotificationProvider` rechaza lo que no es E.164 con `PERMANENT_FAILURE`, despues de haber
persistido y encolado). Brevo no valida nada y no existe ningun consumo de eventos del proveedor. Se envian
notificaciones que nunca podran entregarse y se envia repetidamente a direcciones que ya rebotaron, danando la
reputacion del dominio. Esta historia separa dos mecanismos distintos: validacion de formato (barata, sincrona,
antes de aceptar) y deteccion de rebotes (asincrona, por eventos del proveedor).

Alcance de canal: correo y SMS. Otros canales del catalogo no validan formato hasta tener regla propia.

## User Scenarios & Testing

### User Story 1 - Rechazo por formato antes de encolar (Priority: P1)

El operador envia una notificacion con un correo o telefono mal formado y recibe un rechazo inmediato, sin que se
persista ni se encole nada.

**Independent Test**: enviar con direccion invalida por cada canal; la respuesta es 400 y no existe notificacion ni
mensaje en cola.

**Acceptance Scenarios**:

1. **Given** canal SMS y direccion que no es E.164, **When** se envia, **Then** se rechaza con 400 `INVALID_RECIPIENT`
   y no se persiste.
2. **Given** canal de correo y direccion sin forma `local@dominio` valida, **When** se envia, **Then** igual rechazo.
3. **Given** direccion valida, **When** se envia, **Then** el flujo actual no cambia.
4. **Given** un lote con items validos e invalidos, **When** se envia, **Then** los invalidos quedan `rejected` con
   motivo `INVALID_RECIPIENT` y los validos se aceptan.

### User Story 2 - Rebote duro marca al destinatario (Priority: P1)

Cuando el proveedor informa un rebote duro, la direccion queda marcada como no entregable para ese tenant y canal.

**Independent Test**: publicar un evento de rebote duro y despues enviar a esa direccion; el envio se rechaza.

**Acceptance Scenarios**:

1. **Given** un evento de rebote duro del proveedor para una direccion, **When** se procesa, **Then** la direccion queda
   marcada `UNDELIVERABLE` para ese tenant y canal, con motivo y fecha.
2. **Given** una direccion marcada, **When** se intenta enviar, **Then** se rechaza con 409 `RECIPIENT_UNDELIVERABLE`
   antes de persistir ni encolar.
3. **Given** el mismo evento reentregado, **When** se procesa, **Then** no duplica ni cambia la marca (idempotente).
4. **Given** un rebote blando (buzon lleno, fallo temporal), **When** se procesa, **Then** no se marca.
5. **Given** dos tenants que envian a la misma direccion, **When** rebota para uno, **Then** el otro no se ve afectado.

### User Story 3 - Consulta y levantamiento de la marca (Priority: P2)

Un operador consulta si una direccion esta marcada y un administrador puede levantar la marca.

**Acceptance Scenarios**:

1. **Given** una marca, **When** el operador la consulta, **Then** ve estado, motivo y fecha con la direccion enmascarada.
2. **Given** una marca, **When** un ADMINISTRADOR la levanta, **Then** vuelve a poder enviarse; un OPERADOR recibe 403.

### Edge Cases

- Direccion con espacios o mayusculas: el correo se normaliza (recorte y minusculas) antes de validar y de comparar marcas.
- Evento de rebote sin notificacion origen resoluble (tenant desconocido): se descarta con traza, nunca se marca a ciegas.
- Evento con secreto invalido: se rechaza sin procesar.

## Requirements

- **FR-001**: El envio unitario y por lote MUST validar el formato de la direccion segun canal antes de persistir o encolar.
- **FR-002**: Telefono MUST validar con `PhoneNumbers.isE164`; correo con una regla propia equivalente en `utils`.
- **FR-003**: El rechazo por formato MUST responder 400 `INVALID_RECIPIENT`; en lote, item `rejected` sin abortar el resto.
- **FR-004**: El sistema MUST registrar como rebote duro solo los eventos clasificados como permanentes por el proveedor.
- **FR-005**: La marca MUST ser por `(tenantId, canal, direccion normalizada)` y MUST ser idempotente.
- **FR-006**: El envio a una direccion marcada MUST rechazarse con 409 `RECIPIENT_UNDELIVERABLE` antes de persistir.
- **FR-007**: El tenant de toda operacion HTTP MUST provenir de la sesion del PDP; el del evento de proveedor, de la
  notificacion original localizada por el `messageId` que el proveedor devolvio al aceptar el envio y que el sistema
  MUST persistir en el intento de despacho (ver [plan.md](plan.md#messageid-del-proveedor)).
- **FR-008**: Direcciones en respuestas, logs y eventos MUST ir enmascaradas (telefono con `PhoneNumbers.mask`, correo con
  mascara nueva de `EmailAddresses`) salvo para ADMINISTRADOR, cuyo rol se toma de la sesion del PDP en el controller.
- **FR-009**: El webhook de Brevo MUST autenticarse con el secreto `BREVO_WEBHOOK_SECRET`; si falta o no coincide,
  responde 401 sin procesar. Ni el secreto ni cuerpos con direcciones se registran en logs.
- **FR-010**: Consultar la marca MUST requerir OPERADOR; levantarla, ADMINISTRADOR. La consulta es POST con cuerpo para no exponer la direccion en la URL.
- **FR-011**: En perfiles local y de pruebas el proveedor `simulated` MUST poder simular un rebote duro mediante el mismo
  webhook (ver plan); fuera de esos perfiles el mecanismo no existe.
- **FR-012**: Rechazos y marcas MUST contarse en metricas por tenant y canal, sin la direccion (nombres en el plan).
- **FR-013**: Canales distintos de correo y SMS MUST omitir la validacion de formato y la comparacion de marcas usa la
  direccion tal cual (recortada); solo el correo se pasa a minusculas.

## Success Criteria

- **SC-001**: 100 % de las direcciones mal formadas se rechazan sin persistir ni encolar (prueba automatizada).
- **SC-002**: Tras un rebote duro procesado, el siguiente envio a esa direccion se rechaza en la misma peticion, sin
  llamar al proveedor (prueba E2E).
- **SC-003**: Ninguna marca de un tenant afecta a otro (prueba con dos tenants).
- **SC-004**: La validacion de formato es una funcion pura sin I/O; la prueba unitaria afirma que 10 000 validaciones
  terminan en menos de 1 s (margen amplio para CI).
## Assumptions

- Brevo es el unico proveedor de correo con webhooks de rebote; Twilio SMS queda fuera del rebote en esta historia y
  solo tiene validacion de formato.
- La marca solo bloquea envios nuevos; las notificaciones ya aceptadas no se reclasifican.
