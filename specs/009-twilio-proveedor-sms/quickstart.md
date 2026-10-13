# Quickstart — Integrar Twilio como primer proveedor real de SMS

**Feature**: 009-twilio-proveedor-sms | **Date**: 2026-09-24

Guía de validación. Los detalles del contrato de salida están en `contracts/twilio-messages-api.md`; las
decisiones, en `research.md`.

---

## 1. Validación automatizada (lo que exige el Principio IV)

### Prerrequisitos

- Docker corriendo (las pruebas E2E usan Testcontainers con Mongo y RabbitMQ).
- Con Docker Engine 29 y Testcontainers 1.19.8, exportar antes `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"`
  (no usar `-DargLine`: reemplaza el agente de JaCoCo).
- Ninguna credencial real: todas las pruebas usan valores inventados y un servidor local que simula al
  proveedor. **Ninguna prueba automatizada llama al proveedor real** (SC-009).

### Comandos

```bash
# Puerta completa, igual que CI
./mvnw -B -ntp verify

# Solo las pruebas de esta historia
./mvnw -B -ntp -pl utils test -Dtest=PhoneNumbersTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test \
  -Dtest='TwilioResponseClassifierTest,TwilioNotificationProviderTest,TwilioSmsDeliveryE2ETest,TwilioDisabledProviderE2ETest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Regresión del proveedor de correo (Decisiones 2 y 10: calificador y servidor simulado renombrado)
./mvnw -B -ntp -pl infrastructure -am test \
  -Dtest='BrevoNotificationProviderTest,BrevoEmailDeliveryE2ETest,BrevoDisabledProviderE2ETest,ProviderRoutingE2ETest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Arquitectura: deben seguir en verde
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### Qué debe demostrar cada prueba

| Prueba | Demuestra | Spec |
|---|---|---|
| `PhoneNumbersTest` | formato internacional válido/ inválido (incluido un correo, un número sin `+`, `+0...`, 16 dígitos); enmascarado `***` + últimos 4, y `***` para ≤ 4 dígitos, nulo o vacío | FR-008, FR-013 |
| `TwilioResponseClassifierTest` | cada fila de la tabla de `research.md`, Decisión 5 | FR-006, SC-005 |
| `TwilioNotificationProviderTest` | ruta con el identificador de cuenta, `Authorization` básica, formulario `To`/`From`/`Body` sin asunto; destinatario mal formado → permanente sin petición; cada credencial ausente o mal formada → `ProviderDisabledException` sin petición y un único `WARN` sin valores; tiempo de espera cortado dentro de una `Duration` explícita; registro con `sid`, `code` y número enmascarado y sin el `message` del proveedor | FR-003, FR-004, FR-007, FR-008, FR-012, FR-013, SC-003, SC-006, SC-011 |
| `TwilioSmsDeliveryE2ETest` | flujo completo SMS con `twilio` preferente → `DELIVERED` y `providerId = twilio`; `simulated` preferente → cero peticiones; `400` → `FAILED`; `500` → `RECOVERABLE`; 160 caracteres aceptado; 161 → `400`, nada persistido, cero peticiones; destinatario mal formado → `FAILED`, cero peticiones; ninguna fuga en registros ni eventos, con control positivo del número enmascarado | SC-001, SC-004, SC-005, SC-008, SC-011 |
| `TwilioDisabledProviderE2ETest` | configuración por defecto sin credenciales: arranca, el catálogo sembrado declara SMS con `simulated, twilio`, un SMS se entrega por el simulado; con `twilio` preferente → sin intento, `PENDING`, DLQ con la causa que nombra el dato ausente | FR-002, FR-005, FR-010, SC-002, SC-003, SC-007 |

---

## 2. Validación manual en local (opcional, sin cuenta real)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

En el arranque debe aparecer **un** aviso `Notification sender disabled providerId=twilio reason=missing
notification.provider.twilio.account-sid (TWILIO_ACCOUNT_SID)` (sin credenciales), y ningún valor.

```bash
# SMS por el simulado (preferente por defecto): 202 y luego DELIVERED
curl -s -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-local' \
  -d '{"externalId":"sms-local-1","channelType":"SMS","recipientId":"r-1",
       "recipientAddress":"+573001234567","body":"Hola desde el canal SMS","priority":"NORMAL"}'

# Cuerpo de 161 caracteres: 400 con el límite en el mensaje
BODY=$(printf 'a%.0s' $(seq 1 161))
curl -s -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-local' \
  -d "{\"externalId\":\"sms-local-2\",\"channelType\":\"SMS\",\"recipientId\":\"r-1\",
       \"recipientAddress\":\"+573001234567\",\"body\":\"$BODY\",\"priority\":\"NORMAL\"}"
