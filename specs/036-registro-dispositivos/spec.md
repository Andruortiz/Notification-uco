# Feature Specification: Registro de dispositivos por destinatario (HU2-120)

**Feature Branch**: `feature/HU2-120-registro-dispositivos`

**Created**: 2026-10-24

**Status**: Draft

## Contexto

Hoy `recipientId` es la dirección misma (teléfono, correo o token FCM): quien quiere notificar a una persona debe conocer su
token, y el servicio no sabe a quién pertenece. Esta historia introduce una identidad de destinatario propia, separada de la
dirección, y una lista de dispositivos registrados por destinatario. El adaptador FCM (spec 010) ya clasifica 404 y 410 como
fallo permanente. Quedan fuera las historias que dependen de esta (preferencias por identidad F1, directorio F6, validación E10);
el modelo solo queda extensible para ellas.

## Clarifications

### Session 2026-10-24 (respuestas recomendadas, pendientes de revisión)

- **Q1 - Origen del tenant**: siempre la sesión validada por el PDP (`AuthenticatedPrincipal.tenantId`); ningún cuerpo ni
  parámetro lo fija. Afecta FR-003, FR-009.
- **Q2 - Componente de origen**: es el `subject` de la sesión que registra; el cuerpo no lo declara. Afecta FR-003.
- **Q3 - Unicidad**: único `(tenantId, token)`; el mismo token en dos tenants son registros independientes. Registrar de nuevo
  un token existente para el mismo destinatario es idempotente; para otro destinatario, 409. Afecta FR-004.
- **Q4 - Borrado automático**: solo con 404 cuyo cuerpo indique token no registrado (`UNREGISTERED`) o con 410. Un 404 por URL
  o proyecto mal configurado no borra tokens. Afecta FR-007.
- **Q5 - Envío por persona**: el fan-out (enviar a todos los dispositivos de un destinatario) NO entra; el envío por dirección
  no cambia. Lo consumirá F6. Afecta Out of Scope.
- **Q6 - Roles**: CLIENTE o superior registra, lista y da de baja dentro de su tenant; solo ADMINISTRADOR ve el token completo.

## User Scenarios & Testing

### User Story 1 - Registrar un dispositivo (Priority: P1)

Un componente registra el token de un dispositivo para un destinatario sin que quien notifique deba conocerlo después.

**Independent Test**: `POST /devices` con dos tenants distintos y el mismo token.

**Acceptance Scenarios**:

1. **Given** un destinatario nuevo, **When** se registra un token con plataforma y, opcionalmente, `subject`, **Then** responde 201
   con `deviceId`, `recipientId` generado por el servicio, tenant de la sesión, componente de origen y token enmascarado.
2. **Given** un `recipientId` existente del tenant, **When** se registra otro token, **Then** el destinatario tiene dos dispositivos.
3. **Given** un token ya registrado para el mismo destinatario, **When** se registra de nuevo, **Then** 200 con el dispositivo
   existente y sin duplicado; para otro destinatario, 409.
4. **Given** el mismo token en otro tenant, **When** se registra, **Then** se crea un registro independiente.
5. **Given** un `recipientId` inexistente o de otro tenant, **When** se registra, **Then** 404 sin revelar que existe en otro tenant.
6. **Given** un `subject` ya asociado a una identidad del tenant y sin `recipientId`, **When** se registra, **Then** se reutiliza esa identidad.

### User Story 2 - Listar dispositivos (Priority: P1)

**Acceptance Scenarios**:

1. **Given** un destinatario con dispositivos, **When** `GET /devices?recipientId=`, **Then** devuelve solo los de su tenant, con
   el token enmascarado (`****` + últimos 4) salvo para ADMINISTRADOR.
2. **Given** otro tenant con el mismo `recipientId`, **When** ese tenant lista, **Then** no ve nada del primero.

### User Story 3 - Dar de baja un dispositivo (Priority: P1)

**Acceptance Scenarios**:

1. **Given** un dispositivo del tenant, **When** `POST /device-removals` con `deviceId`, **Then** 204 y el token deja de existir en base de datos.
2. **Given** un `deviceId` de otro tenant, **When** se da de baja, **Then** 404 y el dispositivo ajeno sigue intacto.
3. **Given** una baja repetida, **When** se repite, **Then** 404 sin efecto colateral.

### User Story 4 - Eliminar tokens inválidos solos (Priority: P1)

**Acceptance Scenarios**:

1. **Given** una notificación PUSH cuyo proveedor responde 404 `UNREGISTERED` o 410, **When** el despacho termina en fallo
   permanente, **Then** el dispositivo con ese token en ese tenant se elimina y el intento queda como fallo permanente.
