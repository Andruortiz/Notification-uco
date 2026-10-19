# Contrato v2: rutas, lista canónica y registro de cambios

Borrador del registro de cambios que irá en `api-notificaciones.yaml` (versión 2.0.0) y de la lista canónica inicial de
recursos protegidos. La lista definitiva la deriva `ProtectedResourcesCatalogTest` del propio contrato.

## Cambios incompatibles de 1.0.0 a 2.0.0

1. Ninguna ruta contiene `:` ni `{}`. Los identificadores van en la query string o en el cuerpo.
2. Se retiran sin alias: `GET /notifications/{id}`, `GET /attachment-uploads/{uploadId}`,
   `POST /attachment-uploads/{uploadId}:complete`, `POST /notifications:sendBatch`, `GET /notifications:subscribe`,
   `POST /notifications:subscribeTicket`, `POST /notifications/{id}:retry`, `POST /channels:register`,
   `POST /providers:register`, `GET /recipients/{recipientId}/preferences`,
   `POST /recipients/{recipientId}:updatePreferences`.
3. `POST /attachment-uploads` devuelve `Location: /attachment-uploads/status?uploadId=<id>`.
4. La finalización de una subida y el reintento reciben el identificador en el cuerpo JSON.
5. Las preferencias se actualizan con `PUT /recipients/preferences?recipientId=`.
6. `SendNotificationResponse`, `NotificationStatusResponse`, `BatchItemResult`, `NotificationHistoryItem` y
   `DeliveryAttempt` declaran `required`.
7. Se añade el esquema de seguridad `SessionCookie` y las respuestas 401, 403 y 503 del PEP con `X-Decision-Id`.
8. Cada operación declara `x-status` (`available` o `planned`) y `x-minimum-role`.

## Tabla vieja -> nueva

| Método v1 | Ruta v1 | Método v2 | Ruta v2 | Cambio para el cliente |
|---|---|---|---|---|
| POST | `/notifications` | POST | `/notifications` | ninguno |
| GET | `/notifications` | GET | `/notifications` | ninguno |
| POST | `/notifications:sendBatch` | POST | `/notifications/batches` | solo la ruta |
| GET | `/notifications/{id}` | GET | `/notifications/detail?notificationId={id}` | id a query |
| POST | `/notifications/{id}:retry` | POST | `/notification-retries` | id al cuerpo `{notificationId}` (planned) |
| GET | `/notifications:subscribe` | GET | `/notifications/stream` | solo la ruta |
| POST | `/notifications:subscribeTicket` | POST | `/notifications/stream/tickets` | solo la ruta |
| POST | `/attachment-uploads` | POST | `/attachment-uploads` | cambia el valor de `Location` |
| POST | `/attachment-uploads/{uploadId}:complete` | POST | `/attachment-upload-completions` | id al cuerpo `{uploadId}` |
| GET | `/attachment-uploads/{uploadId}` | GET | `/attachment-uploads/status?uploadId={uploadId}` | id a query |
| GET | `/channels` | GET | `/channels` | ninguno |
| GET | `/providers` | GET | `/providers` | ninguno |
| GET | `/configuration` | GET | `/configuration` | ninguno |
| POST | `/channels:register` | POST | `/channels` | solo la ruta (planned) |
| POST | `/providers:register` | POST | `/providers` | solo la ruta (planned) |
| GET | `/recipients/{recipientId}/preferences` | GET | `/recipients/preferences?recipientId={recipientId}` | id a query (planned) |
| POST | `/recipients/{recipientId}:updatePreferences` | PUT | `/recipients/preferences?recipientId={recipientId}` | verbo e id (planned) |

## Lista canónica inicial de recursos protegidos

El PDP no hereda roles: el perfil CLIENTE recibe las filas CLIENTE; OPERADOR, las filas CLIENTE y OPERADOR;
ADMINISTRADOR, todas. Las rutas de esta lista son exactamente las que se registran (sin query string).

| Método | Ruta | Rol mínimo | Estado |
|---|---|---|---|
| POST | `/notifications` | CLIENTE | available |
| GET | `/notifications` | OPERADOR | available |
| POST | `/notifications/batches` | CLIENTE | available |
| GET | `/notifications/detail` | CLIENTE | available |
| POST | `/notification-retries` | OPERADOR | planned |
| GET | `/notifications/stream` | CLIENTE | available |
| POST | `/notifications/stream/tickets` | CLIENTE | available |
| POST | `/attachment-uploads` | CLIENTE | available |
| POST | `/attachment-upload-completions` | CLIENTE | available |
| GET | `/attachment-uploads/status` | CLIENTE | available |
| GET | `/channels` | CLIENTE | available |
| GET | `/providers` | CLIENTE | available |
| GET | `/configuration` | ADMINISTRADOR | available |
| POST | `/channels` | ADMINISTRADOR | planned |
| POST | `/providers` | ADMINISTRADOR | planned |
| GET | `/recipients/preferences` | CLIENTE | planned |
| PUT | `/recipients/preferences` | CLIENTE | planned |

Actualización HU2-111: `POST /notifications/stream/tickets` y el parámetro `ticket` se retiraron (ver
`specs/026-stream-sin-ticket/`); el catálogo vigente tiene 12 rutas y 16 operaciones. Las cifras y la nota siguientes son
históricas.

Nota para HU2-099: `GET /notifications/stream` también admite `?ticket=` sin cabecera `Authorization` (el ticket lo valida
el filtro de autenticación del servicio); cómo lo trata el PEP queda a decidir en HU2-055, porque el starter reenvía solo
las credenciales que recibe.

## Reglas de las rutas (guardia)

Expresión que cumple toda clave de `paths`: `^/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$`. Es un subconjunto estricto de
`ResourcePath` del PDP (que además admite `{` y `}`, que aquí se prohíben).
