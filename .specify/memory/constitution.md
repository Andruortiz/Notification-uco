<!--
Sync Impact Report
Version change: 1.1.0 → 1.2.0
Rationale: MINOR — two new principles added (VIII, IX), new technical restrictions added (secrets
propagation, configuration-vs-secrets, RabbitMQ consumer ack/DLQ), and one restriction loosened
(Principle V's "sin atribución de IA" sub-clause removed — the principle itself, trazabilidad en
git, is unchanged; only the no-AI-attribution constraint was dropped, consistent with the
attribution convention already in active use for recent commits). No principle redefined
incompatibly.
Modified principles:
- II. Contract-first — clarified that the contract may list planned/blocked operations as long as
  they are identified as such.
- III. Cero comentarios explicativos — carved out an explicit exception for public-API
  documentation comments required by tooling (e.g. Javadoc).
- IV. Calidad verificada, no declarada — added RNF-15 (maintainability: cyclomatic complexity and
  duplication bounded by static analysis), explicitly marked pending until the pipeline has a
  configured, blocking threshold for both metrics (no tool for this exists in `pom.xml` yet).
- V. Trazabilidad en git — removed the "sin atribución de IA en el historial" restriction (title
  unchanged; the restriction it named no longer applies).
- VI. Desarrollo asistido por IA, gobernado por spec-kit — removed the ADR-0016 cross-reference
  (ADR-0016 itself is unaffected; the principle no longer needs to disclaim it inline).
