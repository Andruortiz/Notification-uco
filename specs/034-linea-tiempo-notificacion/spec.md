# Feature Specification: Linea de tiempo por notificacion (HU2-118)

**Feature Branch**: `feature/HU2-118-linea-tiempo-notificacion`

**Created**: 2026-10-24

**Status**: Draft

## Contexto

La busqueda de notificaciones ya devuelve `deliveryAttempts` con fecha, resultado, origen y proveedor. El detalle por id
(`GET /notifications/detail`) solo devuelve estado, canal, ultimo proveedor y `correlationId` de la notificacion. Para
diagnosticar, el operador necesita ver cada intento con su causa de fallo, su latencia y el `correlationId` con el que se
ejecuto, y hoy esos tres datos no se guardan. Los logs no pueden contener el texto crudo del proveedor ni datos personales.

## Clarifications

### Session 2026-10-24

- Q: Quien ve la causa tecnica del fallo? -> A: OPERADOR y ADMINISTRADOR; CLIENTE no accede a la linea de tiempo.
- Q: Como se expresa la causa? -> A: codigo de un conjunto cerrado; nunca texto libre del proveedor ni mensajes de excepcion.
- Q: Que ve cada rol del destinatario? -> A: OPERADOR lo ve enmascarado; ADMINISTRADOR completo.
- Q: Origen del tenant? -> A: la sesion del PDP; no se acepta tenant en la peticion.
- Q: Ruta del recurso nuevo? -> A: `GET /notifications/timeline` con `notificationId` como parametro de consulta (ruta literal).

## User Scenarios & Testing

### User Story 1 - Ver la linea de tiempo de una notificacion (Priority: P1)

Un operador consulta una notificacion por id y ve, en orden cronologico, cada intento con fecha, proveedor, resultado,
origen, ciclo, latencia, causa del fallo (si falla) y `correlationId`.

**Independent Test**: despachar una notificacion con proveedor simulado que falla de forma recuperable y luego acepta;
consultar la linea de tiempo y verificar los dos intentos en orden con todos sus campos.

**Acceptance Scenarios**:

1. **Given** una notificacion con un intento aceptado, **When** el operador consulta su linea de tiempo, **Then** ve un
   intento con resultado `ACCEPTED`, proveedor, latencia en milisegundos, `correlationId` y sin causa de fallo.
2. **Given** una notificacion con un intento fallido y uno posterior aceptado, **When** consulta, **Then** ve ambos
   ordenados por fecha ascendente y el fallido trae un codigo de causa.
3. **Given** una notificacion con reintento manual, **When** consulta, **Then** el intento manual aparece con origen
   `MANUAL` y su ciclo.
4. **Given** una notificacion sin intentos aun, **When** consulta, **Then** recibe la cabecera con lista de intentos vacia.
5. **Given** un id inexistente o de otro tenant, **When** consulta, **Then** recibe 404 sin revelar si existe en otro tenant.

### User Story 2 - Diagnostico sin exponer datos sensibles (Priority: P1)

El contenido de la linea de tiempo y de los logs de despacho no incluye texto crudo del proveedor ni datos personales
visibles para quien no corresponde.

**Acceptance Scenarios**:

1. **Given** un fallo con mensaje crudo del proveedor, **When** se persiste y se consulta, **Then** solo aparece el codigo
   de causa; el mensaje crudo no se almacena ni se registra en logs.
2. **Given** un OPERADOR, **When** consulta, **Then** el destinatario llega enmascarado.
3. **Given** un ADMINISTRADOR, **When** consulta, **Then** el destinatario llega completo.
4. **Given** un CLIENTE, **When** consulta, **Then** recibe 403.

### User Story 3 - Aislamiento por tenant (Priority: P1)

**Acceptance Scenarios**:

1. **Given** dos tenants con notificaciones, **When** el operador del tenant A pide el id de una del tenant B, **Then** recibe
   404 y ningun dato de B.

### Edge Cases

