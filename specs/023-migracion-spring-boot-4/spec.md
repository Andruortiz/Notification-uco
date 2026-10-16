# Feature Specification: Migración del servicio a Spring Boot 4.1 sobre Java 25 (HU2-106)

**Feature Branch**: `feature/HU2-106-migracion-spring-boot-4`

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "Como equipo de desarrollo, quiero migrar el servicio de Spring Boot 3.5 a Spring Boot 4.1 sobre
Java 25, para poder usar el starter del PEP, que está construido sobre esa línea, sin mezclar versiones."

Trazabilidad: HU2-097 (cambio a Java 25, absorbido por esta historia), HU2-055 (autenticar contra el componente de
seguridad; esta historia es su prerrequisito), ADR-0011 (Java 25), ADR-0002 (Spring Boot 3 sobre WebFlux; esta historia
lo reemplaza). Principios involucrados: I (hexagonal), II (contrato), III (sin comentarios), IV (calidad verificada),
VII (sin atajos), IX (observabilidad).

Contexto: el servicio declara el starter `security-pep-integration-spring-boot-starter` 0.1.3, cuyo padre es
`spring-boot-starter-parent` 4.1.0. Con Spring Boot 3.5.16 en el proyecto, `./mvnw verify` ejecuta 935 pruebas con 240
errores, todos en pruebas que levantan el contexto completo, por `BeanDefinitionOverrideException` en el bean
`jsonComponentModule` (definido por una autoconfiguración de Boot 4 que no existe en 3.5). Migrar a medias o dejar
convivir 3.5 y 4.x está descartado. Esta historia es una migración de plataforma: el comportamiento observable del
servicio no cambia. Los "usuarios" de la historia son el equipo que desarrolla y opera el servicio, los clientes de la
API (que no deben notar nada) y quien consume métricas, trazas y logs.

## Clarifications

### Session 2026-10-16

Las decisiones abiertas se resolvieron con la recomendación indicada y quedan pendientes de confirmación del usuario al
aprobar el plan (ver Assumptions, S-1 a S-6).

- Q1: ¿Qué se hace con `resilience4j-spring-boot3` y `spring-retry`, declarados pero sin ningún uso en el código? ->
  Se retiran. Ninguna clase importa `io.github.resilience4j` ni `org.springframework.retry`; `resilience4j-spring-boot3`
  es una librería de la línea 3 (viola FR-001) y `spring-retry` ya no está gestionado por el BOM de Boot 4.
- Q2: ¿Se exige Jackson 3 puro en todo el classpath o se tolera Jackson 2 transitivo? -> El código del repositorio usa
  solo Jackson 3; se tolera Jackson 2 únicamente como dependencia transitiva de librerías de terceros que aún lo
  requieren (jjwt-jackson, Azure SDK), registrado como excepción con dueño y fecha (Principio VII).
- Q3: ¿Qué pasa con los pines de seguridad de Netty, Jackson, amqp-client, BouncyCastle y OkHttp? -> Se conservan solo
  los que superen la versión que gestiona el BOM de Boot 4.1.1, como propiedades que lo sobrescriben, cada uno con su
  motivo; los que el BOM ya iguala o supera se retiran.
- Q4: ¿Una sola entrega o entregas intermedias? -> Una rama y un PR; commits ordenados por módulo y fase. No hay estado
  intermedio publicable (el classpath no puede mezclar líneas).
- Q5: ¿Número del ADR nuevo? -> ADR-0012, que reemplaza a ADR-0002 (la numeración en `docs/adr/` llega hoy a 0011).
- Q6: ¿Se renombran las variables de entorno de despliegue al cambiar propiedades de Spring? -> No. Cambian las
  propiedades de Spring en los archivos de configuración; los nombres de las variables de entorno
  (`MONGO_*`, `RABBITMQ_*`, `SECURITY_*`, etc.) se conservan.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El servicio compila, arranca y pasa toda la suite sobre Spring Boot 4.1 y Java 25 (Priority: P1)

El equipo obtiene un servicio construido íntegramente sobre Spring Boot 4.1.x y Java 25, sin librerías de la línea 3 en
el classpath, con la suite completa en verde y las puertas de calidad (Spotless, SpotBugs, cobertura) intactas.

**Why this priority**: Es el objetivo de la historia y el prerrequisito de HU2-055. Mientras el classpath mezcle líneas,
ninguna prueba de contexto completo pasa y el starter del PEP no se puede usar.

**Independent Test**: `./mvnw -B -ntp verify` termina en verde con las mismas pruebas que hoy (más las que esta historia
añada) y un chequeo automatizado del árbol de dependencias confirma que no hay artefactos Spring Boot 3.x.