Added principles: VIII. Confiabilidad y durabilidad de notificaciones; IX. Observabilidad y
trazabilidad.
Added sections: six new paragraphs under Restricciones técnicas — secrets must not leak into
logs/HTTP responses/RabbitMQ messages/CI artifacts; non-sensitive structural configuration is
versioned while secrets/credentials/environment values are injected via environment variables;
structured (machine-parseable) logging with correlation/tenant/notification identifiers, no
secrets/full payload in logs, and technical+business metrics exposed externally, formalizing RNF-10
(previously an open gap per `docs/requisitos.md` — "cero infraestructura de logging en el código a
esta fecha"); message consumers must ack explicitly only after persisting the result, with exhausted
messages routed to a DLQ with traceable cause (already implemented in practice by HU2-040's
dead-letter queue, merged 2026-09-13); disposable-availability constraints formalizing RNF-12
(≤30s startup, ordered shutdown that lets an in-flight RabbitMQ ack/nack and MongoDB write complete
before the process exits); real liveness/readiness health probes formalizing RNF-11 (both previously
unformalized in this document, though not flagged as gaps in `docs/requisitos.md` the way RNF-10
was).
Removed sections: none.
Deferred / TODO: Redis (or equivalent) for the per-provider rate limiter is a restriction stated
here but not yet added to docker-compose/pom.xml — deferred until that Fase 5 story is actually
built (carried over from v1.1.0, still unresolved). RNF-15's complexity/duplication threshold has no
enforcing tool configured yet (Principle IV notes this explicitly as pending, per Principle VII).
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
El contrato puede contener operaciones planificadas/bloqueadas, siempre que estén identificadas como
tales.

### III. Cero comentarios explicativos
El código nuevo no lleva comentarios `//` que expliquen huecos, decisiones o trade-offs — el nombre
de las clases/métodos y las pruebas son la documentación. No se prohíben los comentarios de
documentación pública requeridos por herramientas, como Javadoc de APIs públicas cuando sea necesario
para generar documentación. El razonamiento detrás de una decisión vive en el spec/plan de la
historia (`specs/<historia>/`) o en el cuerpo del PR, nunca en el código.

### IV. Calidad verificada, no declarada
Ninguna historia se mergea sin que CI esté en verde: compilación, cobertura ≥ 80 % líneas / ≥ 70 %
ramas bloqueante (ADR-0014), análisis estático (SpotBugs + FindSecBugs + Spotless) bloqueante
(ADR-0012), y al menos una prueba end-to-end que ejercite el flujo completo de la historia contra el
proveedor simulado (`SimulatedNotificationProvider`) o el adaptador real correspondiente. Cobertura
unitaria de la lógica interna no sustituye una prueba E2E cuando la historia toca un flujo observable
de punta a punta — son puertas distintas, ambas bloqueantes. "Funciona en mi máquina" no es un criterio
de aceptación. La mantenibilidad (RNF-15) es parte de la misma puerta de análisis estático:
complejidad ciclomática y duplicación de código se mantienen dentro de límites verificables, no solo
el estilo. Mientras el pipeline no tenga un umbral configurado y bloqueante para ambas métricas, esto
queda como pendiente explícito de esta puerta (Principio VII), no como un criterio ya cumplido.

### V. Trazabilidad en git
Commits de una sola línea (`tipo(ámbito): descripción`), rama por historia siguiendo GitFlow
(`feature/HU2-XXX-descripcion`, ADR-0015). El razonamiento completo vive en el cuerpo del PR o en
`specs/<historia>/`, nunca en el commit. El usuario del repositorio siempre revisa y mergea — un
asistente de IA nunca hace merge por su cuenta.

### VI. Desarrollo asistido por IA, gobernado por spec-kit
Toda historia nueva desarrollada con asistencia de IA sigue el flujo `/speckit-specify` →
`/speckit-plan` → `/speckit-tasks` → `/speckit-implement`, con los artefactos versionados en
`specs/<historia>/`. El usuario aprueba explícitamente la constitución, el spec y el plan antes de
que se implemente una sola línea — esa aprobación es la evidencia de revisión humana, no una
formalidad. Esto rige el *proceso de desarrollo*. Este principio aplica a las historias desarrolladas
desde el 2026-09-07 en adelante; el trabajo anterior no se re-documenta retroactivamente — ver la
nota retrospectiva en el README.

### VII. Sin atajos
Ninguna solución temporal se acepta como definitiva. Si una puerta de calidad, una migración de datos
o una regla de negocio no tiene todavía una implementación correcta disponible, se documenta como
pendiente explícito (excepción con dueño y fecha) — nunca se resuelve con un parche que oculte el
problema real solo para que la build pase. La cobertura relajada a 30 % que nunca se revirtió, en el
primer intento de implementación de este proyecto, es exactamente el error que este principio existe
para no repetir.

### VIII. Confiabilidad y durabilidad de notificaciones
Una notificación aceptada por la API no puede depender exclusivamente de memoria ni de la
disponibilidad inmediata del proveedor. La aceptación debe persistirse antes de considerar exitosa la
solicitud, y todo fallo posterior de transporte o proveedor debe resultar en un estado recuperable,
reintento o fallo terminal trazable. Las notificaciones agotadas deben conservar su historial y ser
derivadas a una DLQ cuando corresponda. Ningún error de infraestructura puede provocar silenciosamente
la pérdida de una notificación aceptada.

### IX. Observabilidad y trazabilidad
Toda notificación debe poder reconstruir su recorrido desde la aceptación hasta su resultado
terminal. Los eventos relevantes deben incluir identificadores de correlación, tenant, notificationId
y contexto suficiente para diagnosticar fallos sin exponer credenciales ni datos sensibles. Los
errores de infraestructura y proveedor deben distinguirse de los errores permanentes de negocio.

## Restricciones técnicas

Java 21 LTS (ADR-0001), Spring Boot 3 sobre WebFlux/Reactor — reactivo, no bloqueante (ADR-0002,
ADR-0003). MongoDB y RabbitMQ gestionados fuera de local en todo ambiente distinto a desarrollo
(ADR-0006, ADR-0007). Extensibilidad por catálogo declarativo + adaptadores: agregar un canal o
proveedor no debe requerir modificar el núcleo (ADR-0009 — la decisión que define el proyecto).
Cumplimiento de 15 factores como checklist transversal verificable (ADR-0011). Ninguna credencial
reside en código ni en el repositorio (RNF-09) — tampoco deben aparecer en logs, respuestas HTTP,
mensajes de RabbitMQ ni artefactos generados por CI. Toda operación de la API exige autenticación y
aislamiento multi-tenant una vez se integre el Componente de Seguridad (RNF-13, RNF-14) — mientras
`DEP-01` siga bloqueado, el placeholder `X-Tenant-Id` es aceptable en desarrollo, nunca en producción.

La configuración no sensible y estructural de la aplicación se versiona en archivos de configuración;
los secretos, las credenciales y los valores específicos del entorno se inyectan mediante variables
de entorno o un mecanismo equivalente. Ningún secreto se versiona.

Los logs se emiten en formato estructurado (JSON o equivalente parseable por máquina), nunca como
texto plano libre (RNF-10) — permite correlacionar un fallo entre réplicas sin depender de
expresiones regulares ad hoc. Todo log ligado al ciclo de vida de una notificación incluye
identificador de correlación, `tenantId` y `notificationId` (Principio IX); ningún log incluye
credenciales, tokens, ni el cuerpo completo del contenido de la notificación. Las métricas técnicas
(latencia, throughput, tasa de error) y de negocio (notificaciones aceptadas/despachadas/fallidas por
canal) relevantes se exponen para consulta externa, no solo quedan registradas en el log.

Con múltiples réplicas del contenedor, toda mutación de un agregado ya persistido usa bloqueo
optimista (campo `version`, incrementado en cada escritura condicionada a su valor anterior) — nunca
bloqueo pesimista, porque el flujo de despacho mantiene una llamada externa lenta al proveedor entre
leer y guardar. La idempotencia de aceptación se garantiza con una restricción única
`(tenantId, externalId)` a nivel de base de datos, no solo con una verificación previa en la
aplicación. El despacho debe ser seguro ante redelivery de mensajes y ejecuciones concurrentes,
utilizando estado persistido y/o mecanismos de idempotencia apropiados; un mensaje duplicado no debe
producir una entrega duplicada de manera silenciosa. Cualquier operación de "tomar" un recurso
compartido entre réplicas (ej. el scheduler de reintentos) se implementa como una actualización
condicionada atómica (`findAndModify`/`UPDATE ... WHERE ... RETURNING`), nunca como leer-luego-
escribir.

El consumidor de mensajes debe utilizar confirmación explícita del procesamiento (ack manual, no
automático) y no debe eliminar un mensaje de la cola antes de que el resultado correspondiente haya
sido persistido. Los mensajes que no puedan procesarse después de los reintentos establecidos deben
terminar en una cola de mensajes muertos (DLQ) con trazabilidad del motivo del fallo.

El arranque del servicio toma 30 segundos o menos (RNF-12) — ningún trabajo sincrónico pesado bloquea
el arranque. El apagado ante una señal de terminación es ordenado: el consumidor de RabbitMQ termina
de procesar y confirma (ack/nack) el mensaje en curso antes de cerrar la conexión — nunca se
interrumpe a mitad de un ack pendiente ni de una escritura a MongoDB ya iniciada, consistente con la
regla de confirmación explícita del consumidor de mensajes anterior.

El servicio expone sondas de salud reales en `/actuator/health` (RNF-11): *liveness* (¿debe
reiniciarse el proceso?) y *readiness* (¿debe recibir tráfico ahora mismo?) son preguntas distintas y
se distinguen como tales. El resultado refleja el estado de las dependencias que importan (conexión a
MongoDB, conexión a RabbitMQ) — no basta con que el proceso Java siga vivo.

Caché de lecturas semi-estáticas (catálogo de canales, claves de validación de token) es local por
réplica con invalidación por evento + TTL de respaldo — nunca un almacén compartido, porque el dato es
pequeño y tolera segundos de vencimiento. Estado que debe ser estrictamente consistente entre réplicas
(ej. un contador de límite de tasa por proveedor) sí requiere un almacén compartido real — una caché
local por réplica permitiría que cada una agote el límite completo por su cuenta.

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

**Version**: 1.2.0 | **Ratified**: 2026-09-07 | **Last Amended**: 2026-09-14
