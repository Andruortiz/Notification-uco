# Quickstart: validar la migración a Spring Boot 4.1 (HU2-106)

Guía de validación, no de implementación. Los detalles de cada comprobación están en `plan.md`, `research.md` y
`contracts/compatibilidad-observable.md`.

## Requisitos previos

- JDK 25 (`JAVA_HOME` apuntando a JDK 25; la máquina de desarrollo tiene también 21 como valor por defecto).
- Docker en ejecución para las pruebas de integración y E2E (Testcontainers). Si el motor es Docker Engine 29:
  `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`.
- Acceso al repositorio de paquetes del starter del PEP (`maven.pkg.github.com/seguridad-uco/securitybaseline`) o el starter
  ya en `~/.m2`.

## 1. El classpath está en una sola línea (US1, SC-002)

1. `./mvnw -B -ntp -pl infrastructure dependency:tree`
2. Esperado: todo `org.springframework.boot:*` en 4.1.x; sin `resilience4j-spring-boot3`, `spring-modulith` 1.x, `springdoc` 2.x ni
   `spring-retry`. La regla de construcción que lo impone falla si aparece alguno.

## 2. Compilación y puerta completa (US1, SC-001)

1. `./mvnw -B -ntp clean compile`
2. `./mvnw -B -ntp verify` (unos 10 minutos; en segundo plano). Esperado: 0 fallos y 0 errores, Spotless y SpotBugs en verde,
   cobertura >= 80 % líneas y >= 70 % ramas.
3. Las dos pruebas de DLQ requieren un broker en `localhost:5673` (`docker run -d --name rabbit-ci-temp -p 5673:5672 rabbitmq:3-management`);
   si fallan solo ellas por orden de ejecución local, las decide el pipeline.

## 3. Arquitectura (US1, FR-012)

`./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,NoHardcodedChannelTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false`

## 4. Sin APIs removidas (SC-003, SC-008)

Las pruebas de guardia (ver `plan.md`, tarea de guardias) buscan importaciones prohibidas y claves de configuración
eliminadas. Se ejecutan dentro de `verify`.

## 5. Comportamiento observable igual (US2)

1. Arrancar `docker compose up -d mongodb rabbitmq` y el servicio con `./mvnw -pl infrastructure spring-boot:run`
   (puerto 8060, gestión 8061).
2. `curl -s localhost:8061/actuator/health` -> `UP` con `mongo`, `rabbit`, `livenessState`, `readinessState`.
3. `curl -s localhost:8061/actuator/prometheus | grep -E "http_server_requests|spring_rabbitmq_listener|notification_configuration_version"` ->
   las mismas series que en el catálogo de referencia.
4. Las pruebas `*E2ETest` automatizan el resto (contrato, eventos, trazas, logs).

## 6. Starter del PEP (US3, SC-006)

- PEP deshabilitado: el arranque por defecto de pruebas (`security.pep.enforcement.enabled=false`).
- PEP habilitado: prueba de contexto con el PEP simulado (URL local `http://localhost:18081` si el entorno de seguridad está
  levantado). Esperado: contexto levanta, 0 `BeanDefinitionOverrideException`, arranque <= 30 s.

## 7. Imagen y pipeline (US4, SC-007)

`docker build -t notification-service:hu2-106 .` y arranque del contenedor con las dependencias; el pipeline corre al hacer
push (decisión del usuario).