**Acceptance Scenarios**:

1. **Given** el árbol de dependencias resuelto del módulo `infrastructure`, **When** se inspecciona, **Then** todo
   artefacto `org.springframework.boot:*` está en una versión 4.1.x y no aparece ningún artefacto de la línea 3
   (`resilience4j-spring-boot3`, `springdoc` 2.x, `spring-modulith` 1.x, `spring-boot-starter-*` 3.x).
2. **Given** el repositorio migrado, **When** se ejecuta `./mvnw -B -ntp verify` con Docker disponible, **Then** pasan las
   pruebas unitarias, de integración y E2E, Spotless, SpotBugs/FindSecBugs y la cobertura de 80 % de líneas y 70 % de
   ramas.
3. **Given** el repositorio migrado, **When** se ejecutan `HexagonalArchitectureTest`, `NoHardcodedChannelTest` y
   `ModularityTests`, **Then** pasan sin relajar ninguna regla.
4. **Given** el código fuente y de pruebas, **When** se busca `com.fasterxml.jackson.databind`, `com.fasterxml.jackson.core`,
   `@MockBean`, `@SpyBean`, `org.springframework.boot.actuate.health` y `org.springframework.boot.autoconfigure.amqp`,
   **Then** no hay coincidencias.

---

### User Story 2 - Los clientes y operadores no notan la migración (Priority: P1)

La API, los eventos de RabbitMQ, las métricas, las trazas y los logs se comportan igual que antes de la migración: mismas
rutas, códigos de estado y cuerpos; mismos nombres de métrica, atributos de traza y campos de log.

**Why this priority**: Una migración de plataforma que cambia el contrato o rompe tableros y alertas traslada el costo al
resto de la organización. Boot 4 y Jackson 3 cambian valores por defecto (orden de propiedades, nombres de propiedades de
configuración, exportación de trazas) que pueden alterar el comportamiento sin romper la compilación.

**Independent Test**: Una prueba de contrato compara las respuestas reales de cada endpoint (rutas, estados, cuerpos
JSON) y los mensajes publicados en RabbitMQ contra la expectativa fijada antes de migrar; otra compara el catálogo de
métricas y los campos del log estructurado.

**Acceptance Scenarios**:

1. **Given** cada operación declarada en `api-notificaciones.yaml`, **When** se invoca contra el servicio migrado,
   **Then** el estado HTTP y el cuerpo coinciden con el contrato, y `api-notificaciones.yaml` no cambia.
2. **Given** un evento de dominio publicado a RabbitMQ y un evento consumido (despacho, escaneo de adjuntos,
   parámetros), **When** se serializa y deserializa con Jackson 3, **Then** el contenido es equivalente y un mensaje
   producido por la versión anterior sigue siendo legible.
3. **Given** el endpoint de métricas, **When** se consulta tras ejercitar los flujos, **Then** aparecen las mismas
   métricas (nombre y etiquetas) que antes, incluidas `http.server.requests` y `spring.rabbitmq.listener` con histograma
   de percentiles, y `notification.configuration.version`.
4. **Given** un flujo con trazado activo y un destino OTLP configurado, **When** se atiende una solicitud, **Then** el
   servicio exporta la traza al destino configurado y propaga `traceId` y `spanId` al log y al mensaje de RabbitMQ.
5. **Given** una línea de log estructurado, **When** se emite, **Then** conserva los campos `service`, `correlationId`,
   `tenantId`, `notificationId`, `traceId`, `spanId` y el formato saneado de excepciones.
6. **Given** un cuerpo de carga útil grande (hasta 8 MB), **When** llega a un endpoint que lo admite, **Then** se acepta
   igual que antes.

---

### User Story 3 - El servicio arranca con el starter del PEP en el classpath (Priority: P1)

Con el starter del PEP en el classpath, el servicio arranca sin conflictos de beans, tanto con el PEP deshabilitado
(perfil local y pruebas) como habilitado.

**Why this priority**: Es la razón de ser de la migración y condición de HU2-055.

**Independent Test**: Una prueba de contexto arranca la aplicación completa con `security.pep.enforcement.enabled=false` y
otra con el PEP habilitado apuntando a un PEP simulado, y ambas terminan sin `BeanDefinitionOverrideException`.

**Acceptance Scenarios**:

1. **Given** el starter en el classpath y el PEP deshabilitado, **When** arranca el contexto completo, **Then** no hay
   conflictos de beans y el servicio responde igual que antes de añadir el starter.
