# Feature Specification: Registro de dispositivos por destinatario (HU2-120)

**Feature Branch**: `feature/HU2-120-registro-dispositivos`

**Created**: 2026-10-24

**Status**: Draft

## Contexto

Hoy `recipientId` es la dirección misma (teléfono, correo o token FCM): quien quiere notificar a una persona debe conocer su
token, y el servicio no sabe a quién pertenece. Esta historia introduce una identidad de destinatario propia, separada de la
dirección, y una lista de dispositivos registrados por identidad. El adaptador FCM (spec 010) ya clasifica 404 y 410 como fallo
permanente. Quedan fuera las historias que dependen de esta (preferencias por identidad F1, directorio F6, validación E10); el
modelo solo queda extensible para ellas.

## Clarifications

### Session 2026-10-24 (decisiones aprobadas por el usuario)

- **Q1 - Origen del tenant**: siempre la sesión validada por el PDP (`AuthenticatedPrincipal.tenantId`); ningún cuerpo ni
  parámetro lo fija. Afecta FR-003, FR-009.
- **Q2 - Componente de origen**: es el `subject` de la sesión que registra; el cuerpo no lo declara. Afecta FR-003.
- **Q3 - Unicidad y propiedad**: único `(tenantId, token)`; el mismo token en dos tenants son registros independientes. El
  dispositivo se ata al `subject` de la sesión: registrar un token ya existente en el tenant lo deja asociado a quien lo
  registra (el último registro gana: un dispositivo que cambia de usuario) y la respuesta es la misma, sin 409, para que no se
  pueda sondear qué tokens existen. Afecta FR-001, FR-004, FR-013.
- **Q4 - Borrado automático**: la regla es `errorCode == UNREGISTERED` en la respuesta de FCM, o 410. Un 404 por proyecto o URL
  mal configurados, o un 400 `INVALID_ARGUMENT` por token mal formado, nunca limpian. Afecta FR-007.
- **Q5 - Envío por persona**: el fan-out NO entra; el envío por dirección no cambia. Lo consumirá F6. Afecta Out of Scope.
- **Q6 - Roles** (los tres del código): CLIENTE y OPERADOR operan solo sobre su propia identidad (la del `subject` de la sesión);
  ADMINISTRADOR opera sobre cualquier `subject` del tenant, ve el token completo y ejecuta el borrado por titular.
- **Q7 - Token en la notificación**: el token PUSH que hoy se guarda en claro en `notifications.recipientAddress` se cifra al
  persistir y se descifra al leer; despacho y reintento reciben la dirección real. Afecta FR-012.

## User Scenarios & Testing

### User Story 1 - Registrar un dispositivo (Priority: P1)

**Independent Test**: `POST /devices` con dos tenants y el mismo token, y con dos sujetos del mismo tenant.

**Acceptance Scenarios**:

1. **Given** una sesión sin identidad previa, **When** registra un token y su plataforma, **Then** 201 con `deviceId`,
   `recipientId` generado por el servicio y asociado al `subject` de la sesión, tenant, componente de origen y token enmascarado.
2. **Given** una identidad existente, **When** registra otro token, **Then** tiene dos dispositivos.
3. **Given** un token ya registrado, propio o de otra identidad del tenant, **When** se registra, **Then** 200 con el dispositivo
   asociado a la sesión, sin duplicado y sin diferencia observable entre ambos casos.
4. **Given** el mismo token en otro tenant, **When** se registra, **Then** se crea un registro independiente.
5. **Given** una sesión CLIENTE u OPERADOR que envía un `subject` distinto del suyo, **When** registra, **Then** 403.
6. **Given** una sesión ADMINISTRADOR con `subject` explícito, **When** registra, **Then** el dispositivo queda en esa identidad.

### User Story 2 - Listar dispositivos (Priority: P1)

**Acceptance Scenarios**:

1. **Given** una identidad con dispositivos, **When** `GET /devices`, **Then** devuelve solo los de la sesión, con el token
   enmascarado (`****` + últimos 4); ADMINISTRADOR puede indicar `subject` y ve el token completo.
2. **Given** otro tenant con el mismo `subject`, **When** lista, **Then** no ve nada del primero.

### User Story 3 - Dar de baja un dispositivo (Priority: P1)

**Acceptance Scenarios**:

1. **Given** un dispositivo propio, **When** `POST /device-removals` con `deviceId`, **Then** 204 y el token deja de existir en base de datos.
2. **Given** un `deviceId` de otro tenant, de otra identidad del mismo tenant o inexistente, **When** CLIENTE u OPERADOR lo da
   de baja, **Then** la misma respuesta 404 en los tres casos y el dispositivo ajeno sigue intacto.
3. **Given** una baja repetida, **When** se repite, **Then** 404 sin efecto colateral.

### User Story 4 - Eliminar tokens inválidos solos (Priority: P1)

**Acceptance Scenarios**:

1. **Given** una notificación PUSH cuyo proveedor responde 410 o 404 con `errorCode` `UNREGISTERED`, **When** el despacho
   termina en fallo permanente, **Then** el dispositivo con ese token en ese tenant se elimina y el intento queda como fallo permanente.
2. **Given** un 404 con otro `errorCode` o un 400 `INVALID_ARGUMENT`, **When** ocurre, **Then** no se elimina ningún dispositivo.
3. **Given** el mismo token en otro tenant, **When** se elimina en uno, **Then** el otro permanece.
4. **Given** un token no registrado (envío por dirección directa), **When** el proveedor responde 410, **Then** no hay error ni efecto adicional.
5. **Given** un fallo recuperable (429, 5xx), **When** ocurre, **Then** el dispositivo permanece.

### User Story 5 - Retención y borrado de datos personales (Priority: P2)

