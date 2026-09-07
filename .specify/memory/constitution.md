<!--
Sync Impact Report
Version change: 1.0.0 → 1.1.0
Rationale: MINOR — adds a new principle and technical restrictions, no existing principle removed
or redefined incompatibly.
Modified principles: Governance — removed the claim that an external tool is the formal source of
truth for architecture decisions/backlog; the constitution no longer assigns them a required
location, per explicit user decision (2026-09-07) that such personal working notes should not be
cited as authoritative from any versioned document.
Added sections: Core Principle VII (Sin atajos); two new paragraphs under Restricciones técnicas
(replica concurrency — optimistic locking/atomic claims; caching strategy — local+invalidation vs.
shared store, tied to the rate-limit gap already tracked in the backlog); explicit E2E test gate added
to Principle IV and cross-referenced in Flujo de desarrollo's `tasks.md` requirement (finalized via
/speckit-constitution per explicit user request — "todo el flujo debe ser E2E").
Removed sections: none (Governance paragraph reworded, not removed)
Deferred / TODO: Redis (or equivalent) for the per-provider rate limiter is a restriction stated here
but not yet added to docker-compose/pom.xml — deferred until that Fase 5 story is actually built.
-->

# Notification-uco Constitution

## Core Principles

### I. Arquitectura hexagonal (NON-NEGOTIABLE)
`core` no depende de Spring ni de ningún framework — solo Java puro y las abstracciones propias
(puertos de entrada/salida). Toda comunicación con el exterior pasa por un puerto; los adaptadores
(`infrastructure`) implementan esos puertos, nunca al revés. Se verifica automáticamente en cada
build con `HexagonalArchitectureTest` — un principio no verificado no es un principio, es una
intención. (ADR-0004.)

### II. Contract-first, API orientada a acciones
Cualquier endpoint nuevo se define primero en `api-notificaciones.yaml` — el controller se escribe
para cumplir el contrato, no al revés. Las operaciones que representan una acción de negocio
explícita (no CRUD natural) usan el patrón `recurso:accion` en el path (ej. `/notifications:retry`);
las que sí son CRUD natural usan REST estándar (ej. `POST /notifications`, `GET /notifications/{id}`).

### III. Cero comentarios explicativos
El código nuevo no lleva comentarios `//` que expliquen huecos, decisiones o trade-offs — el nombre
de las clases/métodos y las pruebas son la documentación. El razonamiento detrás de una decisión vive
en el spec/plan de la historia (`specs/<historia>/`) o en el cuerpo del PR, nunca en el código.

### IV. Calidad verificada, no declarada
Ninguna historia se mergea sin que CI esté en verde: compilación, cobertura ≥ 80 % líneas / ≥ 70 %
ramas bloqueante (ADR-0014), análisis estático (SpotBugs + FindSecBugs + Spotless) bloqueante
(ADR-0012), y al menos una prueba end-to-end que ejercite el flujo completo de la historia contra el
proveedor simulado (`SimulatedNotificationProvider`) o el adaptador real correspondiente. Cobertura
unitaria de la lógica interna no sustituye una prueba E2E cuando la historia toca un flujo observable
de punta a punta — son puertas distintas, ambas bloqueantes. "Funciona en mi máquina" no es un criterio
de aceptación.

### V. Trazabilidad en git, sin atribución de IA en el historial
Commits de una sola línea (`tipo(ámbito): descripción`), rama por historia siguiendo GitFlow
(`feature/HU2-XXX-descripcion`, ADR-0015). El razonamiento completo vive en el cuerpo del PR o en
`specs/<historia>/`, nunca en el commit. El usuario del repositorio siempre revisa y mergea — un
asistente de IA nunca hace merge por su cuenta. Los commits y PRs de este repositorio **no** incluyen
firma ni atribución de co-autoría de ningún asistente de IA (decisión explícita, 2026-09-07,
reemplaza cualquier convención anterior).

### VI. Desarrollo asistido por IA, gobernado por spec-kit
Toda historia nueva desarrollada con asistencia de IA sigue el flujo `/speckit-specify` →
`/speckit-plan` → `/speckit-tasks` → `/speckit-implement`, con los artefactos versionados en
`specs/<historia>/`. El usuario aprueba explícitamente la constitución, el spec y el plan antes de
que se implemente una sola línea — esa aprobación es la evidencia de revisión humana, no una
formalidad. Esto rige el *proceso de desarrollo*; no reabre ni contradice ADR-0016 (que rechazó
integrar IA como *funcionalidad del componente* — ningún `CLAUDE.md` de producto, ninguna API de
Claude expuesta como feature del servicio). Este principio aplica a las historias desarrolladas desde
el 2026-09-07 en adelante; el trabajo anterior no se re-documenta retroactivamente — ver la nota
retrospectiva en el README.