2. **Given** el starter habilitado con una URL de PEP válida, **When** arranca, **Then** el contexto levanta y el filtro
   del PEP se registra una vez, sin duplicar el filtro propio de autorización.
3. **Given** el servicio migrado, **When** se inspecciona el classpath, **Then** el starter y el servicio comparten la
   misma versión de Spring Boot.

---

### User Story 4 - La imagen y el pipeline funcionan con Java 25 y Spring Boot 4.1 (Priority: P2)

La imagen Docker se construye y el pipeline de CI/CD pasa de punta a punta con la versión nueva.

**Why this priority**: Sin pipeline en verde la historia no se puede fusionar, pero depende de que las historias
anteriores estén resueltas.

**Independent Test**: Construir la imagen con el `Dockerfile` y ejecutar el pipeline (build, pruebas, análisis estático,
cobertura, imagen y escaneo) sobre la rama.

**Acceptance Scenarios**:

1. **Given** el `Dockerfile`, **When** se construye la imagen, **Then** termina sin error y el contenedor arranca y
   responde a las sondas de salud.
2. **Given** la rama de la historia, **When** corre el pipeline, **Then** todas las etapas pasan, incluido CodeQL.

---

### User Story 5 - La decisión y sus consecuencias quedan documentadas (Priority: P2)

Un ADR reemplaza la decisión de Spring Boot 3 y una nota registra los cambios de comportamiento encontrados durante la
migración, para que el equipo no los redescubra.

**Why this priority**: Es un criterio de aceptación explícito y alimenta futuras migraciones (Principio VII: lo pendiente
queda como excepción con dueño y fecha).

**Independent Test**: Revisar que existen `docs/adr/0012-*.md` con estado "Reemplaza a ADR-0002", la restricción técnica
de la constitución actualizada y el documento de cambios de comportamiento.

**Acceptance Scenarios**:

1. **Given** el repositorio al cerrar la historia, **When** se revisa `docs/adr/`, **Then** hay un ADR nuevo que reemplaza
   al ADR-0002 y cita HU2-106, el starter del PEP y las alternativas descartadas.
2. **Given** la constitución, **When** se lee "Restricciones técnicas", **Then** menciona Spring Boot 4 y el ADR nuevo.
3. **Given** el cierre de la historia, **When** se revisa `specs/023-migracion-spring-boot-4/`, **Then** existe una nota
   con cada cambio de comportamiento detectado, su causa y su resolución.

---

### Edge Cases

- Una propiedad de configuración que Boot 4 renombró o eliminó se ignora sin fallar el arranque; el servicio arranca pero
  se comporta distinto (por ejemplo, una URI de Mongo, un límite de tamaño de carga o un destino OTLP sin efecto). Cada
  propiedad usada se verifica contra los metadatos de Boot 4.
- Un `EnvironmentPostProcessor` registrado con el contrato anterior deja de ejecutarse sin error visible (carga de
  secretos desde Key Vault, derivación del destino OTLP); se verifica con una prueba que falla si no se ejecuta.
- Jackson 3 ordena las propiedades de los objetos alfabéticamente por defecto y sus excepciones son no verificadas; el
  orden de campos de un cuerpo JSON o de un evento no puede cambiar el significado, pero sí el texto exacto de una
  comparación de cadenas.
- Un mensaje ya encolado en RabbitMQ por la versión anterior debe poder consumirse con la versión nueva (despliegue
  gradual con réplicas de ambas versiones durante la actualización).
- Una librería de terceros (jjwt-jackson, Azure SDK, validador de esquemas JSON) arrastra Jackson 2: ambas versiones
  coexisten en paquetes distintos sin que el contexto de Spring resuelva el bean equivocado.
- Un pin de seguridad por CVE se retira por error al alinear con el BOM y reabre una vulnerabilidad que estaba cerrada.
- Las pruebas de DLQ (`DeadLetterQueueE2ETest`, `RabbitRetryConfigCustomAttemptsTest`) dependen de un broker en
  `localhost:5673` y del orden de ejecución; deben seguir decidiéndose en CI.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El proyecto MUST compilar y ejecutarse sobre Spring Boot 4.1.x (Spring Framework 7) y Java 25, sin ningún
  artefacto de la línea 3 de Spring Boot, Spring Modulith 1.x, springdoc 2.x ni `resilience4j-spring-boot3` en el
  classpath.
- **FR-002**: Las dependencias de Spring (Framework, Data, AMQP, Modulith), Jackson, Micrometer, OpenTelemetry, springdoc,
  Testcontainers, Mockito y las demás gestionadas MUST resolverse mediante el BOM de Spring Boot 4.1 o el BOM de su
  proyecto; toda versión que sobrescriba al BOM MUST tener un motivo registrado (CVE o incompatibilidad) y un dueño.
