# Feature Specification: Autenticación interina del servicio (puerto y adaptador local, preparación de HU2-055)

**Feature Branch**: `feature/HU2-096-autenticacion-interina`

**Created**: 2026-09-30

**Status**: Clarificado — Q1 y Q2 resueltas con análisis de tradeoffs en `research.md` (2026-09-30)

**Input**: User description: "HU2-096 — Como sistema cliente que llama a la API de Notification-uco,
quiero autenticarme con un token que identifique mi tenant y mi rol, para que nadie pueda suplantar
mi tenantId enviando un header arbitrario como hoy.

Contexto de negocio: el servicio no tiene autenticación ni autorización hoy. El único dato de
identidad es el header `X-Tenant-Id`, leído sin validar en cada controlador REST
(`NotificationController`, `AttachmentUploadController`) — cualquiera puede mandar cualquier tenant.

Por qué esta historia y no la integración real: existe una historia separada, ya planeada pero
bloqueada por una dependencia externa que aún no cierra (equipo de Seguridad de la organización,
que construye una plataforma centralizada con patrón PEP/PDP/OPA — fuera del alcance y del control
de este equipo). Esa historia bloqueada cubre la integración real contra esa plataforma. Esta
historia (HU2-096) es distinta y no está bloqueada: construye el puerto de salida y un filtro que
hoy validan un token emitido y firmado por este mismo servicio (interino), diseñados explícitamente
para que, cuando la dependencia externa se resuelva, solo haga falta reemplazar el adaptador — nunca
el dominio, los casos de uso, ni (idealmente) los controladores."

## Clarifications

### Session 2026-09-30

Barrido de ambigüedades sobre el resto del spec (alcance funcional, modelo de roles, datos, casos
borde, criterios de éxito): sin hallazgos que requieran una pregunta adicional — cada categoría de
la taxonomía de revisión quedó Clara o con un supuesto razonable ya registrado en `Assumptions` o
`Edge Cases`. Las dos únicas ambigüedades de alto impacto detectadas son decisiones de arquitectura
(no de alcance funcional) y se dejaron abiertas a propósito, sin adivinar una respuesta aquí, para
resolverse en `plan.md`/`research.md` con un análisis explícito de tradeoffs:

- Q: ¿Qué mecanismo de firma usa el JWT interino — secreto compartido (HS256) o par de claves
  asimétrico propio del servicio? → A: HS256 con secreto compartido por variable de entorno. Análisis
  completo de tradeoffs en `research.md` (Decisión 1); sujeto, como el resto del plan, a la aprobación
  explícita del usuario antes de `tasks`/`implement` (Principio VI).
- Q: ¿Cómo se generan tokens válidos para desarrollo local y para las pruebas de contrato/E2E, dado
  que no existe todavía un emisor real? → A: una clase Java reutilizable desde el código de producción
  (`LocalJwtTokenIssuer`), instanciada directamente por las pruebas, sin exponer ningún endpoint HTTP
  de emisión. Análisis completo en `research.md` (Decisión 2); misma sujeción a aprobación.

### Session 2026-09-30 (segunda ronda, tras aprobación del plan)

- Q: ¿Se deben registrar en el log los rechazos de autenticación/autorización (`401`/`403`), con datos
  no sensibles, para poder detectar después un ataque o un cliente mal configurado? → A: Sí — cada
  rechazo `401` o `403` se registra con `tenantId` (si pudo extraerse del token; puede ser nulo si el
  token ni siquiera es un JWT sintácticamente válido) y una categoría del motivo (uno de: sin token,
  token sintácticamente inválido, firma inválida, expirado, claims obligatorias faltantes, rol no
  reconocido, rol insuficiente) — nunca el token ni el secreto de firma. Mismo patrón de sanitización y
  mismo punto conceptual (donde se resuelve el resultado del rechazo) que ya usa
  `AttachmentLogFormatter` para aceptación/rechazo de adjuntos.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Rechazar solicitudes sin identidad verificable (Priority: P1)

Un sistema cliente llama a cualquier endpoint protegido de la API sin un token de portador, con un
token mal formado, expirado, con firma inválida, o con alguna de las claims obligatorias (`sub`,
`tenantId`, `role`) ausente o con un valor de `role` fuera del conjunto permitido. El servicio
rechaza la solicitud antes de que llegue al controlador y a cualquier caso de uso de negocio.

**Why this priority**: Es la garantía central de la historia — sin este comportamiento fail-closed,
el resto de la historia (identidad confiable, roles) no tiene valor: cualquiera seguiría pudiendo
suplantar un tenant simplemente omitiendo o falsificando el token.