### VII. Sin atajos
Ninguna solución temporal se acepta como definitiva. Si una puerta de calidad, una migración de datos
o una regla de negocio no tiene todavía una implementación correcta disponible, se documenta como
pendiente explícito (excepción con dueño y fecha) — nunca se resuelve con un parche que oculte el
problema real solo para que la build pase. La cobertura relajada a 30 % que nunca se revirtió, en el
primer intento de implementación de este proyecto, es exactamente el error que este principio existe
para no repetir.

## Restricciones técnicas

Java 21 LTS (ADR-0001), Spring Boot 3 sobre WebFlux/Reactor — reactivo, no bloqueante (ADR-0002,
ADR-0003). MongoDB y RabbitMQ gestionados fuera de local en todo ambiente distinto a desarrollo
(ADR-0006, ADR-0007). Extensibilidad por catálogo declarativo + adaptadores: agregar un canal o
proveedor no debe requerir modificar el núcleo (ADR-0009 — la decisión que define el proyecto).
Cumplimiento de 15 factores como checklist transversal verificable (ADR-0011). Ninguna credencial
reside en código ni en el repositorio (RNF-09); toda operación de la API exige autenticación y
aislamiento multi-tenant una vez se integre el Componente de Seguridad (RNF-13, RNF-14) — mientras
`DEP-01` siga bloqueado, el placeholder `X-Tenant-Id` es aceptable en desarrollo, nunca en producción.

Con múltiples réplicas del contenedor, toda mutación de un agregado ya persistido usa bloqueo
optimista (campo `version`, incrementado en cada escritura condicionada a su valor anterior) — nunca
bloqueo pesimista, porque el flujo de despacho mantiene una llamada externa lenta al proveedor entre
leer y guardar. La idempotencia de aceptación se garantiza con una restricción única
`(tenantId, externalId)` a nivel de base de datos, no solo con una verificación previa en la
aplicación. Cualquier operación de "tomar" un recurso compartido entre réplicas (ej. el scheduler de
reintentos) se implementa como una actualización condicionada atómica
(`findAndModify`/`UPDATE ... WHERE ... RETURNING`), nunca como leer-luego-escribir. Caché de lecturas
semi-estáticas (catálogo de canales, claves de validación de token) es local por réplica con
invalidación por evento + TTL de respaldo — nunca un almacén compartido, porque el dato es pequeño y
tolera segundos de vencimiento. Estado que debe ser estrictamente consistente entre réplicas (ej. un
contador de límite de tasa por proveedor) sí requiere un almacén compartido real — una caché local por
réplica permitiría que cada una agote el límite completo por su cuenta.

## Flujo de desarrollo

Rama por historia sobre `develop` (excepción documentada solo para el bootstrap inicial, ADR-0015).
Pipeline de CI en GitHub Actions: compilar → pruebas + puertas de calidad (ADR-0013). El reviewer
automático de Copilot en PRs contra `develop` bloquea el merge hasta resolución explícita de cada
comentario — nunca se resuelve solo para desbloquear, se lee y se corrige si aplica. Para toda
historia nueva desde el 2026-09-07: los artefactos de spec-kit (`spec.md`, `plan.md`, `tasks.md`) se
commitean en la misma rama que el código que implementan, no después ni por separado. `tasks.md`
incluye siempre una tarea explícita de prueba end-to-end (Principio IV) — no una tarea genérica de
"pruebas" que la de por cumplida con solo unitarias.

## Governance

Esta constitución tiene precedencia sobre cualquier práctica ad hoc previa. Las enmiendas requieren:
actualizar este archivo, incrementar la versión según semver (MAJOR: eliminar o redefinir un
principio de forma incompatible; MINOR: agregar un principio o sección; PATCH: aclaraciones sin
cambio de fondo), y un Sync Impact Report como comentario HTML al inicio del archivo. Las decisiones
de arquitectura y el backlog de producto se documentan donde el autor lo considere práctico para su
propio seguimiento — esta constitución no les asigna una ubicación formal ni declara ninguna
herramienta externa como fuente de verdad. Lo único vinculante para el proceso de desarrollo asistido
por IA es lo que está escrito en este archivo y en `specs/<historia>/`.

**Version**: 1.1.0 | **Ratified**: 2026-09-07 | **Last Amended**: 2026-09-07
