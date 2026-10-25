# Feature Specification: Modelo de autorización no lineal y su aprovisionamiento (HU2-123)

**Feature Branch**: `feature/HU2-123-autorizacion-no-lineal`

**Created**: 2026-10-25

**Status**: Borrador (pendiente de aprobación de spec y plan)

**Input**: "Como AP quiero definir los roles de la plataforma (administrador de plataforma, administrador de aplicación,
editor de plantillas, operador, auditor) como roles y perfiles del PDP, para dar el mínimo privilegio." Criterios: el contrato
declara por operación la lista de roles permitidos, no un rol mínimo en cadena; el generador de recursos y perfiles
([spec 028](../028-aprovisionar-pdp/spec.md)) usa esa lista y crea los perfiles en cada tenant; un rol sin permiso recibe 403
del PEP. Fuera de alcance: ocultar acciones en la consola.

Trazabilidad: depende de B1 (spec 029, principal solo desde el token). Reemplaza `Role.satisfies` y `x-minimum-role` (spec 024).
Principios: I, II, III, IV, VII.

## Contexto y evidencia

- `Role` es un enum en cadena (`ADMINISTRADOR`, `OPERADOR`, `CLIENTE`) con `satisfies` por ordinal; `RouteAuthorizationPolicy`
  devuelve un rol mínimo por ruta y `AuthenticationWebFilter` compara `principal.role().satisfies(minimo)`.
- El contrato declara `x-minimum-role` (Principio II vigente); `ProtectedResourcesCatalog` deriva perfiles acumulativos
  (`PERFIL_OPERADOR` = CLIENTE + OPERADOR).
- Con el PEP activo (`security.pep.enforcement.enabled`) la política interna devuelve CLIENTE y el PEP decide. Con la sesión
  del PDP el principal se arma con `Role.CLIENTE` fijo (`PdpSessionIdentityAdapter`): la sesión no trae roles.
- El PDP no hereda roles; los roles son de aplicación y los perfiles de tenant ([spec 028](../028-aprovisionar-pdp/research.md)).
- `ErrorCode.INSUFFICIENT_ROLE(2002)` ya existe y se reutiliza.

## Clarifications

### Session 2026-10-25

- Q: ¿Cuántos roles? A: 5 administrativos más CLIENTE, como perfiles del PDP en cada tenant (decisión del producto).
- Q: ¿Perfiles múltiples por usuario? A: Sí. El permiso efectivo es la unión de sus roles.
- Q: ¿Quién recibe el 403? A: Con PEP activo, el PEP; sin PEP (laboratorio y pruebas), la política interna con el mismo criterio.
- Q: ¿Cuándo se retira un rol del PDP? A: Solo cuando ya no tiene recursos ni asignaciones; el script no borra (spec 028, FR-007).
- Q: ¿Se oculta algo en la consola? A: No; depende de que el PDP entregue los permisos del usuario (límite abajo).

Propuestas que el usuario aprueba con este spec (no estaban decididas): nombres de rol `ADMINISTRADOR_PLATAFORMA`,
`ADMINISTRADOR_APLICACION`, `EDITOR_PLANTILLAS`, `OPERADOR`, `AUDITOR`, `CLIENTE` (el `ADMINISTRADOR` actual desaparece); un
perfil del PDP por rol; reemplazo de `x-minimum-role` por `x-allowed-roles` con enmienda de la constitución.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El contrato lista los roles por operación (Priority: P1)

El AP revisa el contrato y ve, en cada operación, exactamente qué roles pueden invocarla.

**Independent Test**: prueba de contrato que recorre todas las operaciones.

**Acceptance Scenarios**:

1. **Given** el contrato, **When** se lee cualquier operación, **Then** tiene `x-allowed-roles` no vacío, sin duplicados, con
   roles conocidos, y no existe `x-minimum-role`.
2. **Given** una operación nueva sin `x-allowed-roles`, **When** corre la prueba de contrato, **Then** falla.
3. **Given** la política interna, **When** se consulta cada operación `available`, **Then** devuelve el mismo conjunto del contrato.

### User Story 2 - Un rol sin permiso recibe 403 (Priority: P1)

**Independent Test**: E2E del servicio completo con tokens de cada rol (ver plan).

**Acceptance Scenarios**:

1. **Given** un token con `AUDITOR`, **When** envía `POST /notifications`, **Then** 403 `INSUFFICIENT_ROLE`.
2. **Given** un token con `CLIENTE`, **When** llama `GET /configuration`, **Then** 403.
3. **Given** un token con `EDITOR_PLANTILLAS` y `OPERADOR`, **When** invoca una operación de cualquiera de los dos, **Then** se
   permite (unión de roles).
4. **Given** un token cuyos roles son todos desconocidos, **When** llama cualquier ruta protegida, **Then** 401 como hoy.
5. **Given** una ruta no declarada, **When** la llama cualquier rol salvo `ADMINISTRADOR_PLATAFORMA`, **Then** 403.

### User Story 3 - El script crea roles y perfiles por tenant desde la lista (Priority: P1)