**Independent Test**: Puede probarse por completo enviando solicitudes a cualquier endpoint protegido
con: (a) sin header `Authorization`, (b) un JWT sintácticamente inválido, (c) un JWT válido pero
expirado, (d) un JWT válido pero firmado con una clave distinta a la del servicio, (e) un JWT
válido y vigente al que le falta la claim `tenantId` o `role`, o cuyo `role` no es uno de los tres
valores permitidos. Todas deben responder `401` y ningún caso de uso de negocio debe ejecutarse.

**Acceptance Scenarios**:

1. **Given** una solicitud a `POST /notifications` sin header `Authorization`, **When** el filtro la
   intercepta, **Then** el servicio responde `401` sin invocar `SendNotificationUseCase`.
2. **Given** una solicitud con `Authorization: Bearer <token-expirado>`, **When** el filtro valida el
   token, **Then** el servicio responde `401` y el cuerpo no revela detalles internos de la causa
   criptográfica del rechazo.
3. **Given** una solicitud con un JWT válido y vigente al que le falta la claim `tenantId`, **When**
   el filtro lo valida, **Then** el servicio responde `401`.
4. **Given** una solicitud con un JWT firmado con una clave distinta a la configurada en el servicio,
   **When** el filtro verifica la firma, **Then** el servicio responde `401`.

---

### User Story 2 - Resolver tenant y rol desde el token, no desde un header arbitrario (Priority: P1)

Un sistema cliente presenta un JWT válido y vigente, emitido para un `tenantId` y un `role`
determinados. El servicio usa exclusivamente esos dos valores —tomados del token ya validado— para
decidir a qué tenant pertenece la operación y qué puede hacer el llamador, sin importar qué envíe (o
deje de enviar) en el header `X-Tenant-Id`, que deja de leerse.

**Why this priority**: Es la promesa explícita de la historia de usuario — nadie puede suplantar un
`tenantId` enviando un header arbitrario. Sin esto, el fail-closed de la Historia 1 protegería el
acceso pero no la identidad que se usa después de entrar.

**Independent Test**: Puede probarse enviando la misma solicitud dos veces con el mismo `Authorization`
válido pero distintos valores (o ausencia) del header `X-Tenant-Id`; el resultado observable (a qué
tenant se atribuye la notificación creada, por ejemplo) debe ser idéntico en ambos casos y
corresponder siempre al `tenantId` del token, nunca al header.

**Acceptance Scenarios**:

1. **Given** un JWT válido con `tenantId=A`, **When** la solicitud además incluye
   `X-Tenant-Id: B` (o no lo incluye), **Then** la notificación se registra bajo el tenant `A` — el
   valor del token, nunca el del header.
2. **Given** un JWT válido con `tenantId=A` y `role=CLIENTE`, **When** el cliente consulta
   `GET /notifications/{id}` de una notificación que pertenece al tenant `A`, **Then** la respuesta
   es exitosa y contiene esa notificación.
3. **Given** un JWT válido con `tenantId=A`, **When** el cliente intenta consultar una notificación
   que pertenece al tenant `B`, **Then** el servicio responde de forma que no revela si la
   notificación existe en otro tenant (comportamiento ya garantizado por el aislamiento multi-tenant
   existente, ahora alimentado por el `tenantId` del token en vez del header).

---

### User Story 3 - Impedir operaciones fuera del rol del llamador (Priority: P2)

Un sistema cliente presenta un JWT válido con un `role` que no alcanza para la operación solicitada
según la jerarquía `ADMINISTRADOR ⊇ OPERADOR ⊇ CLIENTE` (por ejemplo, un token `CLIENTE` intentando
una operación reservada a `OPERADOR` o `ADMINISTRADOR`, en los endpoints donde esa operación ya
existe expuesta). El servicio rechaza la solicitud con un código distinto al de "no autenticado".

**Why this priority**: Depende de que las Historias 1 y 2 ya resuelvan la identidad; sin autorización
por rol, cualquier cliente autenticado podría ejecutar cualquier operación, lo cual no cumple el
modelo de roles jerárquico que la historia exige.

**Independent Test**: Puede probarse generando dos tokens válidos para el mismo tenant con roles
distintos y comparando la respuesta a la misma operación protegida por rol: el rol insuficiente debe
recibir `403` y el rol suficiente debe recibir el resultado normal de la operación.

**Acceptance Scenarios**:

