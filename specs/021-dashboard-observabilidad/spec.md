# Feature Specification: Dashboard de observabilidad con Prometheus y Grafana (021)

**Feature Branch**: `feature/021-dashboard-observabilidad`

**Created**: 2026-10-08

**Status**: Borrador; preguntas abiertas pendientes de respuesta del usuario (ver Clarifications)

**Input**: User description: "Dashboard de observabilidad con Prometheus y Grafana para la demo y para
demostrar los RNF. La spec 020 ya expone /actuator/prometheus en el puerto de gestión 8061 (solo health y
prometheus); el docker-compose.yml solo tiene mongodb, rabbitmq, minio y clamav; no existe ningún
Prometheus ni Grafana en el repo."

Trazabilidad: RNF-10 (métricas técnicas y de negocio expuestas y consultables), RNF-02 (p95 de aceptación
<= 200 ms), RNF-03 (>= 500 notificaciones por minuto por réplica), RNF-01 (disponibilidad >= 99,5 %
mensual), Principio IX (observabilidad), Principio IV (E2E bloqueante) y Principio VII (sin soluciones
temporales). Esta historia no tiene identificador HU2 en el backlog; se identifica por su número de spec.

Contexto verificado en el repositorio (2026-10-08): la spec 020 registra `notification.accepted`,
`notification.attempts`, `notification.dispatched`, `notification.provider.duration` y
`notification.errors` en `MicrometerNotificationMetrics`, activa el histograma de
`http.server.requests` y de `spring.rabbitmq.listener`, y expone solo `health` y `prometheus` en el
puerto 8061 (`management.endpoints.web.exposure.include`). Hoy nadie consume ese endpoint: no hay scraper,
no hay tablero y las métricas solo se pueden leer a mano.

## Clarifications

### Session 2026-10-08

Preguntas abiertas: el autor no puede preguntar a mitad de ejecución; se devuelven al usuario con una
recomendación. Hasta que respondan, el plan las trata como PROVISIONALES (marcadas así) y se ajusta
una vez confirmadas.

- Q1 (PENDIENTE): alertas por notificaciones FAILED. Recomendación: dentro de alcance solo como reglas de
  alerta de Prometheus versionadas (`observability/alerts.yml`), evaluadas y visibles en la pestaña de
  alertas de Prometheus y en el panel de Grafana, sin destino externo (sin Alertmanager ni correo/chat),
  porque no hay canal de notificación definido y añadirlo sería una solución a medias (Principio VII). El
  envío a un destino queda como excepción documentada con dueño y fecha.
- Q2 (PENDIENTE): retención de Prometheus. Recomendación: 31 días, el mínimo que permite mostrar el
  ventana mensual de RNF-01; es configuración versionada del compose, con volumen persistente.
- Q3 (PENDIENTE): forma del gate E2E. Recomendación: prueba E2E del servicio completo que (a) genera
  tráfico que produce todas las series de negocio, (b) lee el JSON del dashboard y las reglas, (c) verifica
  que cada nombre de métrica y cada nombre de etiqueta usados existen en la respuesta real de
  `/actuator/prometheus`, y (d) evalúa cada consulta contra un Prometheus real en contenedor que raspa el
  servicio de la prueba, para detectar consultas inválidas.
- Q4 (PENDIENTE): medición de RNF-03 sin prueba de carga. Recomendación: panel de tasa por minuto y por
  réplica con línea de umbral fija en 500; declarar en la spec que la prueba de carga que demuestra el
  umbral queda fuera de esta historia, registrada como excepción del Principio VII con dueño y fecha
  (fecha y dueño los fija el usuario).
- Q5 (PENDIENTE): imágenes. Recomendación: `prom/prometheus:v3.5.1` y `grafana/grafana:12.2.0`, ambas
  con etiqueta fija (existencia de las etiquetas verificada con `docker manifest inspect` el 2026-10-08;
  la compatibilidad con el dashboard se verifica en la implementación).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Levantar el tablero y verlo con datos en la demo (Priority: P1)

Un presentador del proyecto levanta el stack de observabilidad con un solo comando, con el servicio
corriendo fuera de Docker, abre Grafana, inicia sesión con credenciales que él mismo definió y ve el
tablero ya cargado, con datos del servicio, sin importar ni configurar nada a mano.