El AP ejecuta el script de la spec 028 con la sesión de administración de un tenant y los perfiles quedan creados en ese tenant.

**Independent Test**: prueba del script contra el doble del PDP con dos tenants.

**Acceptance Scenarios**:

1. **Given** un PDP vacío, **When** corre el script, **Then** quedan los seis roles, cada uno vinculado a los recursos
   `available` cuya lista lo incluye, y un perfil por rol con ese único rol.
2. **Given** el segundo tenant de la misma aplicación, **When** corre el script con su sesión, **Then** se crean solo sus
   perfiles (roles y recursos ya existen) y nada del primero cambia.
3. **Given** un PDP con el esquema acumulativo anterior, **When** corre el script, **Then** informa los perfiles y roles
   divergentes sin borrarlos.
4. **Given** una lista con un rol desconocido o vacío, **When** corre, **Then** falla antes de cualquier llamada.

### Edge Cases

- Token con roles repetidos o mezcla de conocidos y desconocidos: se ignoran los desconocidos y se deduplica.
- Claim de rol como texto o como lista.
- Operaciones `planned`: tienen lista, pero no se registran en el PDP.
- Dos tenants: los roles de un token nunca habilitan nada en otro tenant (el tenant sigue saliendo de la identidad).

## Requirements *(mandatory)*

- **FR-001**: `Role` MUST tener seis valores (`ADMINISTRADOR_PLATAFORMA`, `ADMINISTRADOR_APLICACION`, `EDITOR_PLANTILLAS`,
  `OPERADOR`, `AUDITOR`, `CLIENTE`) y MUST NOT imponer orden entre ellos.
- **FR-002**: El principal MUST llevar un conjunto de roles no vacío; la autorización permite si el conjunto intersecta la lista
  de la operación.
- **FR-003**: Cada operación del contrato MUST declarar `x-allowed-roles`; `x-minimum-role` MUST desaparecer.
- **FR-004**: `RouteAuthorizationPolicy` MUST devolver el conjunto permitido por operación; ruta no declarada: solo
  `ADMINISTRADOR_PLATAFORMA`. Con el PEP activo la política interna no restringe (delegación vigente).
- **FR-005**: Un rol sin permiso MUST responder 403 `INSUFFICIENT_ROLE`.
- **FR-006**: El claim de rol MUST aceptar texto o lista; los valores desconocidos se ignoran; sin ninguno conocido, 401 igual
  que hoy.
- **FR-007**: `protected-resources.json` MUST exponer `allowedRoles` por operación; el script de la spec 028 MUST vincular cada
  rol a los recursos `available` que lo listan y crear un perfil por rol.
- **FR-008**: El script MUST ser idempotente por tenant, no borrar ni revocar, informar divergencias y rechazar antes de llamar
  al PDP listas con roles desconocidos o vacías.
- **FR-009**: Una prueba automatizada MUST fallar si la política interna, el contrato y el estado que el script deja en el PDP
  divergen.
- **FR-010**: La constitución MUST enmendarse (Principio II: `x-allowed-roles`) en la misma rama, con aprobación del usuario.
- **FR-011**: Las operaciones de historias futuras (auditoría, resumen por aplicación, consentimientos) MUST traer su lista al
  entrar al contrato; esta historia asigna las existentes.

## Success Criteria

- **SC-001**: Para las 6 combinaciones de rol único, el 100 % de las operaciones `available` responde según el contrato
  (permitido sin 403, no permitido 403 `INSUFFICIENT_ROLE`) en una prueba E2E.
- **SC-002**: Un usuario con dos perfiles obtiene exactamente la unión de permisos (prueba automatizada).
- **SC-003**: Dos ejecuciones consecutivas del script por tenant dejan el PDP idéntico; la segunda hace cero escrituras.
- **SC-004**: `verify` completo en verde con cobertura mínima.

## Límites documentados (Principio VII)

| Límite | Dueño | Fecha |
| --- | --- | --- |
| La sesión del PDP no trae roles; con sesión el principal sigue como `CLIENTE` y no se pueden aplicar reglas por rol dentro del servicio (enmascarado, causa técnica) hasta que Seguridad amplíe la sesión o abra `/api/v1/authorize` | Seguridad | 2026-12-15 |
| Política OPA que concede por rol y perfil: sin ella el PDP deniega todo; no se verifica el 403 del PEP real | Seguridad | 2026-12-15 |
| Creación automática de perfiles al dar de alta un tenant: hoy se ejecuta el script con la sesión de cada tenant | Seguridad | 2026-12-15 |
| Ocultar acciones en la consola (C1b, en «Veremos») | Front C1b | 2026-12-15 |
| Migración manual de perfiles y roles del esquema acumulativo en PDPs ya aprovisionados | AP | 2026-11-12 |

## Assumptions

- El PDP une los roles de todos los perfiles asignados al usuario (a verificar con Seguridad).
- Los nombres de rol del token coinciden con los del PDP.
- B1 está implementada (principal desde el token, claves RSA de prueba).