**Acceptance Scenarios**:

1. **Given** un dispositivo sin actividad durante el plazo de retención, **When** vence, **Then** el sistema lo elimina solo.
2. **Given** un envío aceptado a un dispositivo, **When** ocurre y `lastSeenAt` tiene más de 24 h, **Then** se actualiza y el
   plazo se reinicia; con menos de 24 h no hay escritura.
3. **Given** una solicitud de borrado del titular, **When** un ADMINISTRADOR invoca `POST /device-erasures` con el `subject`,
   **Then** 204, se eliminan su identidad y todos sus dispositivos, y no queda token recuperable; la auditoría conserva solo la huella.
4. **Given** el mismo caso, **When** lo invoca CLIENTE u OPERADOR, **Then** 403.

### Edge Cases

- Token en blanco, plataforma desconocida o token mayor de 4096 caracteres: 400.
- Registros concurrentes del mismo `(tenant, token)`: el índice único decide; ambos reciben la misma respuesta.
- Carrera entre baja manual y borrado automático: la segunda operación no hace fallar el despacho.
- Notificaciones PUSH anteriores a la historia con el token en claro: se leen igual, se cifran en el siguiente guardado y un
  barrido único las cifra todas (plan, Decisión 12).
- Reintento (manual o automático) de una notificación PUSH: usa el token real descifrado aunque el `Device` ya no exista.

## Requirements

- **FR-001**: Existe una identidad de destinatario propia (`recipientId` opaco generado por el servicio, tenant, `subject` de la
  sesión que la crea; opcional en el modelo para futuras identidades sin sesión), distinta de la dirección. `Recipient`,
  `RecipientId` y el envío por dirección no cambian.
- **FR-002**: `POST /devices` registra (plataforma, token; `subject` solo ADMINISTRADOR); `GET /devices` lista;
  `POST /device-removals` da de baja con `deviceId` en el cuerpo; `POST /device-erasures` borra la identidad de un `subject`
  (solo ADMINISTRADOR). Rutas literales.
- **FR-003**: Cada dispositivo guarda `tenantId` (sesión), identidad, componente de origen (`subject` de la sesión), plataforma,
  `createdAt` y `lastSeenAt`.
- **FR-004**: Único `(tenantId, token)` garantizado por índice en base de datos.
- **FR-005**: El token se guarda cifrado y se busca por huella HMAC; nunca aparece en logs, métricas, eventos ni errores.
- **FR-006**: Las respuestas enmascaran el token salvo para ADMINISTRADOR.
- **FR-007**: Un fallo de FCM con `errorCode == UNREGISTERED` o 410 elimina el dispositivo `(tenantId, token)` de la notificación;
  ningún otro código lo hace.
- **FR-008**: Retención: eliminación tras 270 días sin actividad; baja y borrado automático son físicos. `lastSeenAt` se refresca
  como máximo una vez cada 24 h por dispositivo.
- **FR-009**: Aislamiento por tenant en todo acceso.
- **FR-010**: Base legal, retención y borrado documentados en la sección siguiente.
- **FR-011**: El modelo admite más tipos de identificador por destinatario y preferencias sin migrar dispositivos (prueba que
  persiste una identidad sin `subject` y con atributos adicionales desconocidos).
- **FR-012**: Para PUSH, `notifications.recipientAddress` y `recipientId` se persisten cifrados; `recipientId` se enmascara en
  `GET /notifications` salvo para ADMINISTRADOR y el filtro `recipientId` sigue funcionando (por huella); despacho y reintentos
  usan la dirección real. El cifrado del histórico corre en segundo plano, por lotes, sin bloquear el arranque.
- **FR-013**: CLIENTE y OPERADOR solo operan sobre la identidad de su propio `subject`; las respuestas no distinguen entre
  recurso ajeno e inexistente.

## Datos personales

- **Dato tratado**: token de dispositivo y `subject`; identifican indirectamente a una persona.
- **Base legal (supuesto a confirmar por el responsable de datos)**: autorización del titular al habilitar notificaciones en la
  aplicación, Ley 1581 de 2012; finalidad única: entregar las notificaciones que el componente solicita.
- **Retención**: mientras el dispositivo esté activo; máximo 270 días sin actividad; eliminación inmediata en baja o token inválido.
  Las notificaciones conservan el token cifrado según la retención ya vigente de notificaciones.
- **Borrado**: físico, sin copia recuperable, por baja, token inválido, retención o `POST /device-erasures`; la auditoría guarda
  solo los primeros 8 caracteres de la huella.
- **Acceso**: token completo solo para ADMINISTRADOR; el resto lo ve enmascarado.

## Success Criteria

- **SC-001**: Con 50 ejecuciones secuenciales de registro, listado y baja con 10 dispositivos por identidad, el p95 calculado en
  la prueba es menor de 300 ms.
- **SC-002**: Con dos tenants y el mismo token, y con dos sujetos del mismo tenant, ninguna operación de uno modifica ni revela
  datos del otro (prueba automatizada).
- **SC-003**: Tras `UNREGISTERED` o 410, el dispositivo desaparece en menos de 5 s desde el fallo del intento (aserción de `Duration`).
- **SC-004**: Ningún log, evento, documento de notificación ni respuesta no administrativa contiene el token completo; la prueba
  incluye un control positivo (la captura sí registra el envío y el administrador sí ve el token).
- **SC-005**: 20 registros concurrentes del mismo token dejan exactamente un documento.

## Out of Scope

Fan-out a todos los dispositivos de una persona (F6), preferencias por identidad (F1), validación activa de tokens (E10),
migración de `recipientId` históricos y edición de `api-notificaciones.yaml` (lo hace Contrato).