1. **Given** un JWT válido con `role=CLIENTE`, **When** el cliente intenta una operación reservada a
   `OPERADOR` o superior en un endpoint donde esa operación ya está expuesta, **Then** el servicio
   responde `403` y no ejecuta la operación.
2. **Given** un JWT válido con `role=OPERADOR`, **When** el cliente ejecuta una operación permitida a
   `CLIENTE` u `OPERADOR`, **Then** el servicio la ejecuta con normalidad.
3. **Given** un JWT válido con `role=ADMINISTRADOR`, **When** el cliente ejecuta cualquier operación
   cubierta por el mapeo de roles de esta historia, **Then** el servicio la ejecuta con normalidad
   (el rol más alto nunca es rechazado por insuficiencia de rol).

---

### Edge Cases

- ¿Qué pasa si el JWT tiene una claim `role` con un valor que no es ninguno de los tres permitidos
  (por ejemplo, un typo o un rol futuro no contemplado)? → Se trata igual que una claim faltante:
  rechazo `401` (Historia 1), no `403`, porque el servicio no pudo establecer una identidad válida,
  no que la identidad establecida carezca de permisos.
- ¿Qué pasa con `GET /notifications:subscribe` (SSE), cuyo `EventSource` nativo del navegador no
  puede enviar el header `Authorization`? → Limitación conocida, documentada como tal en esta
  historia; la solución concreta (token como parámetro de consulta u otro mecanismo, con sus
  implicaciones de seguridad) se decide y se registra en `plan.md`, no se asume aquí.
- ¿Qué pasa si un endpoint de la API pública no tiene todavía una operación de negocio a la que
  aplicar el mapeo de roles de esta historia (por ejemplo, reintento manual, gestión de preferencias
  del destinatario, o registro de canal/proveedor, ya documentados en el contrato OpenAPI pero sin
  controlador REST implementado todavía)? → El mapeo de rol para esa operación queda definido en esta
  historia, pero su aplicación queda diferida hasta que exista el endpoint correspondiente; no se
  crea un endpoint nuevo solo para aplicar el rol.
- ¿Qué pasa con un sistema cliente `OPERADOR` que necesita consultar notificaciones de un tenant que
  no es el suyo (capacidad "consultar estado de cualquier tenant")? → El endpoint de histórico
  (`GET /notifications`) hoy resuelve el tenant consultado 1:1 con el tenant de la identidad de la
  solicitud (antes el header, ahora el token). Esta historia define el permiso de rol (`OPERADOR`
  puede consultar cualquier tenant), pero el mecanismo para que el endpoint acepte un tenant objetivo
  distinto al propio del llamador no existe todavía; queda como una extensión diferida del endpoint,
  fuera del alcance de esta historia, y se documenta como tal.
- ¿Qué pasa con reloj desincronizado entre el emisor del token y este servicio (token con `exp` en el
  pasado por pocos segundos de diferencia de reloj)? → Se aplica el criterio estándar de expiración
  sin margen de tolerancia adicional para este paso interino; no se introduce lógica de tolerancia de
  reloj (`clock skew`) salvo que el plan la justifique explícitamente.
- ¿Qué pasa si el mismo token se reutiliza después de revocarse (por ejemplo, un cliente
  desactivado)? → Fuera de alcance: este paso interino no incluye una lista de revocación; el único
  mecanismo de expiración de un token es su claim `exp`. Se documenta como limitación conocida, no
  como pendiente a resolver en esta historia.
- ¿Qué pasa si el token presentado ni siquiera es un JWT sintácticamente válido (no se puede
  decodificar lo suficiente para leer `tenantId`)? → El registro del rechazo (FR-015) se hace igual,
  con `tenantId` nulo y la categoría "token sintácticamente inválido"; el rechazo `401` no depende de
  poder identificar el tenant.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema DEBE interceptar toda solicitud a un endpoint protegido de la API pública
  antes de que llegue a cualquier controlador REST, y resolver la identidad del llamador (tenant y
  rol) a partir de un token de portador JWT presentado en el header `Authorization`.
- **FR-002**: El sistema DEBE rechazar con `401` toda solicitud a un endpoint protegido que no incluya
  el header `Authorization`, o cuyo valor no sea un JWT sintácticamente válido.
- **FR-003**: El sistema DEBE rechazar con `401` todo JWT cuya firma no pueda verificarse con la clave
  o secreto configurado en este servicio.
- **FR-004**: El sistema DEBE rechazar con `401` todo JWT expirado (claim `exp` en el pasado en el
  momento de la validación).
