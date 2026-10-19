# Feature Specification: Aprovisionar recursos, roles y perfiles en el PDP como código (HU2-099)

**Feature Branch**: `feature/HU2-099-aprovisionar-pdp`

**Created**: 2026-10-19

**Status**: Draft

## Contexto

Los recursos, roles y perfiles de la aplicación `notification-service` se crean a mano en la consola de seguridad contra el
PDP. Cada build genera `infrastructure/target/contract/protected-resources.json` (método, ruta, rol mínimo, perfiles
acumulativos y estado), que es la fuente canónica. El PDP no hereda roles: los perfiles son acumulativos
(`PERFIL_CLIENTE` = CLIENTE; `PERFIL_OPERADOR` = CLIENTE + OPERADOR; `PERFIL_ADMINISTRADOR` = los tres). El PDP busca recursos
por igualdad exacta de método y ruta y solo acepta letras, dígitos, `. _ ~ { } -` y `/` (`{}` produce 500; `:` y `*`, 400).
Solo se registran las operaciones `available`.

## User Scenarios & Testing

### User Story 1 - Aprovisionar desde la lista canónica (Priority: P1)

Un administrador ejecuta un script que deja en el PDP los recursos `available`, los tres roles, los tres perfiles y sus
vínculos, sin tocar nada a mano.

**Independent Test**: contra un doble HTTP del PDP, el estado resultante coincide con las operaciones `available` del contrato.

**Acceptance Scenarios**:

1. **Given** un PDP vacío para la aplicación, **When** se ejecuta el script, **Then** quedan registrados exactamente los recursos
   `available`, cada rol vinculado a los recursos cuyo rol mínimo es él, y cada perfil con los roles acumulados.
2. **Given** el script ya ejecutado, **When** se ejecuta otra vez, **Then** no hace ninguna escritura ni duplica nada.
3. **Given** un estado parcial, **When** se ejecuta, **Then** completa lo que falta sin recrear lo existente.
4. **Given** operaciones `planned`, **When** se ejecuta, **Then** no se registran.

### User Story 2 - Ver el plan sin efectos (Priority: P1)

**Acceptance Scenarios**:

1. **Given** `--dry-run` sin credencial, **When** se ejecuta, **Then** imprime el plan completo y no contacta al PDP.
2. **Given** `--dry-run` con credencial, **When** se ejecuta, **Then** solo hace lecturas e informa qué crearía.

### User Story 3 - Divergencias y seguridad (Priority: P1)

**Acceptance Scenarios**:

1. **Given** una lista canónica con perfiles que contradicen el rol mínimo, o con una ruta no registrable (`{}`, `:`, `*`),
   **When** se ejecuta, **Then** falla antes de cualquier llamada.
2. **Given** cualquier ejecución, **When** termina, **Then** la credencial no aparece en la salida ni en archivos.
3. **Given** recursos del PDP que no están en la lista canónica, **When** se ejecuta, **Then** se informan como huérfanos y no se borran.

## Requirements

- **FR-001**: El script MUST leer la lista canónica y registrar solo las operaciones `available`.
- **FR-002**: MUST crear en este orden: recursos, roles, vínculos rol-recurso, perfiles, vínculos perfil-rol.
- **FR-003**: MUST ser idempotente: consulta antes de escribir y no duplica.
- **FR-004**: MUST ofrecer `--dry-run` (y `-DryRun`) que no escribe nunca.
- **FR-005**: MUST recibir la credencial de administración solo por la variable `PDP_ADMIN_SESSION` y no imprimirla ni guardarla.
- **FR-006**: MUST rechazar antes de llamar al PDP rutas no registrables, métodos fuera del conjunto conocido, roles
  desconocidos, duplicados y perfiles incoherentes con el rol mínimo.
- **FR-007**: MUST NOT borrar ni revocar nada; informa huérfanos y desvíos.
- **FR-008**: La asignación de perfiles a usuarios MUST seguir siendo manual y el script lo indica.
- **FR-009**: Una prueba automatizada MUST fallar si los recursos que el script deja en el PDP divergen de las operaciones
  `available` del contrato.

## Success Criteria

- **SC-001**: Dos ejecuciones consecutivas dejan el PDP idéntico y la segunda hace cero escrituras (prueba automatizada).
- **SC-002**: Ninguna salida del script contiene el valor de `PDP_ADMIN_SESSION` (prueba automatizada).
- **SC-003**: `verify` completo en verde con cobertura mínima.

## Assumptions

- La aplicación ya está registrada en el PDP (su alta entrega una credencial una sola vez y queda fuera del script).
- La credencial es el valor de la cookie `SECURITY_BASELINE_SESSION` de un administrador de la aplicación (ver research.md).
- No se ejecuta el script contra el PDP real en esta historia.
