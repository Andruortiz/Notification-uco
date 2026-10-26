# Implementation Plan: Modelo de autorización no lineal y su aprovisionamiento (HU2-123)

**Branch**: `feature/HU2-123-autorizacion-no-lineal` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Borrador (pendiente de aprobación)

**Versión del plan**: 1

## Summary

El rol mínimo en cadena se sustituye por una lista de roles permitidos por operación. El contrato es la fuente: de él salen la
política interna (laboratorio y pruebas), el catálogo `protected-resources.json` y, con el script de la
[spec 028](../028-aprovisionar-pdp/plan.md), los roles y perfiles del PDP. Con el PEP activo el 403 lo da el PEP; la política
interna aplica el mismo criterio sin PEP.

## Technical Context

Java 25, Spring Boot 4.1, WebFlux; PowerShell 7 para el script. Se implementa después de B1 (spec 029): usa su emisor de tokens
RSA de prueba y su `AuthenticatedPrincipal` con `clientId`.

## Constitution Check (2.0.1)

- I Hexagonal: `Role` y `AuthenticatedPrincipal` (core) pierden la cadena y ganan un conjunto, sin frameworks. Cumple.
- II Contrato primero: no hay rutas nuevas; cambia la extensión por operación. Exige enmienda del texto del Principio II
  (`x-minimum-role` pasa a `x-allowed-roles`), versión 2.1.0. Ver Excepciones.
