# Feature Specification: Corregir los hallazgos de la revisión de las specs 011 a 016

**Feature Branch**: `fix/revision-specs-011-016` (por crear)

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "spec numero 017"

Trazabilidad: `specs/017-Fix-specs-012-016/informe.md` (hallazgos A-01 a A-13, M-01 a M-31 y B-01 a B-21)
y la Bitácora de problemas técnicos (filas del 2026-10-02). Principios de la constitución implicados:
II (contrato primero), III (cero comentarios), IV (pruebas), VII (sin atajos) y IX (observabilidad).

Contexto: la revisión independiente de las specs 011, 012 (adjuntos y reintento manual), 013, 014, 015 y 016
encontró defectos de seguridad, pérdida silenciosa de trabajo, sobrecargas sin tope y artefactos que ya no
describen el servicio. Esta especificación define el comportamiento esperado una vez corregidos; no repite
el detalle de cada hallazgo, que vive en el informe.

## Clarifications

### Session 2026-10-02

- Q: ¿El rediseño del despacho (ack manual, envío idempotente, reserva atómica antes de enviar) entra en esta
  spec o se separa? -> A: entra aquí (hallazgos A-03, A-04, A-05, A-13 y M-24 a M-26), porque bloquea
  012-reintentar y 013.
- Q: ¿Se reconcilian ahora los artefactos de las historias aún sin código (012-reintentar y 013)? -> A: sí,
  se actualizan en esta spec.
- Q: ¿Cómo se autentica el panel en vivo, que hoy envía el token en la dirección de la conexión? -> A: con un
  ticket de un solo uso y vida corta, obtenido con una solicitud autenticada normal (recomendación del
  agente, pendiente de confirmación del usuario).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El servicio no puede arrancar ni operar de forma insegura (Priority: P1)

Como responsable de la operación, quiero que el servicio rechace configuraciones y credenciales inseguras,
para que nadie pueda suplantar a un cliente de otro tenant ni a un administrador.

**Why this priority**: hoy cualquiera que conozca el valor público del repositorio puede forjar credenciales
de administrador de cualquier tenant si falta la variable de entorno; es el riesgo más grave del informe.

**Independent Test**: arrancar el servicio sin el secreto de firma y con el secreto de desarrollo, en un
entorno no local, y comprobar que no inicia; presentar credenciales sin caducidad y comprobar el rechazo.

**Acceptance Scenarios**:

1. **Given** un entorno no local sin secreto de firma configurado, **When** el servicio arranca,
   **Then** falla con un mensaje claro y no atiende solicitudes.
2. **Given** un secreto igual al valor de desarrollo publicado en el repositorio, **When** el servicio
   arranca fuera del perfil local, **Then** falla con un mensaje claro.
3. **Given** una credencial firmada sin fecha de caducidad, **When** se presenta, **Then** se rechaza con 401.
4. **Given** un error interno de un caso de uso, **When** se atiende una solicitud autenticada, **Then** la
   respuesta es un error de servidor y no un 401, y el registro no lo clasifica como rechazo de credencial.
5. **Given** una ruta cuyo prefijo coincide con una exención (por ejemplo `/actuatorX`), **When** se
   solicita sin credencial, **Then** se exige autenticación.
6. **Given** el script local de emisión de credenciales, **When** se revisa el repositorio, **Then** no
   contiene el secreto ni se versiona en la rama principal.
7. **Given** un operador autenticado, **When** abre el panel en vivo con un ticket válido, **Then** recibe
   las actualizaciones; con un ticket caducado, ya usado o ausente, recibe 401.

---

### User Story 2 - Ninguna petición acepta trabajo que luego pierde sin dejar rastro (Priority: P1)

Como sistema cliente, quiero que el servicio rechace lo que no puede procesar y registre lo que falla, para
no recibir confirmaciones de trabajo que se pierde o se queda atascado.

**Why this priority**: hay lotes sin tope, guardados fallidos que responden éxito sin traza, cargas de
adjuntos que quedan pendientes para siempre y notificaciones huérfanas que nunca se recuperan.

**Independent Test**: enviar un lote por encima del tope; forzar un fallo de guardado del lote; dejar un
adjunto con tamaño inconsistente; forzar un fallo al encolar una notificación reencolada.

**Acceptance Scenarios**:

1. **Given** un lote con más ítems que el máximo permitido, **When** se envía, **Then** se rechaza con 400
   y un mensaje claro, sin procesar ningún ítem.
2. **Given** un fallo al guardar el registro del lote, **When** se atiende el envío, **Then** el fallo
   queda registrado con su categoría y el cliente recibe la confirmación junto con una señal de que el
   seguimiento del lote no quedó guardado.
3. **Given** un lote reenviado con el mismo identificador, **When** se procesa, **Then** no se reprocesan
   los ítems ya aceptados y el cliente recibe el resultado original.
