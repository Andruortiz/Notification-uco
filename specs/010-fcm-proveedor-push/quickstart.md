# Quickstart — Integrar Firebase Cloud Messaging como primer proveedor real de PUSH

**Feature**: 010-fcm-proveedor-push | **Date**: 2026-09-24

Guía de validación. El contrato de salida está en `contracts/fcm-http-v1-api.md`; las decisiones, en
`research.md`.

---

## 1. Validación automatizada (lo que exige el Principio IV)

### Prerrequisitos

- La rama contiene HU2-090 (research.md, Decisión 0): `FakeProviderServer` existe y los adaptadores de
  correo y SMS declaran `@Qualifier`.
- Docker corriendo (las pruebas E2E usan Testcontainers con Mongo y RabbitMQ).
- Con Docker Engine 29 y Testcontainers 1.19.8, exportar antes `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`
  (no usar `-DargLine`: reemplaza el agente de JaCoCo).
- Ninguna credencial real: las pruebas generan un par RSA en tiempo de ejecución y usan dos servidores
  locales que simulan el servicio de autorización y el de envío. **Ninguna prueba automatizada llama al
  proveedor real** (SC-009).

### Comandos

```bash
# Puerta completa, igual que CI
./mvnw -B -ntp verify

# Solo las pruebas de esta historia
./mvnw -B -ntp -pl infrastructure -am test \
  -Dtest='FcmResponseClassifierTest,FcmCredentialsTest,FcmAccessTokenProviderTest,FcmNotificationProviderTest,FcmPushDeliveryE2ETest,FcmDisabledProviderE2ETest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Regresión de los otros proveedores (tercer WebClient en el contexto, Decisión 8)
./mvnw -B -ntp -pl infrastructure -am test \
  -Dtest='BrevoEmailDeliveryE2ETest,BrevoDisabledProviderE2ETest,TwilioSmsDeliveryE2ETest,TwilioDisabledProviderE2ETest,ProviderRoutingE2ETest,ChannelCatalogE2ETest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Arquitectura: deben seguir en verde
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### Qué debe demostrar cada prueba

| Prueba | Demuestra | Spec |
|---|---|---|
| `FcmResponseClassifierTest` | cada fila de `research.md`, Decisión 5 | FR-006, SC-005 |
| `FcmCredentialsTest` | cada fila de motivos de `research.md`, Decisión 3 (ninguna, ambas, archivo ilegible, no JSON, otro `type`, campo ausente, clave ilegible), carga válida por contenido y por archivo; ningún motivo ni `toString()` contiene fragmentos de la credencial | FR-003, FR-004, SC-003 |
| `FcmAccessTokenProviderTest` | la aserción lleva `iss`, `scope`, `aud`, `iat`, `exp = iat + 3600` y su firma verifica con la clave pública del par de la prueba; diez obtenciones con token vigente → una petición de canje; `expires_in` dentro del margen → nueva petición; canje fallido no se memoriza; canje lento cortado dentro de una `Duration` explícita | FR-007, FR-011, SC-006, SC-008 |
| `FcmNotificationProviderTest` | ruta con el `project_id`, `Authorization: Bearer`, cuerpo con `token` idéntico a la dirección, `title` presente u omitido, sin `data`; deshabilitado → `ProviderDisabledException`, un único `WARN` sin valores, **cero peticiones en los dos servidores**; canje rechazado → permanente y cero peticiones de envío; `401` del envío invalida la caché; tiempo de espera del envío cortado dentro de una `Duration` explícita; registros con identificador enmascarado, `providerMessageId` y `providerErrorCode`, sin `message`, título, cuerpo, token de acceso ni aserción | FR-005, FR-007, FR-008, FR-011, FR-012, FR-014, SC-004, SC-006, SC-011, SC-012 |
| `FcmPushDeliveryE2ETest` | flujo completo PUSH con `fcm` preferente → `DELIVERED` y `providerId = fcm`; `simulated` preferente → cero peticiones; `404 UNREGISTERED` → `FAILED`, una sola petición, sin reintentos; `400` → `FAILED`; `503` → `RECOVERABLE`; asunto 100 + cuerpo 900 aceptado; asunto 101 o cuerpo 901 → `400`, nada persistido, cero peticiones; sin asunto → petición sin `title`; ninguna fuga en registros ni eventos, con control positivo del identificador enmascarado | SC-001, SC-004, SC-005, SC-012, SC-013 |
| `FcmDisabledProviderE2ETest` | configuración por defecto sin credenciales: arranca, el catálogo sembrado declara PUSH con `simulated, fcm`, un push se entrega por el simulado; con `fcm` preferente → sin intento, `PENDING`, DLQ con la causa, cero peticiones en los dos servidores | FR-002, FR-005, FR-010, SC-002, SC-003, SC-007, SC-011 |

---

## 2. Validación manual en local (opcional, sin proyecto real)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

En el arranque debe aparecer **un** aviso `Notification sender disabled providerId=fcm reason=missing
...(FCM_CREDENTIALS_JSON or FCM_CREDENTIALS_FILE)` y ningún valor.

```bash
# Push por el simulado (preferente por defecto): 202 y luego DELIVERED
curl -s -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-local' \
  -d '{"externalId":"push-local-1","channelType":"PUSH","recipientId":"r-1",
       "recipientAddress":"device-token-demo-1234","subject":"Pedido","body":"Tu pedido salio","priority":"NORMAL"}'