```

Si el Mongo local ya tenía catálogo, el primer `curl` devuelve `400` "canal no disponible": dar de alta el
canal SMS (sección 4).

---

## 3. Prueba manual de humo con cuenta de prueba real (FUERA de CI)

### Prerrequisitos

- Cuenta de prueba del proveedor con: identificador de cuenta (`AC...`), token de autenticación, un número
  de origen de la cuenta y **al menos un número de destino verificado** en ella.
- Recordar las limitaciones de la cuenta de prueba: solo números verificados (máximo 5) y el prefijo
  "Sent from your Twilio trial account - " en cada mensaje, que además hace que un cuerpo de 160
  caracteres llegue en 2 fragmentos (no es un fallo del límite).

### Procedimiento

1. Exportar las variables (nunca escribirlas en un archivo versionado):

   ```bash
   export TWILIO_ACCOUNT_SID='<AC...>'
   export TWILIO_AUTH_TOKEN='<token>'
   export TWILIO_FROM_NUMBER='<+1...>'
   export NOTIFICATION_SMS_PROVIDERS='twilio,simulated'
   ```

2. Levantar dependencias y arrancar el servicio:

   ```bash
   docker compose up -d mongodb rabbitmq
   ./mvnw -pl infrastructure spring-boot:run
   ```

3. Comprobar en el arranque que **no** aparece el aviso de proveedor deshabilitado.

4. `NOTIFICATION_SMS_PROVIDERS` solo actúa sobre un catálogo vacío. Si el Mongo local ya tenía catálogo,
   dar de alta o invertir el canal SMS a mano (sección 4) con `providers: ["twilio", "simulated"]`.

5. Enviar a un número **verificado**:

   ```bash
   curl -s -X POST http://localhost:8060/notifications \
     -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-humo' \
     -d '{"externalId":"humo-sms-1","channelType":"SMS","recipientId":"humo-1",
          "recipientAddress":"<numero verificado +...>","subject":"no debe llegar",
          "body":"Prueba manual de humo HU2-090.","priority":"NORMAL"}'
   ```

6. Verificar, en este orden:
   - llega el SMS al teléfono, con el prefijo de cuenta de prueba y **sin** el texto del asunto;
   - `GET /notifications/{id}` devuelve `DELIVERED` y `providerId` `twilio`;
   - el registro del despacho trae `providerMessageId` y coincide con el mensaje en la consola del
     proveedor;
   - el registro **no** contiene token, identificador de cuenta, cuerpo ni número completo, y sí el número
     enmascarado (`***` + últimos 4).

7. Casos negativos baratos:
   - un número **no verificado**: estado `FAILED` y `providerErrorCode=21608` en el registro, sin el
     `message` del proveedor;
   - un cuerpo de 161 caracteres: `400` inmediato y nada en la consola del proveedor.

8. Limpiar: `unset TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN TWILIO_FROM_NUMBER NOTIFICATION_SMS_PROVIDERS`.

### Registro del resultado

Anotar aquí cada ejecución (este archivo está versionado; **nunca** pegar credenciales ni números
completos):

| Fecha | Quién | Commit | SMS recibido | Estado final | Asunto ausente | Código 21608 con no verificado | Notas |
|---|---|---|---|---|---|---|---|
| *(pendiente)* | | | | | | | |

---

## 4. Alta del canal SMS en un entorno existente

`ChannelCatalogSeeder` siembra **solo si la colección está vacía**. En un entorno ya sembrado el canal SMS
no existe hasta darlo de alta a mano:

```javascript
// mongosh, base de datos del componente
db.channel_catalog.updateOne(
  { _id: "SMS" },
  { $set: {
      providers: ["simulated", "twilio"],
      contentSchema: "{\"type\":\"object\",\"required\":[\"body\"],\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":160}}}"
  } },
  { upsert: true }
)
```

El refresco de la caché es periódico (`NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS`, 30 s por defecto): el
cambio se ve sin reiniciar. Limitación conocida, con dueño y fecha en `research.md`, Decisión 4.

---

## 5. Advertencias operativas

- **No habilitar** `logging.level.reactor.netty.http.client=DEBUG` ni `wiretap` con credenciales reales:
  volcaría la cabecera `Authorization`, la URL con el identificador de cuenta, el número y el cuerpo.
- Las credenciales van por variable de entorno: insuficiente para producción (HU2-052, con dueño y fecha
  en `research.md`, Decisión 15). Preferir claves de API revocables cuando exista esa historia.
- No exponer el proveedor real a carga real sin límite de tasa propio (HU2-039).
- Producción con números propios en EE.UU. exige registro A2P 10DLC.