- Intentos historicos persistidos antes de esta historia: latencia, causa y `correlationId` por intento llegan nulos; la
  respuesta sigue siendo valida y el intento se muestra.
- Fallo sin latencia medible (proveedor deshabilitado, adjuntos no soportados): latencia nula.
- Notificacion con muchos ciclos de reintento: se devuelven todos los intentos sin paginar.

## Requirements

### Functional Requirements

- **FR-001**: El sistema MUST exponer una consulta de linea de tiempo por `notificationId` que devuelva la cabecera
  (id, estado, canal, destinatario, `correlationId` de la notificacion) y la lista de intentos.
- **FR-002**: Cada intento MUST incluir fecha, proveedor, resultado, origen, ciclo, latencia en milisegundos (nula si no
  aplica), codigo de causa (nulo si el resultado es `ACCEPTED`) y `correlationId`.
- **FR-003**: El sistema MUST persistir latencia, causa y `correlationId` por intento al registrar cada intento de despacho
  y de reintento manual; los documentos previos MUST seguir leyendose.
- **FR-004**: La causa MUST ser un codigo de un conjunto cerrado y estable; el texto crudo del proveedor y los mensajes de
  excepcion MUST NOT almacenarse, devolverse ni registrarse en logs.
- **FR-005**: Los intentos MUST ordenarse por fecha ascendente.
- **FR-006**: El acceso MUST requerir rol OPERADOR o superior; el tenant MUST tomarse de la sesion y filtrar la consulta.
- **FR-007**: El destinatario MUST enmascararse salvo para ADMINISTRADOR.
- **FR-008**: Un id inexistente o de otro tenant MUST producir 404 indistinguible.
- **FR-009**: La nueva ruta MUST quedar listada como operacion para el contrato y para el aprovisionamiento del PDP, con
  rol minimo OPERADOR.
- **FR-010**: Los logs nuevos MUST incluir `correlationId` y MUST NOT incluir destinatario sin enmascarar.

### Key Entities

- **Intento de entrega** (existente, se amplia): fecha, resultado, origen, proveedor, ciclo + latencia, causa, `correlationId`.
- **Causa de fallo**: codigo cerrado (ver `plan.md`).
- **Linea de tiempo**: cabecera de la notificacion + intentos ordenados.

## Contrato que consumira la UI (repo aparte)

`GET /notifications/timeline?notificationId=<id>` -> 200 con `notificationId`, `status`, `channelType`, `recipient`,
`correlationId`, `attempts[]` (`occurredOn`, `providerId`, `result`, `origin`, `cycle`, `latencyMillis`, `failureCause`,
`correlationId`); 400 id invalido; 401; 403 rol insuficiente; 404 no encontrada. `failureCause` y `latencyMillis` pueden
ser `null`. Operacion nueva para el contrato; este trabajo no modifica el archivo OpenAPI.

## Success Criteria

- **SC-001**: El operador ve los datos de diagnostico de una notificacion de hasta 20 intentos con una sola consulta, que
  responde en menos de 1 s en el entorno de pruebas (prueba automatizada con asercion de `Duration`).
- **SC-002**: En 100 % de los intentos nuevos, latencia (cuando hay llamada al proveedor), causa (cuando falla) y
  `correlationId` quedan presentes.
- **SC-003**: Ninguna respuesta ni log de las pruebas contiene el texto crudo del proveedor sembrado.
- **SC-004**: Cero datos de otro tenant en las pruebas con dos tenants.

## Assumptions

- El `correlationId` de un intento es el de la notificacion salvo que el reintento manual aporte el suyo; ver pregunta abierta.
- La causa se deriva de la categoria del fallo conocida por el despacho, sin cambiar el contrato de los proveedores.
- Los intentos existentes no se migran.
- El rol minimo OPERADOR solo se hace efectivo con el PDP si el recurso se aprovisiona (HU2-099); sin PEP lo aplica la politica local.
- Fuera de alcance: UI, edicion del OpenAPI, exportacion, filtros y paginacion de intentos.
