# Feature Specification: Prueba de conexion del proveedor (HU2-114)

**Feature Branch**: `feature/HU2-114-prueba-conexion-proveedor`
**Created**: 2026-10-24
**Status**: Draft

## Contexto

El catalogo ya declara `simulated` como segundo proveedor de cada canal (email: `simulated,brevo`; sms: `simulated,twilio`;
push: `simulated,fcm`). Hoy un AP solo descubre que una credencial esta mal cuando falla un envio real. Esta historia anade
una sonda por adaptador que valida credenciales y alcance de red sin enviar ningun mensaje. La pantalla "Probar" vive en el
repo del front y solo consume el contrato descrito en [plan.md](plan.md#contrato-para-la-ui).

## User Scenarios & Testing

### User Story 1 - Probar las credenciales de un proveedor (Priority: P1)

Un ADMINISTRADOR elige un canal y un proveedor del catalogo, pulsa "Probar" y ve si responde, con la latencia medida, o el
motivo del fallo.

**Independent Test**: contra un doble HTTP del proveedor, la sonda devuelve OK con latencia, o FAILED con un codigo cerrado.

**Acceptance Scenarios**:

1. **Given** un proveedor habilitado con credenciales validas, **When** el ADMINISTRADOR lo prueba, **Then** recibe `OK` y
   `latencyMs` medido en servidor, y no se envia ni se crea ninguna notificacion.
2. **Given** credenciales rechazadas, red caida o respuesta inesperada, **When** se prueba, **Then** recibe `FAILED`, `latencyMs`
   y un `errorCode` de un conjunto cerrado; la peticion HTTP sigue siendo 200 porque el fallo es el resultado de la prueba.
3. **Given** un proveedor sin credencial requerida (deshabilitado), **When** se prueba, **Then** `FAILED` con
   `CREDENTIALS_MISSING` sin llamada de red.
4. **Given** una sonda que no responde, **When** pasa el limite de tiempo, **Then** `FAILED` con `TIMEOUT` (limite
   configurable, por defecto 5 s).

### User Story 2 - Probar sin segundo proveedor real (Priority: P1)

Un canal con `simulated` y un proveedor real habilitados puede probarse completo sin esperar a un segundo proveedor real.

**Acceptance Scenarios**:

1. **Given** `simulated` en el catalogo del canal, **When** se prueba, **Then** responde sin red segun
   `SIMULATED_PROVIDER_RESULT` (`ACCEPTED` -> `OK`; cualquier otro valor -> `FAILED` con `SIMULATED_FAILURE`).
2. **Given** el perfil de pruebas (con `simulated` habilitado), **When** se listan los proveedores de cada canal, **Then** cada
   canal tiene al menos dos proveedores y todos son probables (verificado por prueba automatizada).

### User Story 3 - Ningun secreto sale al navegador (Priority: P1)

**Acceptance Scenarios**:

1. **Given** cualquier resultado de prueba, **When** se inspeccionan respuesta, cabeceras y logs, **Then** no contienen apiKey,
   authToken, clave privada ni cuerpo crudo del proveedor, tampoco cuando el proveedor devuelve la credencial en su error.
2. **Given** un rol distinto de ADMINISTRADOR (CLIENTE u OPERADOR), **When** intenta probar, **Then** 403 y no se llama al
   proveedor.

### Edge Cases

- `providerId` o `channel` desconocidos, o proveedor que no pertenece al canal: 400 `VALIDATION_ERROR`, sin sonda.
- Proveedor nombrado en el catalogo sin adaptador (`MISSING_ADAPTER`): 200 `FAILED` con `ADAPTER_MISSING`.
- Segunda prueba del mismo par (canal, proveedor) mientras otra sigue en vuelo en la misma replica: 429. El limite es por replica; uno
  global queda como mejora.
- `simulated` hoy esta por defecto en los tres canales y su adaptador no tiene perfil; restringirlo es la dependencia D-001.

## Requirements

### Functional Requirements

- **FR-001**: Cada adaptador de proveedor expone una sonda inocua sin efecto de negocio: Brevo consulta la cuenta, Twilio
  consulta la cuenta, FCM obtiene un token de acceso, `simulated` responde localmente.
- **FR-002**: La sonda nunca envia ni persiste una notificacion, ni consume cuota de envio.
- **FR-003**: Solo ADMINISTRADOR puede ejecutar la prueba; el rol se resuelve de la sesion del PDP.
- **FR-004**: El resultado contiene `providerId`, `channel`, `status` (`OK`|`FAILED`), `latencyMs` y, si falla, `errorCode`
  cerrado: `CREDENTIALS_MISSING`, `AUTH_REJECTED`, `UNREACHABLE`, `TIMEOUT`, `PROVIDER_ERROR`, `ADAPTER_MISSING`,
  `SIMULATED_FAILURE`.
- **FR-005**: Ningun secreto ni cuerpo crudo del proveedor aparece en respuesta, cabeceras, logs ni en las etiquetas de la
  metrica `notification.provider.probe` (etiquetas `providerId` y `status`; verificado por prueba).
- **FR-006**: La latencia se mide en el servidor desde el inicio de la sonda hasta su resultado, y la sonda tiene un tiempo
  limite.
- **FR-007**: Cada prueba queda en log estructurado con `correlationId`, `providerId`, `channel`, `status`, `errorCode`,
  `latencyMs` y la identidad que la pidio; sin datos personales ni secretos.
- **FR-008**: El endpoint se define primero en el contrato OpenAPI (a cargo de Contrato) con ruta literal.
- **FR-009**: La prueba no usa datos de ningun tenant; el tenant de la sesion solo se registra en el log.

### Key Entities

- **ProviderProbeResult**: `providerId`, `channel`, `status`, `latencyMs`, `errorCode` opcional. Sin estado persistente.

## Success Criteria

- **SC-001**: Una prueba contra `simulated` responde en menos de 1 s; contra un doble HTTP sin respuesta, `TIMEOUT` en no mas
  del limite mas 1 s. Verificado con aserciones de `Duration`.
- **SC-002**: En 100 % de los escenarios de fallo de las pruebas, cuerpo y logs no contienen la credencial sentinela.
- **SC-003**: 100 % de las peticiones de CLIENTE y OPERADOR reciben 403 sin invocar ninguna sonda.
- **SC-004**: Con el perfil de pruebas, los tres canales tienen al menos dos proveedores y cada uno es probable. Fuera de
  desarrollo la cantidad depende de D-001.
- **SC-005**: Con el modo de autorizacion realmente activo (PEP), CLIENTE y OPERADOR reciben 403 en el 100 % de los intentos.

## Assumptions

- La pantalla y el boton "Probar" son trabajo del repo del front; aqui solo se fija el contrato.
- "Antes de activarlo" se entiende como proveedor con credenciales ya configuradas en el despliegue; la activacion dinamica
  del catalogo es de la Fase 6 y no se toca.
- Roles: hay tres en el codigo (ADMINISTRADOR, OPERADOR, CLIENTE) hasta B2; solo ADMINISTRADOR prueba.

## Dependencias

- **D-001**: `simulated` fuera de desarrollo lo resuelve la historia E3 (propiedad `simulated.enabled`, falsa por defecto, y
  catalogo con el proveedor real primero). Dueno: Equipo de backend. Fecha: 2026-12-15. Hasta entonces `simulated` esta en los
  tres canales de todo perfil. La E2E corre con el perfil de pruebas, donde `simulated` esta habilitado.