4. **Given** un objeto de adjunto ausente, de tamaño distinto al declarado o que supera el máximo, **When**
   se escanea, **Then** la carga pasa a un estado terminal de fallo con el motivo registrado.
5. **Given** una carga cuya vigencia venció, **When** se intenta completar, **Then** se rechaza.
6. **Given** una notificación reencolada cuyo encolado falla, **When** pasa el umbral de recuperación,
   **Then** se vuelve a intentar encolar y el fallo queda registrado.
7. **Given** un ítem del lote que falla por una causa interna, **When** se devuelve el resultado, **Then**
   el motivo es genérico y no contiene detalles internos (hosts, colecciones, direcciones).
8. **Given** un ítem de lote sin la lista de adjuntos, **When** se envía, **Then** se trata como sin
   adjuntos o se rechaza con 400, nunca con 500.

---

### User Story 3 - El despacho no duplica ni pierde envíos (Priority: P2)

Como operador, quiero que una reentrega del mismo mensaje no duplique el envío ni contamine la cola de
mensajes muertos, para confiar en el estado de cada notificación.

**Why this priority**: el despacho confirma automáticamente y envía antes de guardar; es la base sobre la
que se construirán el reintento manual y las preferencias.

**Independent Test**: reentregar el mismo mensaje de despacho y forzar un fallo de guardado tras un envío
aceptado.

**Acceptance Scenarios**:

1. **Given** un mensaje de despacho reentregado sobre una notificación ya en proceso o entregada, **When**
   se consume, **Then** se ignora sin error ni paso a la cola de mensajes muertos.
2. **Given** un fallo al guardar tras un envío aceptado por el proveedor, **When** se reentrega, **Then**
   el proveedor no recibe un segundo envío.
3. **Given** un reintento manual de una notificación fallida, **When** se aplica, **Then** el contador de
   intentos sigue la regla definida para el reintento manual.

---

### User Story 4 - Los artefactos de las historias describen el servicio real (Priority: P2)

Como persona que retoma una historia, quiero que la spec, el plan, las tareas y el quickstart coincidan con
el comportamiento actual, para poder ejecutarlos sin sorpresas.

**Why this priority**: las historias 011, 014 y 016 describen una cabecera de tenant que ya no se usa y
tareas de cierre que figuran sin hacer; los pasos manuales del quickstart ya no funcionan.

**Independent Test**: ejecutar cada quickstart actualizado contra el servicio y comprobar que los pasos
funcionan; revisar que no queden menciones de la cabecera de tenant ni tareas de cierre sin estado real.

**Acceptance Scenarios**:

1. **Given** el quickstart de una historia mergeada, **When** se ejecuta paso a paso con credencial, **Then**
   produce los resultados descritos.
2. **Given** los artefactos de una historia mergeada, **When** se revisan, **Then** el estado del plan y las
   tareas de cierre reflejan lo ejecutado, y lo no ejecutado figura como pendiente con motivo.
3. **Given** el reintento manual, **When** se revisa su spec, **Then** declara el rol OPERADOR y exige
   pruebas de 403 para CLIENTE y 202 para OPERADOR.

---

### User Story 5 - El código cumple las reglas del proyecto (Priority: P3)

Como persona que mantiene el código, quiero que no haya comentarios explicativos ni errores genéricos sin
tratar, para cumplir la constitución y recibir respuestas coherentes.

**Why this priority**: baja urgencia pero evita que la deuda crezca y que se repita en cada historia nueva.

**Independent Test**: buscar comentarios en el código de producción; provocar un conflicto de versión, una
clave duplicada y un error inesperado y comprobar la respuesta.

**Acceptance Scenarios**:

1. **Given** el código de producción, **When** se revisa, **Then** no contiene comentarios explicativos.
2. **Given** un conflicto de versión o una clave duplicada, **When** ocurre en una solicitud, **Then** la
   respuesta es 409 con mensaje claro.
3. **Given** un error inesperado, **When** ocurre, **Then** la respuesta es 500 con mensaje genérico e
   identificador de correlación, sin el mensaje interno de la excepción.

---

### Edge Cases

- Un secreto de firma con longitud insuficiente: debe fallar el arranque con un mensaje claro.
- Un lote en el límite exacto del máximo: se acepta; con un ítem más: se rechaza.
- Dos reentregas simultáneas del mismo mensaje: solo una produce un envío.
- Un adjunto cuyo objeto se vuelve a escribir entre la finalización y el escaneo.
- Una historia ya mergeada cuyas tareas de cierre no se pueden ejecutar localmente por falta de Docker.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio MUST negarse a arrancar fuera del perfil local si falta el secreto de firma o si
  coincide con el valor de desarrollo publicado (A-01).
- **FR-002**: El servicio MUST rechazar credenciales sin fecha de caducidad (M-01).
- **FR-003**: Los errores de la cadena de atención posteriores a la autenticación MUST NO clasificarse ni
  responderse como fallos de autenticación (A-02).