- III Sin comentarios en código nuevo. Cumple.
- IV Pruebas: E2E explícito en T-E2E; unitarias de `Role`/política; `verify` completo al cierre. Cumple.
- V Commits de una línea. VII Los límites están en [spec.md](spec.md#límites-documentados-principio-vii) con dueño y fecha.
- RabbitMQ: no aplica.

## Decisiones

1. **Roles.** `Role` = `ADMINISTRADOR_PLATAFORMA`, `ADMINISTRADOR_APLICACION`, `EDITOR_PLANTILLAS`, `OPERADOR`, `AUDITOR`,
   `CLIENTE`. Se elimina `satisfies`; `Role.of` se conserva. El nombre del rol es el del PDP y el del claim.
2. **Principal.** `AuthenticatedPrincipal(subject, tenantId, Set<Role> roles, clientId)`. Se conserva un constructor con un solo
   `Role` para no tocar los 15 `new AuthenticatedPrincipal(` (3 en `main`, 12 en `test`; 10 archivos, recuento del 2026-10-25).
   `role()` se retira; quien lo usa pasa a `roles()`.
3. **Política.** `RouteAuthorizationPolicy.allowedRolesFor(method, path)` devuelve `Set<Role>`: el conjunto del mapa, o
   `{ADMINISTRADOR_PLATAFORMA}` si la ruta no está declarada, o todos los roles si el PEP está activo. El mapa sigue en código
   y `RouteAuthorizationPolicyContractTest` lo contrasta con el contrato (no se carga el YAML en runtime: mismo patrón actual).
4. **Filtro.** `AuthenticationWebFilter`: permite si `roles` intersecta `allowedRolesFor`; si no, el 403 existente. Sin `ErrorCode`
   nuevo.
5. **Claim de rol.** En `PlatformJwtTokenValidationAdapter` el claim puede ser texto o lista; se ignoran los desconocidos; si no
   queda ninguno, `UNKNOWN_ROLE` como hoy (B1 lo mapea a su código 401). El adaptador de sesión sigue con `{CLIENTE}`.
6. **Catálogo y script.** `ProtectedResourcesCatalog` emite `allowedRoles` en vez de `minimumRole`/`profiles` acumulativos.
   `scripts/provision-pdp.ps1`: valida `allowedRoles` (conocidos, no vacíos, sin duplicados); crea los seis roles; vincula cada
   rol a los recursos `available` que lo listan; crea `PERFIL_<ROL>` con ese único rol. Perfiles son del tenant de la sesión:
   se ejecuta una vez por tenant y la idempotencia hace que solo se creen los perfiles faltantes. Imprime el tenant de la sesión
   (`GET /api/v1/session`). Los `PERFIL_*` acumulativos y el rol `ADMINISTRADOR` previos se informan como divergencias.
7. **Asignación inicial** (propuesta, a aprobar). AP = `ADMINISTRADOR_PLATAFORMA`, AA = `ADMINISTRADOR_APLICACION`, ET =
   `EDITOR_PLANTILLAS`, OP = `OPERADOR`, AU = `AUDITOR`, CL = `CLIENTE`. AP está en todo lo que lista OP ("todo lo del operador").
   AU no ve contenido: no figura en envío, detalle ni stream.

   | Operaciones | Roles permitidos |
   | --- | --- |
   | `POST /notifications`, `/notifications/batches` | CL, AA, AP |
   | adjuntos (3) | CL, AA |
   | `POST /notifications` con plantilla de categoría SEGURIDAD (crítica) | AA, AP (rama interna: CL no; cubierta por la precondición bloqueante de roles por sesión, Seguridad, 2026-12-15) |
   | `GET /notifications/detail`, `/notifications/stream` | CL, OP, AA, AP |
   | `GET /notifications`, `/notifications/timeline`, `/provider-health` | OP, AA, AP |
   | `POST /notification-retries`, `/notification-resends`, `/recipients/undeliverable-lookups` | OP, AP |
   | `GET /channels`, `GET /providers` | los seis |
   | `POST /channels`, `/providers`, `/provider-connection-tests`, `/recipients/undeliverable-releases`, `/device-erasures` | AP |
   | `GET /configuration` | AP, AA |
   | `/devices` (POST, GET), `/device-removals` | CL |
   | `/recipients/preferences` (GET, PUT) | CL; AA solo para `subject` ajeno |
   | `POST/PUT /templates`, `/template-archivals`, `/template-variable-detections` | ET, AP |
   | `GET /templates`, `/templates/detail` | CL, OP, ET, AA, AP |
   | `POST /template-renders` | CL, ET, AA, AP (OP no) |
   | `POST /template-previews` | ET, AA, AP |
   | `PUT /template-mappings` | ET, AP |
   | `GET /template-mappings` | CL, OP, ET, AA, AP |
   | `POST /template-mapping-resolutions` | CL, ET, AA, AP |
   | `POST /template-provider-links`, `POST /template-approval-syncs` | ET, AP |
   | `GET /template-provider-links` | CL, OP, ET, AA, AP |
   | `POST /template-publications`, `POST /template-unpublications` | AA, AP |
   | `POST /provider-template-allowlist-entries`, `GET /provider-template-allowlist-entries`, `POST /provider-template-allowlist-removals` | AP, AA (D5, spec 045; entran al contrato con su historia, FR-011) |
   | `PUT` y `POST /template-blocks`, `POST /template-block-archivals` | ET, AP |
   | `GET /template-blocks`, `GET /template-blocks/detail` | CL, OP, ET, AA, AP |
   | `/inbox-items`, `/inbox-unread-counts`, `/inbox-reads`, `/inbox-read-alls`, `/inbox-removals`, `/inbox-stream` | CL |
   | `POST /inbox-erasures` | AA, AP |
   | `POST /recipient-directory`, `POST /recipient-directory-removals` | AA |
   | `GET /recipient-directory/lookups` | CL, AA, AP |
   | `POST /recipient-directory-erasures` | AA, AP |
   | `POST /user-notifications` | CL, AA |
   | `GET /audit-entries`, `GET /audit-entries/detail` | AU, AP únicamente (AA no) |

   Rutas `planned`: solo lista, sin registro en el PDP (spec, Edge Cases). `H6` (`/platform-status`) cancelada. Resumen por
   aplicación y consentimientos: FR-011.

   Desenmascarar datos: solo AP y AA; el resto los ve enmascarados (spec, Clarifications).

   Impacto en nivel 1: E5 y E10 deben reescribir sus ramas de desenmascarar y de borrado a los roles de esta tabla (`/inbox-erasures`, `/device-erasures`), no a `ADMINISTRADOR`. `/recipient-directory-erasures` es de F6, no de E5 ni E10.

## Cambios de contrato requeridos (los aplica el agente del contrato; aquí no se edita el YAML)

1. En todas las operaciones del contrato, reemplazar `x-minimum-role` por `x-allowed-roles: [..]` según la tabla.
2. Texto de cabecera del contrato (pasos 8 y descripción de `x-status`/`x-minimum-role`): describir `x-allowed-roles`.
3. Schemas de error: ninguna ruta ni código nuevo; la respuesta 403 de cada operación protegida ya apunta a `INSUFFICIENT_ROLE`.
4. `info.version` y el changelog (`ChangelogContractTest`): entrada por el cambio de extensión.
5. Constitución: Principio II y resumen, a 2.1.0, con el texto `x-allowed-roles` (enmienda aprobada por el usuario en el lote de nivel 2).

## Project Structure

- `core`: `Role`, `AuthenticatedPrincipal`; prueba `RoleTest`.
- `infrastructure/src/main`: `RouteAuthorizationPolicy`, `AuthenticationWebFilter`, `PlatformJwtTokenValidationAdapter`,
  `PdpSessionIdentityAdapter` (solo constructor).
- `infrastructure/src/test`: `ApiContract` (lee `x-allowed-roles`), `ProtectedResourcesCatalog` y su prueba,
  `RouteAuthorizationPolicyContractTest`, `FakePdpAdmin` con estado por tenant, `PdpProvisioningScriptTest`, nuevo
  `RoleAuthorizationE2ETest`, migración de usos de `Role.ADMINISTRADOR`.
- `scripts/provision-pdp.ps1`, `README.md`, `.specify/memory/constitution*.md`.

## Pruebas

- **E2E (`RoleAuthorizationE2ETest`, patrón `NotificationLiveUpdatesE2ETest`, PEP desactivado, tokens RSA de B1):** para cada
  rol único recorre las operaciones `available` y afirma permitido (sin 403) o 403 `INSUFFICIENT_ROLE` según el contrato (SC-001);
  token con dos roles = unión (SC-002); roles desconocidos = 401; ruta no declarada; dos tenants: el rol de un token del tenant A
  no da acceso a datos del B (el tenant sigue saliendo del token).
- **Script (E2E del flujo contra el doble):** estado resultante = contrato; segunda ejecución con cero escrituras (SC-003); dos
  tenants con perfiles separados y sin cruce; esquema acumulativo previo informado sin borrar; lista inválida falla sin llamadas;
  la credencial no aparece en la salida.
- **Contrato:** `x-allowed-roles` presente, válido, sin `x-minimum-role`; política = contrato; catálogo = contrato.
- Arquitectura: `HexagonalArchitectureTest` y `ModularityTests` siguen en verde.

## Riesgos

- Rompe a todo quien emita tokens con `ADMINISTRADOR`: Seguridad debe usar los nombres nuevos en el realm (pregunta abierta).
- B1 y otras historias del nivel 2 tocan `AuthenticatedPrincipal` y el contrato: integrar B2 sobre la rama que ya contenga B1.
- Sin PDP real no se prueba que el PDP una los permisos de varios perfiles ni el 403 del PEP.
- Precondición bloqueante (Seguridad, 2026-12-15): con sesión el principal es `CLIENTE`. Ver [spec.md](spec.md#precondición-bloqueante).
  Las ramas internas por rol (desenmascarar, `subject` ajeno, borrado por titular, liberar marca) se documentan como no disponibles
  en producción hasta cumplirla; los controles de ruta son del PEP.

## Excepciones (Principio VII)

Ver [spec.md](spec.md#límites-documentados-principio-vii). La enmienda de la constitución a 2.1.0 y los seis roles quedan
aprobados por el usuario en el lote de nivel 2.