2. **Given** un 404 sin indicio de token inválido, **When** ocurre, **Then** no se elimina ningún dispositivo.
3. **Given** el mismo token en otro tenant, **When** se elimina en uno, **Then** el otro permanece.
4. **Given** un token no registrado (envío por dirección directa), **When** el proveedor responde 410, **Then** no hay error ni efecto adicional.
5. **Given** un fallo recuperable (429, 5xx), **When** ocurre, **Then** el dispositivo permanece.

### User Story 5 - Retención y borrado de datos personales (Priority: P2)

**Acceptance Scenarios**:

1. **Given** un dispositivo sin actividad durante el plazo de retención, **When** vence, **Then** el sistema lo elimina solo.
2. **Given** un envío aceptado a un dispositivo, **When** ocurre, **Then** `lastSeenAt` se actualiza y el plazo se reinicia.
3. **Given** una solicitud de borrado del titular, **When** un ADMINISTRADOR da de baja todos sus dispositivos, **Then** no queda
   token recuperable; la auditoría conserva solo la huella del token.

### Edge Cases

- Token en blanco, plataforma desconocida o token mayor de 4096 caracteres: 400.
- Registros concurrentes del mismo `(tenant, token)`: el índice único decide; el perdedor recibe el resultado idempotente o 409.
- Carrera entre baja manual y borrado automático: la segunda operación no hace fallar el despacho.

## Requirements

- **FR-001**: Existe una identidad de destinatario propia (`recipientId` opaco generado por el servicio, tenant, `subject`
  opcional), distinta de la dirección. `Recipient`, `RecipientId` y el envío por dirección no cambian.
- **FR-002**: `POST /devices` registra (plataforma, token; `recipientId` o `subject` opcionales); `GET /devices?recipientId=`
  lista; la baja es `POST /device-removals` con `deviceId` en el cuerpo (rutas literales).
- **FR-003**: Cada dispositivo guarda `tenantId` (sesión), `recipientId`, componente de origen (`subject` de la sesión),
  plataforma, `createdAt` y `lastSeenAt`.
- **FR-004**: Único `(tenantId, token)` garantizado por índice en base de datos.
- **FR-005**: El token se guarda cifrado y se busca por huella HMAC; nunca aparece en logs, métricas, eventos ni errores.
- **FR-006**: Las respuestas enmascaran el token salvo para ADMINISTRADOR.
- **FR-007**: Un fallo permanente de FCM con 404 `UNREGISTERED` o 410 elimina el dispositivo `(tenantId, token)` de la notificación.
- **FR-008**: Retención: eliminación tras 270 días sin actividad (configurable); baja y borrado automático son físicos.
- **FR-009**: Aislamiento por tenant en todo acceso; un id de otro tenant se trata como inexistente.
- **FR-010**: Base legal, retención y borrado documentados en la sección siguiente.
- **FR-011**: El modelo admite más tipos de identificador por destinatario y preferencias sin migrar dispositivos.

## Datos personales

- **Dato tratado**: token de dispositivo y `subject`; identifican indirectamente a una persona.
- **Base legal (supuesto a confirmar por el responsable de datos)**: autorización del titular al habilitar notificaciones en la
  aplicación, Ley 1581 de 2012; finalidad única: entregar las notificaciones que el componente solicita.
- **Retención**: mientras el dispositivo esté activo; máximo 270 días sin actividad; eliminación inmediata en baja o token inválido.
- **Borrado**: físico, sin copia recuperable; la auditoría guarda solo los primeros 8 caracteres de la huella.
- **Acceso**: token completo solo para ADMINISTRADOR; el resto lo ve enmascarado.

## Success Criteria

- **SC-001**: Registro, listado y baja responden en menos de 300 ms p95 con 10 dispositivos por destinatario (prueba con aserción de `Duration`).
- **SC-002**: Con dos tenants y el mismo token, ninguna operación de uno modifica ni revela datos del otro (prueba automatizada).
- **SC-003**: Tras 404 `UNREGISTERED` o 410, el dispositivo desaparece en menos de 5 s desde el fallo del intento (prueba con `Duration`).
- **SC-004**: Ningún log, evento ni respuesta no administrativa contiene el token completo (prueba que captura logs).
- **SC-005**: 20 registros concurrentes del mismo token dejan exactamente un documento.

## Out of Scope

Fan-out a todos los dispositivos de una persona (F6), preferencias por identidad (F1), validación activa de tokens (E10),
migración de `recipientId` históricos y edición de `api-notificaciones.yaml` (lo hace Contrato).