# Cuerpo de 901 caracteres: 400 con el límite en el mensaje
BODY=$(printf 'a%.0s' $(seq 1 901))
curl -s -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-local' \
  -d "{\"externalId\":\"push-local-2\",\"channelType\":\"PUSH\",\"recipientId\":\"r-1\",
       \"recipientAddress\":\"device-token-demo-1234\",\"body\":\"$BODY\",\"priority\":\"NORMAL\"}"
```

Si el Mongo local ya tenía catálogo, el primer `curl` devuelve `400` "canal no disponible": dar de alta el
canal PUSH (sección 4).

---

## 3. Prueba manual de humo con un proyecto real (FUERA de CI)

### Prerrequisitos

- Un proyecto del proveedor con Cloud Messaging habilitado y la **clave JSON de una cuenta de servicio**
  (consola del proveedor → configuración del proyecto → cuentas de servicio → generar clave). Guardarla
  fuera del repositorio, por ejemplo en `~/secrets/fcm-humo.json`, con permisos de solo lectura para el
  usuario.
- Un **identificador de dispositivo** real de una aplicación de ese mismo proyecto. La vía más barata es
  una página web de prueba con el SDK web del proveedor (pide permiso de notificaciones y muestra el
  identificador) o la aplicación de ejemplo para Android. Cómo lo obtiene la aplicación no es parte de
  esta historia.

### Procedimiento

1. Ejecución A, credencial por **archivo** (nunca escribirla en un archivo versionado):

   ```bash
   export FCM_CREDENTIALS_FILE="$HOME/secrets/fcm-humo.json"
   export NOTIFICATION_PUSH_PROVIDERS='fcm,simulated'
   docker compose up -d mongodb rabbitmq
   ./mvnw -pl infrastructure spring-boot:run
   ```

2. Comprobar en el arranque que **no** aparece el aviso de proveedor deshabilitado.

3. `NOTIFICATION_PUSH_PROVIDERS` solo actúa sobre un catálogo vacío. Si el Mongo local ya tenía catálogo,
   dar de alta o invertir el canal PUSH a mano (sección 4) con `providers: ["fcm", "simulated"]`.

4. Enviar al identificador real:

   ```bash
   curl -s -X POST http://localhost:8060/notifications \
     -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-humo' \
     -d '{"externalId":"humo-push-1","channelType":"PUSH","recipientId":"humo-1",
          "recipientAddress":"<identificador de dispositivo>","subject":"Prueba de humo",
          "body":"Prueba manual de humo HU2-091.","priority":"NORMAL"}'
   ```

5. Verificar, en este orden:
   - el dispositivo muestra el push con el título "Prueba de humo" y el cuerpo enviado;
   - `GET /notifications/{id}` devuelve `DELIVERED` y `providerId` `fcm`;
   - el registro del despacho trae `providerMessageId` y el identificador enmascarado (`***` + últimos 4);
   - el registro **no** contiene la clave privada, el `client_email`, el token de acceso, la aserción, el
     título, el cuerpo ni el identificador completo.

6. Ejecución B, credencial por **variable**: detener el servicio, `unset FCM_CREDENTIALS_FILE`,
   `export FCM_CREDENTIALS_JSON="$(cat "$HOME/secrets/fcm-humo.json")"`, arrancar y repetir el paso 4 con
   otro `externalId`. Luego exportar **ambas** y comprobar que el arranque avisa de deshabilitación por
   credencial ambigua.

7. Casos negativos baratos:
   - identificador inventado (`device-token-demo-1234`) → `FAILED` y `providerErrorCode=INVALID_ARGUMENT`
     en el registro, sin el `message` del proveedor;
   - identificador de una aplicación desinstalada o con el permiso revocado → `FAILED` con
     `providerErrorCode=UNREGISTERED`, sin reintentos;
   - cuerpo de 901 caracteres → `400` inmediato y nada en la consola del proveedor.

8. Limpiar: `unset FCM_CREDENTIALS_FILE FCM_CREDENTIALS_JSON NOTIFICATION_PUSH_PROVIDERS`. Si la clave de
   la cuenta de servicio era solo para la prueba, revocarla en la consola.

Si el canje de autorización falla aquí y no en las pruebas automatizadas, es la señal para activar el plan
de respaldo de `research.md`, Decisión 1 (c).

### Registro del resultado

Anotar aquí cada ejecución (este archivo está versionado; **nunca** pegar credenciales, identificadores
completos ni el `project_id` si se considera sensible):

| Fecha | Quién | Commit | Medio de credencial | Push recibido | Estado final | `INVALID_ARGUMENT` con inventado | `UNREGISTERED` con desinstalada | Notas |
|---|---|---|---|---|---|---|---|---|
| *(pendiente)* | | | | | | | | |

---

## 4. Alta del canal PUSH en un entorno existente

`ChannelCatalogSeeder` siembra **solo si la colección está vacía**. En un entorno ya sembrado el canal PUSH
no existe hasta darlo de alta a mano:

```javascript
// mongosh, base de datos del componente
db.channel_catalog.updateOne(
  { _id: "PUSH" },
  { $set: {
      providers: ["simulated", "fcm"],
      contentSchema: "{\"type\":\"object\",\"required\":[\"body\"],\"properties\":{\"subject\":{\"maxLength\":100},\"body\":{\"type\":\"string\",\"maxLength\":900}}}"
  } },
  { upsert: true }
)
```

El refresco de la caché es periódico (`NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS`, 30 s por defecto): el
cambio se ve sin reiniciar. Limitación conocida, con dueño y fecha en `research.md`, Decisión 7.

---

## 5. Advertencias operativas

- **No habilitar** `logging.level.reactor.netty.http.client=DEBUG` ni `wiretap` con credenciales reales:
  volcaría el token de acceso, la aserción firmada, el identificador de dispositivo y el contenido.
- La credencial es una clave privada que permite enviar push a todos los dispositivos del proyecto:
  preferir el archivo montado con permisos restrictivos; variable de entorno solo en desarrollo e
  integración continua. Gestión de secretos de plataforma pendiente (HU2-052).
- Un único proyecto del proveedor por despliegue (Q3): identificadores de aplicaciones de otros proyectos
  terminan en `FAILED` por `SENDER_ID_MISMATCH`.
- No exponer el proveedor real a carga real sin límite de tasa propio (HU2-039).
