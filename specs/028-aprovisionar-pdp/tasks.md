# Tasks: Aprovisionar recursos, roles y perfiles en el PDP como código (HU2-099)

**Prerequisites**: plan.md en estado `Aprobado`.

## Phase 1: Investigación

- [x] T001 Descompilar el PDP 0.1.6 y la consola; registrar endpoints verificados y preguntas abiertas en `research.md`.

## Phase 2: Pruebas y doble

- [x] T002 Extraer `ProtectedResourcesCatalog` de `ProtectedResourcesCatalogTest`.
- [x] T003 `FakePdpAdmin`: doble HTTP con sesión, CSRF, paginación, validación de rutas y duplicados.
- [x] T004 `PdpProvisioningScriptTest`: recursos == `available` del contrato, roles, perfiles acumulativos, acceso efectivo,
  idempotencia (cero escrituras), estado parcial, otras aplicaciones, huérfanos, dry-run con y sin credencial, secreto no
  impreso, sesión inválida, aplicación inexistente, lista incoherente, ruta no registrable, lista ausente. Esta prueba es el E2E.

## Phase 3: Implementación

- [x] T005 `scripts/provision-pdp.ps1`.

## Phase 4: Documentación y verificación

- [x] T006 README y `quickstart.md` con uso, orden y asignación manual de perfiles.
- [ ] T007 `spotless:apply` hecho. Pruebas (`PdpProvisioningScriptTest`, arquitectura, `verify` completo): pendiente de
  verificación en CI por instrucción del usuario; no se ejecutaron localmente y el script no se probó ni siquiera con un
  intérprete (ni siquiera sintaxis) en esta historia.