**Why this priority**: sin esto no hay demo ni evidencia; todo lo demás cuelga de que el tablero exista y
reciba datos.

**Independent Test**: con el servicio arriba y credenciales definidas, levantar el perfil de
observabilidad, enviar notificaciones y comprobar que Prometheus marca el servicio como accesible y que el
tablero muestra series en sus paneles.

**Acceptance Scenarios**:

1. **Given** las dependencias locales arriba y credenciales de Grafana definidas por el usuario, **When** se
   levanta el perfil de observabilidad, **Then** Prometheus y Grafana arrancan, Prometheus raspa el puerto
   de gestión del servicio y Grafana tiene la fuente de datos y el tablero ya aprovisionados.
2. **Given** el stack arriba y el servicio procesando notificaciones, **When** el presentador abre el
   tablero, **Then** ve al menos un valor en cada panel de negocio y técnico en menos de 30 segundos tras
   el primer ciclo de raspado.
3. **Given** que no se definieron las credenciales de Grafana, **When** se intenta levantar el perfil de
   observabilidad, **Then** el arranque falla con un mensaje que indica qué falta definir, y levantar solo
   `mongodb` y `rabbitmq` sigue funcionando sin definirlas.
4. **Given** el stack arriba, **When** se inspecciona qué puertos se publican, **Then** el puerto de
   gestión 8061 del servicio no se publica en el compose y las interfaces de Prometheus y Grafana solo
   escuchan en el equipo local.

---

### User Story 2 - Demostrar los RNF en el tablero (Priority: P1)

Un evaluador o el equipo mira el tablero y contrasta, panel por panel, cada requisito no funcional con su
medición actual y su umbral: latencia de aceptación (RNF-02), tasa por réplica (RNF-03), disponibilidad
(RNF-01) y las métricas técnicas y de negocio expuestas (RNF-10).

**Why this priority**: es el propósito declarado de la historia: demostrar los RNF, no solo graficar
métricas.

**Independent Test**: con tráfico enviado, cada panel de RNF muestra su valor, su umbral dibujado y el
estado cumple/no cumple por color; los paneles de negocio desglosan por canal, proveedor, resultado,
código de error y categoría de fallo.

**Acceptance Scenarios**:

1. **Given** tráfico de aceptación sobre `POST /notifications`, **When** se mira el panel de RNF-02,
   **Then** muestra el percentil 95 de la latencia de aceptación con el umbral de 200 ms y colorea en rojo
   cuando lo supera.
2. **Given** tráfico sostenido, **When** se mira el panel de RNF-03, **Then** muestra notificaciones por
   minuto y por réplica, con el umbral de 500 por minuto, de forma que cada réplica se distingue de las
   demás.
3. **Given** el servicio caído un tramo de tiempo, **When** se mira el panel de RNF-01, **Then** la
   disponibilidad calculada sobre la ventana elegida baja y se compara con el 99,5 %; la lectura mensual
   completa solo es posible si Prometheus conserva el histórico del mes.
4. **Given** notificaciones despachadas con resultados distintos, **When** se miran los paneles de
   negocio, **Then** se distinguen aceptadas, intentos, despachos por resultado, duración del proveedor y
   errores por código y categoría de fallo, por canal y proveedor.
5. **Given** el tablero completo, **When** se revisan todas las consultas y etiquetas, **Then** ninguna usa
   `tenantId`, `notificationId`, `correlationId` ni dato del destinatario o del contenido.

---

### User Story 3 - Garantizar que el tablero no se desincroniza del servicio (Priority: P2)

Quien cambie una métrica o su nombre en el servicio se entera en la build, no en plena demo, de que el
tablero o las reglas quedaron apuntando a una serie inexistente o con una consulta inválida.

**Why this priority**: un tablero versionado que referencia métricas que ya no existen es exactamente un
panel vacío en la demo; es la puerta E2E de esta historia (Principio IV).

**Independent Test**: renombrar una métrica usada por el tablero y comprobar que la prueba falla; restaurar
y comprobar que pasa.

**Acceptance Scenarios**:

1. **Given** el tablero y las reglas versionados, **When** corre la prueba E2E, **Then** cada nombre de
   métrica y cada etiqueta que usan existe en la respuesta real de `/actuator/prometheus` tras generar
   tráfico que produce todas las series de negocio.
2. **Given** una consulta del tablero o de las reglas con sintaxis inválida, **When** corre la prueba,
   **Then** falla indicando el panel o la regla.
3. **Given** una consulta o etiqueta que incluya `tenantId` o datos del destinatario, **When** corre la
   prueba, **Then** falla.
4. **Given** el control positivo (el mismo escaneo sobre un tablero con una métrica inexistente), **When**
   corre la prueba, **Then** falla; sin control positivo la prueba no cuenta.

---

### User Story 4 - Alertas por notificaciones fallidas (Priority: P3)

El equipo ve en Prometheus y en el tablero cuándo la tasa de notificaciones que terminan en FAILED supera
un umbral.

**Why this priority**: valor operativo adicional; su alcance depende de la respuesta a Q1, por eso no es
parte del MVP.

**Independent Test**: provocar despachos fallidos con el proveedor simulado y comprobar que la regla pasa a
estado activo y desaparece al cesar.

**Acceptance Scenarios**:

1. **Given** despachos con resultado `failed` por encima del umbral durante la ventana definida, **When** se
   evalúa la regla, **Then** la alerta pasa a activa y el tablero la muestra.
2. **Given** que cesan los fallos, **When** pasa la ventana, **Then** la alerta se resuelve.

---

### Edge Cases

- El servicio no corre o el 8061 no es alcanzable: Prometheus marca el objetivo como caído, el tablero lo
  muestra de forma explícita y los demás paneles se ven sin datos, no con ceros.
- Series de negocio que aún no existen (un contador solo aparece tras su primer incremento): los paneles
  deben mostrar "sin datos" y no un error ni un cero engañoso; la prueba E2E genera tráfico para cada serie.
- Linux sin `host.docker.internal`: el compose debe resolver el anfitrión de forma explícita.
- Varias réplicas: los paneles de RNF-03 y de disponibilidad separan por instancia.
- La etiqueta `result` del contrato de la 020 enumera `discarded`, pero el código actual solo emite
  `delivered`, `recoverable` y `failed`; los paneles no deben depender de `discarded` (ver Brechas en el
  plan).
- El histograma de latencia tiene fronteras de cubo fijas: el percentil 95 es una aproximación por
  interpolación, no un valor exacto; el panel lo declara.
- Cardinalidad: el tablero solo usa etiquetas de catálogo cerrado.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST incluir la configuración de un Prometheus que raspa el puerto de gestión
  del servicio en el anfitrión, con intervalo de raspado y retención versionados.
- **FR-002**: El repositorio MUST incluir la fuente de datos de Grafana y el tablero como archivos
  versionados que Grafana aprovisiona al arrancar, sin pasos manuales.
- **FR-003**: El `docker-compose.yml` MUST añadir Prometheus y Grafana bajo un perfil `observability`, con
  imágenes de etiqueta fija (nunca `latest`), de modo que levantar los servicios existentes sin el perfil
  no cambie.
- **FR-004**: Las credenciales de Grafana MUST no tener valor por defecto en el repositorio, MUST
  inyectarse desde el entorno del usuario, y su ausencia MUST impedir arrancar Grafana con un mensaje claro
  sin impedir arrancar el resto de servicios.
- **FR-005**: El puerto de gestión 8061 del servicio MUST NOT publicarse desde el compose; las interfaces
  de Prometheus y Grafana MUST escuchar solo en el equipo local.
- **FR-006**: El tablero MUST mostrar las métricas de negocio `notification_accepted_total`,
  `notification_attempts_total`, `notification_dispatched_total`,
  `notification_provider_duration_seconds` y `notification_errors_total`, desglosadas por las etiquetas que
  realmente emite el servicio (`channel`, `provider`, `result`, `errorCode`, `failureCategory`).
- **FR-007**: El tablero MUST mostrar las métricas técnicas: latencia con percentiles, rendimiento y tasa de
  error de `http.server.requests`, consumo del listener de RabbitMQ y JVM (memoria, hilos).