- **FR-005**: El sistema DEBE rechazar con `401` todo JWT al que le falte alguna de las claims
  obligatorias (`sub`, `tenantId`, `role`) o cuyo `role` no sea exactamente uno de
  `ADMINISTRADOR`, `OPERADOR`, `CLIENTE`.
- **FR-006**: El sistema DEBE resolver el `tenantId` de cada solicitud exclusivamente a partir de la
  claim `tenantId` del token ya validado. El header `X-Tenant-Id` DEJA de leerse por cualquier
  controlador REST de la API pública.
- **FR-007**: El sistema DEBE resolver el rol del llamador exclusivamente a partir de la claim `role`
  del token ya validado, y DEBE aplicar el modelo jerárquico `ADMINISTRADOR ⊇ OPERADOR ⊇ CLIENTE` al
  autorizar cada operación (un rol superior siempre satisface el requisito de un rol inferior).
- **FR-008**: El sistema DEBE rechazar con `403` toda solicitud cuyo token sea válido pero cuyo rol no
  alcance el mínimo requerido por la operación solicitada, únicamente en los endpoints donde esa
  operación ya está expuesta.
- **FR-009**: El sistema DEBE aplicar como mínimo el siguiente mapeo de rol por operación, para las
  operaciones que ya tienen un endpoint REST expuesto en el repositorio:
  - `CLIENTE`: enviar notificación individual (`POST /notifications`), enviar lote
    (`POST /notifications:sendBatch`), consultar el estado de una notificación puntual propia
    (`GET /notifications/{id}`), el ciclo de subida de adjuntos que acompaña al envío
    (`POST /attachment-uploads`, `POST /attachment-uploads/{uploadId}:complete`,
    `GET /attachment-uploads/{uploadId}`), suscribirse a actualizaciones en vivo de sus propias
    notificaciones (`GET /notifications:subscribe`), y consultar el catálogo de canales y proveedores
    disponibles para construir una solicitud válida (`GET /channels`, `GET /providers` — operación de
    solo lectura, sin mapeo de rol explícito en el encargo original de esta historia; se asigna el rol
    mínimo autenticado por no ser una operación sensible).
  - `OPERADOR`: todo lo de `CLIENTE`, más consultar histórico/trazabilidad por filtros combinables
    (`GET /notifications` — operación distinta de `GET /notifications/{id}`, reservada a `OPERADOR` o
    superior porque expone el recorrido completo de intentos y permite buscar más allá de una
    notificación puntual).
  - `ADMINISTRADOR`: todo lo de `OPERADOR` (no hay operación adicional con endpoint expuesto hoy que
    requiera exclusivamente `ADMINISTRADOR`).
- **FR-010**: El sistema DEBE dejar definido, aunque sin endpoint que lo aplique todavía, el mapeo de
  rol para las operaciones documentadas en el contrato OpenAPI pero sin controlador REST implementado:
  reintentar envío manual (`OPERADOR`), gestionar preferencias del destinatario (`CLIENTE`), registrar
  canal y registrar proveedor (`ADMINISTRADOR`). Este requisito se satisface documentando el mapeo;
  no exige crear los controladores correspondientes.
- **FR-011**: El contrato OpenAPI del servicio DEBE actualizarse antes que cualquier cambio de código
  para reflejar `Authorization: Bearer <JWT>` como mecanismo de autenticación de cada operación
  protegida, en reemplazo del header `X-Tenant-Id`, incluyendo las respuestas `401` y `403` donde
  aplique.
- **FR-012**: El sistema DEBE ofrecer un mecanismo, utilizable sin depender de infraestructura externa,
  para generar tokens JWT válidos que las pruebas automatizadas (contrato y E2E) puedan usar para
  simular distintos tenants y roles, sin exponer un endpoint HTTP de emisión (ver `research.md`,
  Decisión 2).
- **FR-013**: El sistema NO DEBE dejar pasar por defecto ninguna solicitud a un endpoint protegido
  cuando la validación del token falle por cualquier motivo no contemplado explícitamente arriba
  (fail-closed como comportamiento general, no solo para los casos enumerados).
- **FR-014**: El sistema DEBE tratar la limitación del endpoint `GET /notifications:subscribe` (SSE)
  para transportar el token como una condición documentada de esta historia, con la solución concreta
  decidida en el plan.
- **FR-015**: El sistema DEBE registrar cada rechazo `401` o `403` con el `tenantId` (si pudo
  extraerse del token; nulo si el token ni siquiera es un JWT sintácticamente válido) y una categoría
  del motivo del rechazo (sin token, token sintácticamente inválido, firma inválida, expirado, claims
  obligatorias faltantes, rol no reconocido, rol insuficiente). El registro NO DEBE incluir el token
  ni el secreto de firma en ningún caso.