- **FR-004**: Las rutas exentas de autenticación MUST coincidir de forma exacta o por segmento completo (M-02).
- **FR-005**: El script de emisión de credenciales MUST NO contener el secreto ni llegar a la rama
  principal (M-29).
- **FR-006**: El envío por lote MUST rechazar con 400 los lotes que superen un máximo de ítems definido en
  el contrato (A-07).
- **FR-007**: El fallo al guardar el registro de un lote MUST registrarse y MUST ser visible para el
  cliente; el reenvío con el mismo identificador MUST NO reprocesar ítems ya aceptados (A-08, A-09).
- **FR-008**: Los motivos de ítems fallidos o rechazados MUST NO exponer detalles internos (M-11).
- **FR-009**: Un ítem de lote MUST validarse por completo antes de procesarse, sin producir 500 por campos
  ausentes (M-12, M-13).
- **FR-010**: El escaneo de adjuntos MUST comparar el tamaño real con el declarado y con el máximo antes de
  leer, y MUST llevar toda carga que no pueda resolverse a un estado terminal de fallo con motivo
  registrado (A-10, A-11).
- **FR-011**: Completar una carga MUST rechazarse si su vigencia venció, y las cargas abandonadas MUST
  limpiarse (M-19, M-20).
- **FR-012**: La recuperación de notificaciones reencoladas MUST registrar los fallos de encolado y MUST
  reintentarlos (A-06, M-24).
- **FR-013**: Los mensajes de despacho reentregados MUST ser idempotentes y el envío al proveedor MUST NO
  repetirse tras un fallo de guardado; el consumidor MUST confirmar el mensaje de forma explícita solo
  después de persistir el resultado, con reintentos acotados y derivación a la cola de mensajes muertos con
  el motivo (A-03, A-04, A-05, M-24 a M-26).
- **FR-014**: La spec del reintento manual MUST definir la regla del contador de intentos y las pruebas por
  rol (A-12, A-13).
- **FR-015**: Los artefactos de las specs 011, 014 y 016 MUST describir la autenticación vigente y reflejar
  el estado real de planes y tareas de cierre (M-14, M-18, M-30); los de 012-reintentar y 013 MUST
  actualizarse también, con roles, autenticación vigente y pruebas por rol.
- **FR-019**: El panel en vivo MUST autenticarse con un ticket de un solo uso y vida corta, solicitado con
  una petición autenticada normal; la credencial principal MUST NOT viajar en la dirección de la conexión
  (M-03). El ticket caducado o ya usado MUST rechazarse con 401.
- **FR-016**: El código de producción MUST NOT contener comentarios explicativos (M-28).
- **FR-017**: Los conflictos de versión, las claves duplicadas y los errores inesperados MUST devolver
  respuestas 409, 409 y 500 genérica con correlación, respectivamente (M-27).
- **FR-018**: Cada corrección MUST incluir una prueba que falle antes del cambio y un control positivo
  cuando la prueba verifique ausencia de algo (Principio IV).

### Key Entities

- **Hallazgo**: problema identificado en la revisión, con identificador, severidad, ubicación y estado.
- **Carga de adjunto**: archivo en espera de escaneo, con tamaño declarado, vigencia y estado terminal.
- **Registro de lote**: seguimiento de un lote enviado, con identificador, ítems y resultado por ítem.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los arranques fuera del perfil local sin secreto válido fallan antes de atender
  la primera solicitud.
- **SC-002**: 0 hallazgos de severidad alta o crítica del informe quedan abiertos al cerrar la historia, o
  cada uno tiene dueño y fecha según el Principio VII.
- **SC-003**: Un lote por encima del máximo se rechaza en menos de 1 segundo sin procesar ningún ítem.
- **SC-004**: 0 cargas de adjuntos permanecen en espera de escaneo más allá de su vigencia.
- **SC-005**: Reentregar 100 veces el mismo mensaje de despacho produce exactamente 1 envío al proveedor.
- **SC-006**: El 100 % de los quickstart de las historias mergeadas se ejecutan sin pasos fallidos.
- **SC-007**: 0 comentarios explicativos en el código de producción.

## Assumptions

- El máximo de ítems por lote se fija en el contrato; su valor se decide al planificar (por defecto, uno
  coherente con el límite de tamaño del cuerpo ya vigente).
- La autenticación interina se mantiene hasta que se cierre DEP-01; esta spec no la reemplaza.
- Las historias 012-reintentar y 013 siguen sin implementarse; esta spec no las construye, solo actualiza
  sus artefactos.
- El ticket del panel en vivo añade una operación al contrato, que se define primero (Principio II); la
  duración exacta del ticket se decide al planificar.
- Los hallazgos de severidad baja que no figuran en los requisitos se registran en el plan como mejoras
  opcionales, sin comprometer fecha.
- Las tareas de cierre que requieren Docker se ejecutan en CI cuando no son ejecutables en local.
