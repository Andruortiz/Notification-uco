# Implementation Plan: Catálogo de canales persistido en Mongo

**Branch**: `feature/HU2-107-catalogo-mongo` | **Date**: 2026-09-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-catalog-mongo-persistence/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Reemplazar `application.yml` como fuente de verdad del catálogo de canales por una colección Mongo (`channel_catalog`). El camino de lectura (`ChannelCatalogPort`) nunca consulta Mongo de forma síncrona: lee de un snapshot en memoria por réplica, refrescado periódicamente (`@Scheduled`) desde Mongo — así una caída temporal de la base de datos no afecta el despacho (ADR-0010). Un componente de arranque migra, una sola vez por ambiente, las entradas que hoy existen en `notification.catalog.channels` hacia la nueva colección, reutilizando `ChannelCatalogProperties` como fuente de la semilla. Sin API de administración ni catálogo por tenant en esta historia (decisiones de alcance confirmadas con el usuario en `/speckit-specify`).

## Technical Context

**Language/Version**: Java 21 (ADR-0001)

**Primary Dependencies**: Spring Boot 3 / WebFlux, Reactor, Spring Data Reactive MongoDB (`ReactiveMongoTemplate`, ya en uso), Spring Scheduling (`@Scheduled`, ya habilitado desde HU2-030) — sin dependencia nueva

**Storage**: MongoDB — nueva colección `channel_catalog` (`_id` = nombre del canal)

**Testing**: JUnit 5, Mockito, StepVerifier, Testcontainers (Mongo) para la prueba E2E de migración + resolución de rutas

**Target Platform**: Kubernetes (igual que el resto del componente)

**Project Type**: Microservicio hexagonal — historia que toca `infrastructure` (nuevo adaptador de salida, componente de refresco, componente de migración de arranque); `core` no cambia (el puerto `ChannelCatalogPort` ya existe y no se modifica)

**Performance Goals**: Ninguno nuevo — el catálogo es pequeño (pocos canales); el snapshot en memoria hace que la resolución de rutas siga siendo un acceso local, sin latencia de red en el camino de despacho

**Constraints**: No bloquear hilos reactivos (refresco y migración usan el pipeline reactivo de `ReactiveMongoTemplate`, no llamadas bloqueantes); el snapshot en memoria nunca se vacía por un fallo de refresco — solo se reemplaza cuando el refresco es exitoso (FR-005)

**Scale/Scope**: Catálogo global, unos pocos canales — no escala por tenant ni por volumen de escritura (sin API de escritura en esta historia)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principio I (Arquitectura hexagonal)**: cumple — `ChannelCatalogPort` (ya existente en `core/port/out/`) no cambia; toda la implementación nueva (adaptador Mongo, refresco programado, migración de arranque) vive en `infrastructure`. `core` no gana ninguna dependencia de Spring ni de Mongo.
- **Principio II (Contract-first API)**: no aplica — esta historia no expone ni modifica ningún endpoint HTTP (FR-008, decisión de alcance confirmada con el usuario).
- **Principio III (Cero comentarios)**: cumple — código sin comentarios explicativos.
- **Principio IV (Calidad verificada)**: pendiente hasta `tasks.md` — requiere cobertura ≥80/70, ArchUnit/Modulith en verde, Spotless/SpotBugs/FindSecBugs limpios, y **prueba E2E explícita** (bloqueante): con Testcontainers Mongo vacío, arrancar el contexto, verificar que la migración sembró el catálogo de `application.yml`, y que `ChannelCatalogPort.findActiveRoute` resuelve la ruta esperada.
- **Principio V (Trazabilidad git)**: rama `feature/HU2-107-catalogo-mongo` (HU2-107 asignado en Notion 2026-09-12, backlog "Modelo de datos del catálogo dinámico en MongoDB"), creada desde `develop`, commits de una sola línea, sin atribución de IA.
- **Principio VI (spec-kit)**: spec ya aprobado (con las dos decisiones de alcance resueltas por el usuario antes de escribir el spec); este plan se aprueba antes de generar `tasks.md`.
- **Principio VII (Sin atajos)**: la decisión de refrescar solo por sondeo (sin Change Streams) queda documentada explícitamente en `research.md` (punto 2), con su razón técnica (Mongo local no corre como replica set) — no es un olvido, es una excepción documentada.
- **Restricciones técnicas — estrategia de caché (ADR-0010)**: esta historia es la aplicación directa de "caché de lecturas semi-estáticas del catálogo de canales, local por réplica, con TTL de respaldo" ya definida como restricción — no introduce una estrategia nueva, la implementa por primera vez para este dato concreto.

**Resultado del gate**: PASS. No hay violaciones que requieran justificación — se omite Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/002-catalog-mongo-persistence/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No se genera `contracts/` — esta historia no expone ningún contrato externo nuevo (FR-008: sin API de administración).

### Source Code (repository root)

```text
core/src/main/java/co/edu/uco/notification/core/
└── port/out/ChannelCatalogPort.java          # sin cambios — solo se reimplementa su adaptador

infrastructure/src/main/java/co/edu/uco/notification/infrastructure/
├── adapter/out/catalog/
│   ├── ChannelCatalogDocument.java            # nuevo — documento Mongo (_id = channelType)
│   ├── ChannelCatalogCache.java                # nuevo — snapshot en memoria (AtomicReference<Map<String, ChannelRoute>>)
│   ├── MongoChannelCatalogAdapter.java          # nuevo — implementa ChannelCatalogPort leyendo de ChannelCatalogCache
│   ├── ChannelCatalogRefresher.java             # nuevo — @Scheduled, consulta Mongo y reemplaza el snapshot en éxito
│   ├── ChannelCatalogSeeder.java                 # nuevo — ApplicationRunner, migra desde ChannelCatalogProperties si la colección está vacía
│   ├── ChannelCatalogProperties.java             # modificado en su rol — deja de alimentar el runtime, solo alimenta la migración
│   └── ConfigurationChannelCatalogAdapter.java   # eliminado — reemplazado por MongoChannelCatalogAdapter
└── config/UseCaseConfig.java                     # sin cambios — no depende de la implementación de ChannelCatalogPort

infrastructure/src/test/java/co/edu/uco/notification/infrastructure/adapter/out/catalog/
├── ChannelCatalogSeederTest.java                 # nuevo
├── ChannelCatalogRefresherTest.java              # nuevo
├── MongoChannelCatalogAdapterTest.java           # nuevo
└── ConfigurationChannelCatalogAdapterTest.java   # eliminado (su adaptador desaparece)
    (más la prueba E2E que exige el Principio IV — se define en tasks.md)
```

**Structure Decision**: Toda la historia vive en `infrastructure/adapter/out/catalog/` — es un cambio de adaptador de salida puro, consistente con que `ChannelCatalogPort` (el puerto en `core`) no cambia. No se introduce ningún adaptador de entrada HTTP ni cambio de contrato OpenAPI, consistente con FR-008 (sin API de administración en esta historia).

## Complexity Tracking

> Sin violaciones del Constitution Check — no aplica.