### Key Entities

- **Token de acceso (JWT interino)**: representa la identidad de un sistema cliente ante la API.
  Contiene, como mínimo, `sub` (identificador del sistema cliente emisor de la solicitud), `tenantId`
  (tenant al que pertenecen las operaciones del llamador) y `role` (uno de `ADMINISTRADOR`,
  `OPERADOR`, `CLIENTE`). Tiene una fecha de expiración (`exp`) y una firma verificable localmente por
  este servicio.
- **Identidad resuelta de la solicitud**: el resultado de validar un token — tenant y rol ya
  verificados — que el filtro pone a disposición de los controladores para que dejen de leer
  `X-Tenant-Id` a mano.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de las solicitudes a endpoints protegidos sin un token válido (ausente,
  inválido, expirado, mal formado o con claims incompletas) son rechazadas con `401`; ninguna llega a
  ejecutar un caso de uso de negocio.
- **SC-002**: El 100% de las solicitudes con un token válido pero con rol insuficiente para la
  operación solicitada son rechazadas con `403`, en los endpoints donde el mapeo de roles ya aplica.
- **SC-003**: El `tenantId` bajo el que se registra o consulta cualquier notificación corresponde
  siempre al del token presentado, independientemente de cualquier valor enviado en el header
  `X-Tenant-Id`, verificado con dos tenants distintos en la misma suite de pruebas.
- **SC-004**: La validación de un token añade una sobrecarga despreciable frente al presupuesto de
  aceptación existente — el servicio sigue aceptando una notificación individual en 200ms p95 o menos,
  medido de extremo a extremo incluyendo la validación del token.
- **SC-005**: Un desarrollador o una suite de pruebas automatizada puede obtener un token válido para
  cualquier combinación de tenant y rol sin depender de un servicio externo ni de credenciales reales.
- **SC-006**: El 100% de los rechazos `401`/`403` queda reflejado en el log con `tenantId` (o nulo) y
  una categoría de motivo reconocible, verificado en pruebas automatizadas que confirman además que
  ningún log generado por esta historia contiene el valor crudo del token ni el secreto de firma.

## Assumptions

- El adaptador que emite tokens de producción reales (integración con la plataforma de Seguridad
  externa, patrón PEP/PDP/OPA) no es parte de esta historia; esta historia entrega el puerto de salida
  y un adaptador interino que valida tokens firmados por este mismo servicio.
- El filtro reactivo no constituye un caso de uso de negocio y por tanto no tiene puerto de entrada
  propio; es infraestructura transversal que consume el nuevo puerto de salida `TokenValidationPort`.
- Ningún caso de uso existente en `core` cambia su contrato de negocio; los controladores REST pasan a
  suministrarles tenant y rol ya resueltos por el filtro, en vez de leerlos de un header sin validar.
- El aislamiento multi-tenant de datos ya existente (particionado por tenant en Mongo/MinIO, unicidad
  de `(tenantId, externalId)`, etc.) no se modifica; esta historia agrega una capa de autenticación y
  autorización de acceso a la API, anterior y distinta a ese aislamiento.
- Los endpoints documentados en el contrato OpenAPI que todavía no tienen controlador REST (reintento
  manual, preferencias del destinatario, registro de canal/proveedor) quedan con su mapeo de rol
  definido pero sin aplicación observable hasta que esos controladores existan; no se crean en esta
  historia.
- La integración real contra la plataforma de Seguridad externa (PEP/PDP/OPA) sigue bloqueada por una
  dependencia externa y es una historia distinta, fuera de alcance aquí.
- Esta historia no construye un endpoint HTTP público de emisión de tokens (no hay una operación tipo
  `POST /auth/token` en el contrato). Emitir tokens reales para sistemas clientes durante el período
  interino es una actividad operativa fuera de banda (por ejemplo, ejecutar localmente el mecanismo de
  generación de tokens como una utilidad, sin exponerlo por red), consistente con que la decisión
  arquitectónica original de esta historia solo menciona el filtro y el puerto de validación, nunca un
  puerto o endpoint de emisión.
- La lectura del catálogo (`GET /channels`, `GET /providers`) no tenía un rol asignado explícitamente
  en el encargo original de esta historia; se asume el rol mínimo autenticado (`CLIENTE`) por ser una
  operación de solo lectura sin datos sensibles, necesaria para construir una solicitud de envío
  válida.