- **FR-008**: El tablero MUST incluir un panel por RNF con su umbral dibujado: RNF-02 (p95 de aceptación
  frente a 200 ms), RNF-03 (notificaciones por minuto por réplica frente a 500), RNF-01 (disponibilidad
  frente a 99,5 %) y RNF-10 (series de negocio y técnicas presentes).
- **FR-009**: Ninguna consulta, etiqueta, variable ni leyenda del tablero ni de las reglas MUST usar
  `tenantId`, `notificationId`, `correlationId`, ni datos del destinatario o del contenido.
- **FR-010**: Una prueba E2E bloqueante en `verify` MUST comprobar que cada métrica y cada etiqueta
  referenciada por el tablero y las reglas existe en la salida real de `/actuator/prometheus` tras generar
  tráfico que produce todas las series, y que ninguna referencia nombres prohibidos por FR-009 (forma
  exacta sujeta a Q3).
- **FR-011**: La misma prueba MUST incluir un control positivo que demuestre que una referencia inexistente
  la hace fallar.
- **FR-012**: El README MUST documentar cómo definir las credenciales, levantar el perfil, abrir el
  tablero, el supuesto de que el servicio corre fuera de Docker y la lectura de cada panel de RNF, incluida
  la limitación de RNF-03 (sujeto a Q4) y de RNF-01.
- **FR-013**: Lo que el tablero no puede demostrar (prueba de carga de RNF-03, histórico mensual de RNF-01,
  destino de las alertas) MUST quedar registrado como excepción del Principio VII con dueño y fecha, no
  como un panel que aparente demostrarlo.
- **FR-014**: (sujeto a Q1) Reglas de alerta de Prometheus versionadas para despachos con resultado
  `failed`, evaluadas por Prometheus y visibles en su interfaz.
- **FR-015**: Los datos de Prometheus y Grafana MUST persistir en volúmenes nombrados que sobreviven a
  reiniciar los contenedores.

### Key Entities

- **Configuración de raspado**: lista de objetivos, intervalo y retención de Prometheus.
- **Fuente de datos aprovisionada**: conexión de Grafana a Prometheus con identificador estable.
- **Tablero**: conjunto versionado de paneles y consultas; cada panel referencia métricas del servicio por
  nombre y etiqueta.
- **Regla de alerta** (sujeta a Q1): condición sobre una métrica de negocio con ventana y umbral.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con credenciales definidas y el servicio arriba, un presentador pasa de cero a ver el tablero
  con datos con un solo comando de arranque y sin ninguna configuración manual en Grafana.
- **SC-002**: El 100 % de las métricas y etiquetas referenciadas por el tablero y las reglas existen en la
  salida real del servicio; verificado automáticamente en cada build.
- **SC-003**: 0 consultas, etiquetas o variables del tablero usan `tenantId`, identificadores de
  notificación o correlación, o datos del destinatario; verificado automáticamente.
- **SC-004**: Cada uno de los RNF-01, RNF-02, RNF-03 y RNF-10 tiene un panel con su umbral visible, y el
  README explica qué demuestra y qué no.
- **SC-005**: Levantar `mongodb` y `rabbitmq` sin definir credenciales de Grafana sigue funcionando
  (verificado con la configuración del compose, no a mano).
- **SC-006**: Ninguna imagen del compose nueva usa `latest`; verificado automáticamente.

## Assumptions

- El servicio corre fuera de Docker (con `spring-boot:run` o el `Dockerfile`) y publica el 8061 solo en el
  equipo local; Prometheus lo alcanza por el nombre del anfitrión de Docker.
- Es una herramienta de demostración y verificación local; el despliegue en el clúster usa la plataforma
  de monitoreo del entorno, fuera de esta historia.
- No se añaden métricas nuevas al servicio ni se modifica el contrato de la 020; si el tablero necesita una
  métrica que no existe, se registra como brecha en lugar de inventarse.
- La prueba de carga que demuestre RNF-03 y un Alertmanager con destino externo no forman parte de esta
  historia (ver Q1 y Q4); quedan como excepciones documentadas.
- El esquema de autenticación del servicio no cambia: el endpoint de gestión no tiene autenticación y se
  protege por red (decisión de la 020).
