# ADR-0012 — Spring Boot 4 sobre WebFlux/Reactor

**Estado**: Aceptado (2026-10-16). Reemplaza a ADR-0002.
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas"), `pom.xml` (`spring-boot.version`), `specs/023-migracion-spring-boot-4/`, HU2-106

## Contexto

ADR-0002 fijó Spring Boot 3 sobre WebFlux/Reactor. El starter del componente de seguridad
(`security-pep-integration-spring-boot-starter` 0.1.3) está construido sobre Spring Boot 4.1 y arrastra sus módulos;
con Spring Boot 3.5.16 en el proyecto, las pruebas de contexto completo fallan con `BeanDefinitionOverrideException`
(240 errores de 935 pruebas). Mezclar ambas líneas está descartado.

## Decisión

Migrar el servicio a Spring Boot 4.1.1 (Spring Framework 7, Jackson 3, Spring Modulith 2.1.1, springdoc 3.1.1,
Testcontainers 2) sobre Java 25 (ADR-0011). El stack sigue siendo WebFlux/Reactor reactivo y no bloqueante (ADR-0003).

## Consecuencias

- Jackson 3 (`tools.jackson`) en todo el código propio; las anotaciones siguen en `com.fasterxml.jackson.annotation`.
  Jackson 2 queda solo como dependencia transitiva de MinIO, springdoc (swagger-core), el SDK de Azure y jjwt-jackson (excepción EX-01, revisión 2027-01-15).
- Propiedades renombradas: `spring.mongodb.uri`, `spring.http.codecs.max-in-memory-size`,
  `management.opentelemetry.tracing.export.otlp.endpoint`. Se retira `management.endpoint.prometheus.enabled`.
- `EnvironmentPostProcessor` pasa a `org.springframework.boot.EnvironmentPostProcessor`.
- Se retiran `resilience4j-spring-boot3` y `spring-retry`, que no tenían ningún uso.
- Las pruebas usan `@MockitoBean`/`@MockitoSpyBean`, los módulos de prueba por tecnología de Boot 4 y Testcontainers 2.
- Una regla de `maven-enforcer-plugin` impide que vuelvan a entrar artefactos de la línea 3.
- Los pines de seguridad que superan al BOM de Boot se conservan por propiedad con motivo y condición de retiro.
- El detalle de los cambios de comportamiento hallados está en `specs/023-migracion-spring-boot-4/cambios-de-comportamiento.md`.

## Alternativas consideradas

- **Quedarse en Spring Boot 3.5.** Descartada: el starter del PEP no carga en esa línea.
- **Convivencia de 3.5 y 4.x.** Descartada: produce conflictos de beans y de versiones.
- **No usar el starter.** Descartada por ADR-0011: la guía de integración exige que el PEP decida antes de ejecutar la ruta.