- **FR-003**: El código de `core`, `infrastructure` y `utils` MUST usar los paquetes y APIs de Jackson 3 (`tools.jackson`,
  con las anotaciones que siguen en `com.fasterxml.jackson.annotation`) y de Spring Framework 7; no MUST quedar
  referencias a APIs eliminadas ni a los paquetes de autoconfiguración, de pruebas o de actuator que Boot 4 reubicó.
- **FR-004**: Los beans que reciben un mapeador JSON del contexto MUST recibir el de Jackson 3 y producir y leer los mismos
  documentos JSON que antes para eventos de dominio, mensajes de despacho, escaneo de adjuntos, parámetros y respuestas
  de proveedores externos (Brevo, Twilio, FCM).
- **FR-005**: La validación de esquemas de contenido por canal MUST producir los mismos veredictos y mensajes de error que
  antes para los esquemas del catálogo (EMAIL, SMS, PUSH) y para esquemas de canales nuevos.
- **FR-006**: El contrato OpenAPI y el comportamiento observable de todos los endpoints MUST permanecer idénticos: mismas
  rutas, métodos, códigos de estado, cabeceras relevantes y cuerpos (incluido el formato de errores y de fechas).
- **FR-007**: La configuración MUST seguir leyendo los mismos nombres de variable de entorno de despliegue; las
  propiedades de Spring que Boot 4 renombró MUST actualizarse en `application*.yml` y en las pruebas, y ninguna propiedad
  usada MUST quedar ignorada silenciosamente.
- **FR-008**: Los `EnvironmentPostProcessor` del servicio (carga de secretos de Key Vault y derivación del destino OTLP)
  MUST seguir ejecutándose al arrancar, con prueba que lo demuestre.
- **FR-009**: Las métricas, trazas y logs MUST conservar nombres, etiquetas y campos: histogramas de percentiles
  (`http.server.requests`, `spring.rabbitmq.listener`), endpoint de Prometheus en el puerto de gestión, sondas de
  liveness y readiness con los mismos componentes, exportación OTLP de trazas, y los campos del log estructurado.
- **FR-010**: Los consumidores de RabbitMQ MUST conservar confirmación manual y DLQ, la política de reintentos y el
  republicado de mensajes agotados, con los mismos destinos y cabeceras.
- **FR-011**: El servicio MUST arrancar con `security-pep-integration-spring-boot-starter` en el classpath, con el PEP
  deshabilitado y habilitado, sin conflictos de beans.
- **FR-012**: `HexagonalArchitectureTest`, `NoHardcodedChannelTest` y `ModularityTests` MUST pasar sin relajar sus reglas;
  `core` MUST seguir sin depender de Spring.
- **FR-013**: Las pruebas MUST usar las anotaciones y artefactos de Spring Boot 4 y Testcontainers 2 (por ejemplo
  `@MockitoBean` y `@MockitoSpyBean`, los módulos de prueba por tecnología) y MUST conservar su intención y su cobertura.
- **FR-014**: La imagen Docker MUST construirse y arrancar con Java 25, y el pipeline MUST pasar con Java 25 y Spring
  Boot 4.1.
- **FR-015**: MUST existir un ADR que reemplace a ADR-0002, la constitución MUST reflejar Spring Boot 4 en sus
  restricciones técnicas, y MUST existir una nota con los cambios de comportamiento hallados.
- **FR-016**: Toda excepción a estos requisitos (por ejemplo Jackson 2 transitivo de una librería de terceros) MUST
  quedar registrada con dueño y fecha de revisión, sin soluciones temporales presentadas como definitivas.

### Key Entities *(include if feature involves data)*

- **Línea de plataforma**: conjunto coherente de versiones (Spring Boot, Spring Framework, Jackson, Micrometer,
  OpenTelemetry, Testcontainers, Mockito) fijado por el BOM de Boot 4.1; el classpath solo puede contener una.
- **Sobrescritura de versión (pin)**: versión de una librería elegida por encima del BOM, con motivo (CVE o
  incompatibilidad), dueño y condición de retiro.
- **Cambio de comportamiento**: diferencia observable entre antes y después de la migración (valor por defecto, propiedad
  renombrada, orden de campos, excepción no verificada), con causa, prueba que la detecta y resolución.
