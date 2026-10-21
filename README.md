# Notification-uco

## Trazabilidad del desarrollo asistido por IA

Desde el 2026-09-07 este repositorio adopta [spec-kit](https://github.com/github/spec-kit): cada
historia nueva desarrollada con asistencia de IA queda documentada de punta a punta en
`specs/<historia>/` (`spec.md` → `plan.md` → `tasks.md`), gobernada por las reglas de
`.specify/memory/constitution.md`.

**Nota retrospectiva:** el trabajo anterior a esa fecha (PRs #1–22, dominio, casos de uso CU-01 a
CU-04, contrato OpenAPI inicial) se desarrolló con asistencia de IA pero sin este proceso
estructurado — la evidencia disponible para ese período es el historial real de commits/PRs en
GitHub y las pruebas automatizadas del repositorio (en particular `HexagonalArchitectureTest` como
verificación objetiva de la arquitectura). No se reconstruye retroactivamente para simular que el
proceso existía desde el inicio.

## Adjuntos por correo (Brevo)

El canal EMAIL entrega adjuntos por Brevo. Los envia como Base64 dentro de la solicitud al proveedor.

- **Activacion:** el proveedor por defecto del canal es el simulado, que no envia nada. Para entregar de
  verdad, definir `NOTIFICATION_EMAIL_PROVIDERS=brevo` y las variables `BREVO_API_KEY`,
  `BREVO_SENDER_EMAIL` y `BREVO_SENDER_NAME`.
- **Limite:** Brevo admite menos de 4 MB de adjuntos por correo transaccional. Se aplica ese limite como
  **suma de todos los adjuntos** (4 000 000 bytes), en dos puntos: al aceptar la notificacion, con el
  esquema del canal (`attachmentsTotalBytes` y `sizeBytes`, 400 con el motivo), y al despachar, donde un
  total mayor falla sin llamar a Brevo. Hasta 5 adjuntos por notificacion.
- **Archivos grandes:** los de mas de 1 MB se suben primero a MinIO (`POST /attachment-uploads`). Al
  despachar, el servicio los lee del almacen y comprueba su huella SHA-256 antes de enviarlos. Si el
  archivo ya no existe, cambio o no coincide con su huella, la notificacion falla sin reintentos; si el
  almacen no responde, se reintenta.
- **Subida desde el navegador:** la subida va directo a MinIO con una URL prefirmada. `MINIO_PUBLIC_ENDPOINT`
  debe ser una direccion alcanzable desde el navegador, y `MINIO_CORS_ALLOW_ORIGIN` debe incluir el origen
  del frontend.
- **Bases ya sembradas:** el sembrador solo agrega canales que faltan y nunca modifica uno guardado, asi que
  una base creada antes de este cambio sigue con el canal EMAIL sin adjuntos. Para actualizarlo, desde
  `mongosh` conectado a la base del servicio:

```js
db.channel_catalog.updateOne(
  { _id: "EMAIL" },
  { $set: { contentSchema: '{"type":"object","properties":{"attachments":{"type":"array","maxItems":5,"items":{"type":"object","properties":{"sizeBytes":{"type":"integer","maximum":4000000}}}},"attachmentsTotalBytes":{"type":"integer","maximum":4000000}}}' } }
)
```

  El catalogo se refresca solo cada 30 segundos.
- **Fuera de alcance:** SMS (Twilio) y push (FCM) no envian adjuntos; una notificacion con adjuntos
  despachada por ellos falla sin reintentos.

## Configuracion sincronizada desde Parametros

El servicio adopta sin reiniciar el tope de intentos de despacho (`dispatch.max-attempts`), los tiempos de
espera y de conexion de Brevo, Twilio y FCM, y el intervalo del reencolador (`requeue.interval-ms`). Un cambio
invalido se rechaza completo y conserva la version vigente; las operaciones en curso terminan con el valor con
que empezaron.

- **Fuente inactiva por defecto:** sin `NOTIFICATION_PARAMETERS_BASE_URL` el servicio opera con los valores
  de arranque o con la ultima configuracion valida guardada en Mongo (coleccion `configuration_last_known`).
  Con la variable definida activa el sondeo HTTP contra `{base-url}/notification-service/configuration`.
- **Propiedades `notification.parameters.*`:** `base-url` (vacia), `poll-interval-ms` (30000), `timeout-ms`
  (5000), `connect-timeout-ms` (2000) y `last-known-load-timeout-ms` (5000).
- **Consulta:** `GET /configuration` (rol `ADMINISTRADOR`) devuelve los parametros gestionables con su valor
  vigente, la version y el origen (`PARAMETERS`, `LAST_KNOWN` o `DEFAULTS`); nunca incluye credenciales ni
  direcciones base.
- **Observabilidad:** eventos `CONFIG_APPLIED`, `CONFIG_REJECTED`, `CONFIG_IGNORED`, `PARAMETERS_UNAVAILABLE`
  y `PARAMETERS_RECOVERED` con identificador de correlacion `param-...`, y la metrica
  `notification.configuration.version` con la etiqueta `source`.
- **Suscripcion por evento (contrato supuesto):** con `NOTIFICATION_PARAMETERS_EVENTS_EXCHANGE`, `..._ROUTING_KEY` y
  `..._QUEUE` definidas, el servicio consume de una cola propia mensajes `{"version": n, "values": {...}}` del
  exchange de Parametros con ack manual y DLQ (`<cola>.dlq`); el sondeo HTTP se conserva como reconciliacion.
  Sin exchange configurado no existe cola ni listener. El contrato esta en
  `specs/022-suscripcion-parametros/contracts/parametros-evento-supuesto.md` y se ajusta antes del 2026-11-15.

## Autenticacion

El servicio exige un JWT en `Authorization: Bearer`. El modo se elige con `NOTIFICATION_AUTH_MODE`:

- **`local`** (por defecto): JWT firmado con un secreto compartido (`AUTH_JWT_HS256_SECRET`), que es obligatorio, de al menos 32 bytes y no tiene valor por defecto. Para desarrollo, activa el perfil `local` (`SPRING_PROFILES_ACTIVE=local`), que trae un secreto de desarrollo; el servicio no arranca con ese valor fuera del perfil `local`. Todo token debe traer la claim `exp`. Es la autenticacion interina para desarrollo y pruebas.
- **`platform`**: tokens de la plataforma central de seguridad, validados con **su clave publica** (RSA o EC). Hay un solo adaptador activo por modo; un valor desconocido impide que la aplicacion arranque.

Cada modo es un adaptador del puerto `TokenValidationPort`; el dominio y el filtro de autenticacion no cambian. En modo `platform` el arranque falla si falta la clave publica.

Panel en vivo: `GET /notifications/stream` se autentica con la cabecera `Authorization: Bearer` o con la cookie de sesion `SECURITY_BASELINE_SESSION` (un `EventSource` nativo la envia con `withCredentials`). Sin credenciales o con una sesion invalida responde 401; si la plataforma de sesiones no responde, 503. Desde HU2-111 se retiraron `POST /notifications/stream/tickets` y el parametro `ticket`; `access_token` tampoco se acepta.

Contrato de la API (version 2.0.0): todas las rutas son literales, sin `:` ni `{}`, para que el PDP las registre como recursos protegidos. Los identificadores viajan en la query o en el cuerpo: `GET /notifications/detail?notificationId=...`, `GET /attachment-uploads/status?uploadId=...`, `POST /attachment-upload-completions` con `{uploadId}`. Cada operacion declara `x-status` (`available` o `planned`) y `x-minimum-role` en `api-notificaciones.yaml`, que tambien trae la tabla de migracion v1 a v2. La lista de recursos protegidos se genera en cada build en `infrastructure/target/contract/protected-resources.json`.

| Variable | Para que sirve | Por defecto |
| --- | --- | --- |
| `AUTH_PLATFORM_PUBLIC_KEY` | Clave publica del emisor, en PEM (X.509); admite los saltos de linea como `\n` | obligatoria |
| `AUTH_PLATFORM_ISSUER` | Si se define, el claim `iss` debe coincidir | sin validar |
| `AUTH_PLATFORM_AUDIENCE` | Si se define, el claim `aud` debe incluirla | sin validar |
| `AUTH_PLATFORM_SUBJECT_CLAIM` | Claim con el usuario o sistema | `sub` |
| `AUTH_PLATFORM_TENANT_CLAIM` | Claim con el tenant | `tenantId` |
| `AUTH_PLATFORM_ROLE_CLAIM` | Claim con el rol; puede ser un texto o una lista | `role` |
| `AUTH_PLATFORM_CLOCK_SKEW_SECONDS` | Tolerancia de reloj | `30` |

Los roles de la plataforma se traducen a los del servicio (`CLIENTE`, `OPERADOR`, `ADMINISTRADOR`) con una tabla, que se define en `application.yml` o en un archivo de configuracion (no en variables de entorno):

```yaml
notification:
  auth:
    platform:
      role-mapping:
        soporte: OPERADOR
        sistema-integrado: CLIENTE
```

Si el token trae varios roles, gana el de mayor privilegio; si ninguno es valido, se rechaza. La firma se comprueba solo con la clave publica: un token firmado con HMAC usando esa clave es rechazado.

**Pendiente de acordar con el equipo de seguridad:** los nombres reales de los claims, la tabla de roles, el emisor y la audiencia, y como se garantiza que la peticion llega a traves del PEP (red privada, mTLS o firma). Esos valores se ajustan por configuracion.

### Cookie de sesion (BFF/PDP) y CORS con credenciales

Con el PEP activo (`security.enabled` y `security.pep.enforcement.enabled`), el servicio acepta tambien la cookie `SECURITY_BASELINE_SESSION` como alternativa al Bearer:

- **Precedencia:** si la peticion trae `Authorization`, manda el Bearer y, si es invalido, responde 401 sin probar la cookie. Sin `Authorization`, se usa la cookie; sin ninguna de las dos, responde 401 tambien en `GET /notifications/stream`.
- **Identidad:** el servicio consulta `GET {AUTH_SESSION_URL}{AUTH_SESSION_PATH}` (por defecto `http://localhost:18080/api/v1/session`) reenviando solo esa cookie. Toma `data.subject` y `data.tenantId` de la respuesta; el rol efectivo es `CLIENTE` y la autorizacion por ruta la hace el PEP. No se cachea la identidad: cada peticion con cookie consulta la sesion, de modo que un cierre de sesion se refleja de inmediato.
- **Errores:** sesion inexistente, expirada, revocada o con una forma inesperada: 401 (`NTF-2001`). Plataforma de sesiones caida, con error 5xx o sin respuesta en `AUTH_SESSION_TIMEOUT_MS` (3000 por defecto): 503 (`NTF-2003`). Con el PEP inactivo la cookie se rechaza con 401.
- **Variables:** `AUTH_SESSION_URL`, `AUTH_SESSION_PATH`, `AUTH_SESSION_TIMEOUT_MS`.
- **CORS:** `NOTIFICATION_CORS_ALLOWED_ORIGINS` lista los origenes con permiso para llamar con credenciales; el servicio devuelve el origen exacto y `Access-Control-Allow-Credentials: true`, y no arranca si la lista contiene un comodin. Si el panel pasa por el proxy de Vite (mismo origen) CORS no interviene.

Esta resolucion de identidad es provisional (excepcion registrada en `specs/025-sesion-cookie-bff/research.md`): el dueno es Seguridad (starter) con Notification-uco (adaptador) y debe retirarse antes del cierre de HU2-055, cuando el starter exponga la identidad que ya verifica. Siguen abiertos con seguridad: si la consulta de sesion se puede hacer de servicio a servicio y si exige `X-Application-Credential`, y la tolerancia de revocacion que permitiria una cache.

### Rutas publicas con el PEP activo

El starter del PEP exige credencial en toda ruta salvo las de `security.pep.enforcement.public-paths` (`SECURITY_PEP_PUBLIC_PATHS`, separadas por comas, patrones de ruta completa; `/**` final cubre el subarbol; no distingue metodo). Por defecto son solo lectura de documentacion y sondas: `GET /openapi/api-notificaciones.yaml` y `GET /v3/api-docs` (contrato, sin datos) y, en el puerto de gestion, `/actuator/health`, `/actuator/health/**` y `/actuator/prometheus`. Estan declaradas en `x-public-routes` del contrato y no son recursos que haya que registrar en el PDP.

- **Swagger UI** (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/swagger-config`) esta deshabilitada y no es publica por defecto; el perfil `local` la habilita. Para otro entorno: `SWAGGER_UI_ENABLED=true` y anadir esas rutas a `SECURITY_PEP_PUBLIC_PATHS`. El criterio esta en `specs/027-rutas-publicas-pep/research.md`.
- Una prueba compara el contrato, `application.yml`, `application-local.yml` y la lista del filtro del servicio.

## Credenciales de GitHub Packages (starter del PEP)

El starter `security-pep-integration-spring-boot-starter` se publica en GitHub Packages (`https://maven.pkg.github.com/seguridad-uco/securitybaseline`), que exige autenticacion incluso para leer. El repositorio y la dependencia estan declarados en `infrastructure/pom.xml` con el identificador de servidor `github`.

- **Local:** en `~/.m2/settings.xml` declara un `<server>` con `<id>github</id>`, tu usuario de GitHub y un token con permiso `read:packages`.
- **CI:** los workflows leen los secretos del repositorio `PACKAGES_USER` y `PACKAGES_TOKEN` (GitHub no permite nombres que empiecen por `GITHUB_`), que exponen como las variables `GITHUB_PACKAGES_USER` y `GITHUB_PACKAGES_TOKEN` (token con `read:packages` sobre el paquete) y un paso copia `.mvn/settings-github-packages.xml` a `~/.m2/settings.xml` (así Sonar no marca como credencial la clave `server-password` de `actions/setup-java`).
- **PR de forks y de Dependabot:** GitHub no entrega los secretos del repositorio a esos eventos; `GITHUB_PACKAGES_USER` y `GITHUB_PACKAGES_TOKEN` llegan vacios y Maven recibe un 401 al resolver el starter, de modo que los jobs de compilacion, pruebas, calidad, seguridad e imagen fallan en el paso de Maven. Los secretos solo se declaran en los pasos que los usan (Maven y `docker build`), no a nivel de workflow. Esos PR se revisan y se vuelven a lanzar desde una rama del propio repositorio.
- **Imagen Docker:** el `Dockerfile` lee los secretos como variables de entorno solo dentro de cada `RUN` y usa `.mvn/settings-github-packages.xml` (estatico, con `${env.GITHUB_PACKAGES_USER}` y `${env.GITHUB_PACKAGES_TOKEN}`); un fallo de resolucion rompe el build. Recibe las mismas credenciales como secretos de BuildKit y no las deja en ninguna capa: `docker build --secret id=github_packages_user,env=GITHUB_PACKAGES_USER --secret id=github_packages_token,env=GITHUB_PACKAGES_TOKEN -t notification-service .`

## Metricas y puerto de gestion

El servicio expone la gestion en un puerto aparte del de la API: la API sigue en el 8060 y el puerto de gestion es `MANAGEMENT_PORT` (por defecto 8061). El 8061 no se publica fuera del cluster; las sondas de Kubernetes y el scrape de Prometheus lo consultan por la red interna.

- `GET http://localhost:8061/actuator/health/liveness` y `/actuator/health/readiness`: sondas de salud.
- `GET http://localhost:8061/actuator/prometheus`: metricas en formato Prometheus (negocio, `http.server.requests` con percentiles, consumo de RabbitMQ y JVM).
- Solo `health` y `prometheus` estan expuestos, con lista explicita; `env`, `beans`, `heapdump`, `configprops`, `loggers`, `threaddump`, `mappings`, `metrics` e `info` no responden. El 8060 no sirve ninguna ruta de `/actuator`.
- Metricas de negocio: `notification_accepted_total`, `notification_attempts_total`, `notification_dispatched_total`, `notification_provider_duration_seconds` y `notification_errors_total`, con etiquetas de cardinalidad cerrada (`channel`, `provider`, `result`, `errorCode`, `failureCategory`); nunca `tenantId`, `notificationId`, `correlationId` ni datos del destinatario.
- El `docker-compose.yml` levanta por defecto solo las dependencias; el servicio corre con `./mvnw -pl infrastructure spring-boot:run` o con el `Dockerfile`, que declara `EXPOSE 8060 8061`. Con `docker compose --profile app up -d` tambien levanta la imagen publicada (`NOTIFICATION_IMAGE` y `NOTIFICATION_VERSION` del `.env`).

## Tablero de observabilidad

Prometheus y Grafana viven en `docker-compose.observability.yml`, aparte de `docker-compose.yml`, para que `docker compose up -d mongodb rabbitmq` no exija credenciales de Grafana. El servicio corre fuera de Docker (`./mvnw -pl infrastructure spring-boot:run`); Prometheus lo raspa en `host.docker.internal:8061` cada 15 segundos y conserva 31 dias en el volumen `prometheus-data`.

1. Define en el `.env` (sin valor por defecto, el arranque falla si faltan) `GRAFANA_ADMIN_USER` y `GRAFANA_ADMIN_PASSWORD`. Opcionales: `GRAFANA_PORT` (3000) y `PROMETHEUS_PORT` (9090).
2. Levanta el stack: `docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d`. Para no repetir `-f`, define en el `.env` `COMPOSE_PATH_SEPARATOR=,` y `COMPOSE_FILE=docker-compose.yml,docker-compose.observability.yml`; en Windows el separador por defecto es `;`, asi que sin `COMPOSE_PATH_SEPARATOR` los dos puntos no funcionan.
3. Abre `http://localhost:3000`, entra con las credenciales del `.env` y busca el tablero "Notification Service". Prometheus queda en `http://localhost:9090` (reglas en la pestana Alerts). Ambos escuchan solo en `127.0.0.1`; el 8061 del servicio no se publica.
4. Para parar y borrar los datos: `docker compose -f docker-compose.yml -f docker-compose.observability.yml down -v`.

Que demuestra cada panel de RNF y que no:

- RNF-02: percentil 95 de `POST /notifications` con 202 frente a 0,2 s. El percentil se interpola entre cubos fijos del histograma, es una aproximacion.
- RNF-03: aceptadas e intentos por minuto y por replica frente a 500. Muestra el ritmo observado, no la capacidad sostenida: la prueba de carga que la demuestra esta pendiente (dueno: equipo de desarrollo del componente, fecha 2026-11-05).
- RNF-01: fraccion de raspados exitosos (`up`) en la ventana elegida frente al 99,5 %. Mide si el raspado funciono, no la disponibilidad percibida por los clientes; la lectura mensual necesita una ventana de 30 dias.
- RNF-10: las filas de negocio y tecnicas leen las series del endpoint de metricas; una prueba automatizada (`ObservabilityDashboardE2ETest`) verifica que cada metrica y etiqueta del tablero y de las reglas existe y que cada consulta es valida en un Prometheus real.
- Alertas: `observability/alerts.yml` define `NotificationDispatchFailedHigh` (mas de 5 % de despachos fallidos en 5 minutos) y `NotificationServiceDown`. Se evaluan en Prometheus sin Alertmanager; el destino externo esta pendiente (dueno: equipo de desarrollo del componente, fecha 2026-11-12).

## Azure Key Vault

Las variables de entorno del servicio (`MONGO_PASSWORD`, `BREVO_API_KEY`, `AUTH_JWT_HS256_SECRET`, etc.)
pueden leerse tambien desde un Azure Key Vault. Los contenedores (MongoDB, RabbitMQ, MinIO, ClamAV) no
cambian.

- **Activacion:** definir `AZURE_KEYVAULT_ENDPOINT` (por ejemplo `https://mi-vault.vault.azure.net/`).
  Sin ella el servicio funciona solo con el `.env`, como siempre.
- **Nombres:** Key Vault no admite guiones bajos, asi que el secreto `MONGO-PASSWORD` se expone como
  `MONGO_PASSWORD`. Se crean con el mismo nombre de la variable cambiando `_` por `-`.
- **Prefijo (recomendado):** con `AZURE_KEYVAULT_SECRET_PREFIX` (por ejemplo `NOTIFICATION-`) solo se leen
  los secretos cuyo nombre empieza con ese prefijo, y este se descarta: `NOTIFICATION-MONGO-PASSWORD` se
  expone como `MONGO_PASSWORD`. Sin prefijo se leen todos los secretos del vault, tambien los de otros
  servicios, y cada uno es una llamada mas al arrancar.
- **Precedencia:** una variable de entorno real gana sobre el vault; el vault gana sobre los valores por
  defecto de `application.yml`.
- **Autenticacion:** `DefaultAzureCredential`. En Azure, identidad administrada del Container App o App
  Service con el rol *Key Vault Secrets User* sobre el vault. En local, `az login`.
- **Fallo:** si el vault esta configurado y no se puede leer, el arranque falla.
- **Alcance:** solo se leen los secretos habilitados; se cargan una vez al arrancar (rotar un secreto
  requiere reiniciar la aplicacion).
