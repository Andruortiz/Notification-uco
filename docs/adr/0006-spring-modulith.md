# ADR-0006 — Spring Modulith para verificar límites de módulo

**Estado**: Aceptado
**Referenciado en**: `.specify/memory/constitution.md`, `ModularityTests.java`, backlog (riesgo: "Ingesta y despacho comparten proceso — no se pueden escalar por separado")

## Contexto

Ingesta (API REST) y despacho (listener de RabbitMQ) comparten el mismo proceso dentro del
monolito modular — un riesgo identificado en el backlog es que no se pueden escalar por separado
mientras sigan en el mismo deployable.

## Decisión

Usar Spring Modulith (`ModularityTests`) para verificar automáticamente los límites de módulo
dentro del monolito, generando documentación (`docs/modulith/`, no versionada) en cada build.

## Consecuencias

- `ModularityTests.verify()` corre en cada PR y falla el build si aparece acoplamiento indebido
  entre módulos.
- `ModularityTests.writesDocumentation()` genera diagramas en `infrastructure/target/spring-modulith-docs/`
  (no versionado, se regenera en cada build).
- El riesgo de "ingesta y despacho no se pueden escalar por separado" queda **documentado pero no
  resuelto** — separar ambos en procesos/deployables distintos es un cambio de arquitectura mayor
  que no se ha decidido todavía. Modulith detecta el acoplamiento, no lo elimina por sí solo.

## Alternativas consideradas

- **Separar ingesta y despacho en dos servicios desde el inicio**: descartada por ahora — para el
  volumen actual del proyecto, la complejidad operativa de dos deployables no se justifica; Modulith
  permite mantenerlos en un monolito modular con la opción de separarlos después si el riesgo se
  materializa.
- **No verificar los límites de módulo** (solo por convención): descartada — un límite de
  arquitectura no verificado automáticamente tiende a erosionarse con el tiempo (ver Principio I de
  la constitución: "un principio no verificado no es un principio, es una intención").