- **Excepción registrada**: desviación aceptada de un requisito, con dueño y fecha (Principio VII).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `./mvnw -B -ntp verify` termina con 0 fallos y 0 errores y con cobertura de al menos 80 % de líneas y 70 %
  de ramas en cada módulo que hoy la mide; el número de pruebas ejecutadas no es menor que el de antes de la migración
  (935 más las que esta historia añada), salvo pruebas retiradas con justificación escrita.
- **SC-002**: Una comprobación automatizada del árbol de dependencias resuelto encuentra 0 artefactos de la línea 3 de
  Spring Boot, 0 artefactos de Spring Modulith 1.x y 0 de springdoc 2.x, y falla la construcción si aparece alguno.
- **SC-003**: Una búsqueda automatizada en el código fuente y de pruebas encuentra 0 importaciones de
  `com.fasterxml.jackson.databind`, `com.fasterxml.jackson.core`, `org.springframework.boot.test.mock.mockito`,
  `org.springframework.boot.actuate.health` y `org.springframework.boot.autoconfigure.amqp`.
- **SC-004**: El 100 % de las operaciones de `api-notificaciones.yaml` responde con el mismo estado y cuerpo que antes de
  la migración en una prueba de contrato automatizada; el archivo del contrato no cambia (0 líneas modificadas).
- **SC-005**: El catálogo de métricas expuesto tras ejercitar los flujos E2E contiene el 100 % de los nombres y etiquetas
  que contenía antes, verificado por una prueba automatizada; las trazas llegan al destino OTLP de prueba y los 7 campos
  de log conservados aparecen en la salida.
- **SC-006**: El servicio con el starter del PEP en el classpath arranca el contexto completo en 2 configuraciones
  (PEP deshabilitado y habilitado) con 0 `BeanDefinitionOverrideException`, y el arranque tarda no más de 30 s
  (restricción de disponibilidad desechable de la constitución).
- **SC-007**: La imagen Docker se construye y responde a la sonda de readiness en no más de 30 s; el pipeline completo
  termina en verde en la rama de la historia.
- **SC-008**: Cada propiedad de configuración de Spring usada por el servicio se verifica contra los metadatos de Boot 4:
  0 propiedades marcadas como eliminadas o con reemplazo obligatorio permanecen en `application*.yml`, `application.properties`
  de pruebas o argumentos de pruebas.
- **SC-009**: El ADR nuevo, la actualización de la constitución y la nota de cambios de comportamiento existen y se
  referencian entre sí al cerrar la historia (3 de 3).

## Assumptions

- **S-1 (Q1)**: `resilience4j-spring-boot3` y `spring-retry` se retiran del `pom.xml` porque ninguna clase los usa. Si el
  usuario prefiere conservar Resilience4j para trabajo futuro, la alternativa es `resilience4j-spring-boot4` 2.4.0
  (existe en Maven Central) y se reabre el alcance.
- **S-2 (Q2)**: Jackson 2 solo permanece como transitivo de jjwt-jackson 0.13.0 (última versión publicada) y del SDK de
  Azure; dueño: equipo del servicio, revisión al publicarse una versión de jjwt con Jackson 3 o al cambiar de librería de
  JWT.
- **S-3 (Q3)**: Los pines por CVE (Netty 4.2.19, amqp-client 5.37.0, Jackson 2.22.3, BouncyCastle 1.86, OkHttp 4.12.0) se
  evalúan uno por uno contra el BOM de Boot 4.1.1 (Netty 4.2.17, amqp-client 5.30.0, jackson-2-bom 2.21.5, Jackson 3
  3.1.5); se conservan los que superen al BOM.
- **S-4 (Q4)**: Una sola rama y un solo PR contra `main`; los cambios sin commitear que son prerrequisitos (Java 25,
  Spotless 1.28.0, starter del PEP, `PepDelegation`, ADR-0011, constitución 1.2.1) se commitean primero y por separado,
  decisión del usuario.
- **S-5 (Q5)**: El ADR nuevo es ADR-0012 en `docs/adr/`.
- **S-6 (Q6)**: Los nombres de variables de entorno de despliegue no cambian; solo las propiedades internas de Spring.
- Se usa Spring Boot 4.1.1, la última versión GA disponible en Maven Central a la fecha; el starter del PEP usa 4.1.0 como
  padre y no genera conflicto dentro de la misma línea menor.
- Fuera de alcance: cualquier cambio funcional del servicio, la autenticación contra el componente de seguridad (HU2-055),
  añadir endpoints o métricas nuevas, y migrar a Spring MVC o a hilos virtuales.
- Las pruebas de integración y E2E requieren Docker; las dos de DLQ requieren además un broker en `localhost:5673` y las
  decide el pipeline si localmente fallan por orden de ejecución.
