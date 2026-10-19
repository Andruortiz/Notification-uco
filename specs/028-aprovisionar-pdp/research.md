# Research: API de administración del PDP 0.1.6 (HU2-099)

Fuente: imagen `security-pdp:0.1.6` descompilada (`javap` sobre `BOOT-INF/classes`, sin arrancarla) y el código de la consola
Vue (`src/api/index.ts`, `client.ts`). No se ejecutó ninguna llamada contra un PDP.

## Endpoints verificados

| Uso | Método y ruta | Cuerpo | Verificación |
| --- | --- | --- | --- |
| Validar sesión / obtener CSRF | `GET /api/v1/session` | - | Controlador `SessionController`; la consola lo llama para obtener la cookie `XSRF-TOKEN` |
| Buscar aplicación | `GET /api/v1/applications?offset&limit` | - | `ApplicationController.list` (params `name,page,size,offset,limit`); respuesta paginada `{content,total,page,offset,limit}` dentro de `data` |
| Listar recursos | `GET /api/v1/applications/{id}/resources` | - | `ProtectedResourceController`; devuelve lista en `data` |
| Registrar recurso | `POST /api/v1/applications/{id}/resources` | `{path, method}` | `ResourceAdministrationController` + `RegisterProtectedResourceBodyRequest` |
| Listar roles | `GET /api/v1/roles?offset&limit` | - | `RoleController` (todo el tenant, paginado, límite 1..100); trae `applicationId` y `resourceIds` |
| Crear rol | `POST /api/v1/roles` | `{name, scope:"APPLICATION", applicationId}` | `RoleAdministrationController` + `DefineRoleRawRequest`; nombre de 3 a 60 caracteres |
| Vincular rol y recurso | `POST /api/v1/roles/{roleId}/resources` | `{resourceId}` | `GrantResourceRawRequest` |
| Listar perfiles | `GET /api/v1/profiles?offset&limit` | - | `ProfileController`; trae `applicationId` y `roleIds` |
| Crear perfil | `POST /api/v1/profiles` | `{name, scope:"APPLICATION", applicationId}` | `DefineProfileRawRequest` |
| Agregar rol a perfil | `POST /api/v1/profiles/{profileId}/roles` | `{roleId}` | `AddRoleToProfileRawRequest` |

Existen además `DELETE` y `PATCH` de recursos, roles y perfiles (`CatalogMaintenanceController`) que el script no usa.

## Autenticación verificada

- Con el perfil `keycloak` (el del `compose.yaml` local) la cadena de seguridad es `oauth2Login` con sesión: cookie
  `SECURITY_BASELINE_SESSION`. La configuración de Bearer (`JwtSecurityProperties`) solo se carga con el perfil `!keycloak`.
  No hay token técnico ni cabecera `X-Application-Credential` para `/api/v1/**`.
- CSRF: `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`, cabecera `X-XSRF-TOKEN`, sin ofuscación) exigido solo a peticiones
  con cookie de sesión y método no seguro. El script obtiene el token del `Set-Cookie` de `GET /api/v1/session`.
- Decisión: la credencial es el valor de la cookie `SECURITY_BASELINE_SESSION` de un administrador, en `PDP_ADMIN_SESSION`.
- Validación de ruta (`ResourcePath`): empieza por `/`, sin espacios, barras dobles, query ni fragmento; juego de caracteres
  `. _ ~ { } -` más alfanuméricos.

## Lo que se supuso o no se pudo verificar (preguntas abiertas)

1. Códigos de éxito exactos de los POST (200 o 201): el script acepta cualquier 2xx.
2. Código de un duplicado (se supone 409): no se usa, el script consulta antes de escribir.
3. Si el PDP reemite `XSRF-TOKEN` en `GET /api/v1/session` cuando ya hay sesión; si no lo hace, el script genera un valor aleatorio
   y lo envía en cookie y cabecera (válido con doble envío; no probado contra el PDP real).
4. Duración de la sesión y si un usuario sin rol de administrador de la aplicación recibe 403 (existe
   `PrincipalMustBeApplicationAdministratorValidator`; su alcance exacto no se leyó).
5. Una credencial no interactiva (cliente técnico) para CI: el PDP local no la ofrece; pregunta para el equipo de seguridad.
6. Alta de la aplicación (`POST /api/v1/applications`) queda fuera: entrega una credencial de un solo uso.
7. Baja de huérfanos y revocación de vínculos sobrantes: pospuesta (la baja de recursos tiene validadores de dependencias
   en el PDP no revisados).
8. Variante bash: no se hace; PowerShell 7 corre en Linux y macOS y es lo que usa la prueba en CI.
