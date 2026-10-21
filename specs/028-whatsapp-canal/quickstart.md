# Quickstart: validar el canal WhatsApp

**Feature**: 028-whatsapp-canal | Guía de validación; el diseño está en `plan.md`, `data-model.md` y `contracts/`.

## 1. Prerrequisitos

- JDK 21, Docker corriendo (las pruebas E2E usan Testcontainers) y, en este equipo,
  `JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` antes de `./mvnw`.
- Ninguna prueba automatizada necesita credenciales: el proveedor se simula con un servidor HTTP local.

## 2. Validación automatizada

```bash
./mvnw -B -ntp -pl utils,core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=WhatsAppDeliveryE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest=WhatsAppDisabledProviderE2ETest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

Resultados esperados de las dos E2E:

| Escenario | Resultado |
|---|---|
| Texto libre con proveedor habilitado | una petición con `To`, `From`, `Body`; `DELIVERED`; intento con `twilio-whatsapp` |
| Plantilla con variables | una petición con `ContentSid` y `ContentVariables`, sin `Body`; `DELIVERED` |
| Cuerpo y plantilla juntos, o ninguno | `400`; nada persistido; cero peticiones |
| Plantilla en el canal de correo | `400` con "channel does not accept provider templates" |
| Cuerpo de 1601 caracteres | `400` con el límite; con 1600 se acepta |
| Adjunto | `400` por canal que no acepta adjuntos |
| Respuesta 400 con código de ventana cerrada | `FAILED`, sin reintentos, etiqueta `outside-session-window` en el registro |
| 429, 5xx, tiempo agotado | recuperable; se reintenta |
| Reentrega del mismo despacho | misma clave de idempotencia; intentos distintos, claves distintas (si aplica, Decisión 6) |
| Dos tenants | el tenant B no ve la notificación, la plantilla ni las variables del tenant A |
| Sin credenciales | arranque completo, un aviso con la variable ausente, sin valores; la notificación no se marca entregada |
| Registros y mensajes internos | sin credenciales, cuerpo, variables ni número completo; número enmascarado presente |

## 3. Prueba de humo manual con cuenta de pruebas (fuera de CI)

1. Requiere una cuenta de Twilio con el sandbox de WhatsApp (o un remitente aprobado) y un teléfono que se
   haya unido al sandbox. Exportar, sin pegarlos en ningún archivo:

   ```bash
   export TWILIO_ACCOUNT_SID='<AC...>'
   export TWILIO_AUTH_TOKEN='<token>'
   export TWILIO_WHATSAPP_FROM_NUMBER='<+...>'
   export NOTIFICATION_WHATSAPP_PROVIDERS='twilio-whatsapp,simulated'
   ```

2. `docker compose up -d mongodb rabbitmq` y `./mvnw -pl infrastructure spring-boot:run`. Comprobar que
   **no** hay aviso de proveedor deshabilitado.
3. Texto libre a un número que escribió al sandbox en las últimas 24 h:

   ```bash
   curl -s -X POST http://localhost:8060/notifications -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: tenant-humo' \
     -d '{"externalId":"humo-wa-1","channelType":"WHATSAPP","recipientId":"humo-1",
          "recipientAddress":"<+...>","body":"Prueba manual de humo HU2-112.","priority":"NORMAL"}'
   ```

4. Plantilla aprobada (`templateId` = identificador de contenido de la consola):

   ```bash
   curl -s -X POST http://localhost:8060/notifications -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: tenant-humo' \
     -d '{"externalId":"humo-wa-2","channelType":"WHATSAPP","recipientId":"humo-1",
          "recipientAddress":"<+...>","providerTemplate":{"templateId":"<HX...>","variables":{"1":"Alice"}},
          "priority":"NORMAL"}'
   ```

5. Verificar: llegan ambos mensajes; `GET /notifications/{id}` devuelve `DELIVERED` y `twilio-whatsapp`;
   el registro trae `providerMessageId`; no hay credenciales, cuerpo, variables ni número completo.
6. Casos negativos: texto libre a un número sin conversación abierta: `FAILED` y etiqueta
   `outside-session-window`; plantilla inexistente: `FAILED` con el código del proveedor.
7. Limpiar las variables exportadas.

### Registro del resultado

Anotar cada ejecución (versionado; **nunca** pegar credenciales ni números completos):

| Fecha | Quién | Commit | Texto libre | Plantilla | Ventana cerrada rechazada | Idempotencia observada | Notas |
|---|---|---|---|---|---|---|---|
| *(pendiente)* | | | | | | | |

## 4. Alta del canal WhatsApp en un entorno existente

`ChannelCatalogSeeder` siembra solo un catálogo vacío. En un entorno ya sembrado:

```javascript
db.channel_catalog.updateOne(
  { _id: "WHATSAPP" },
  { $set: {
      providers: ["simulated", "twilio-whatsapp"],
      contentSchema: "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\",\"maxLength\":1600},\"providerTemplate\":{\"type\":\"object\",\"required\":[\"templateId\"],\"properties\":{\"templateId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},\"variables\":{\"type\":\"object\",\"maxProperties\":20,\"additionalProperties\":{\"type\":\"string\",\"maxLength\":1024}}}}}}"
  } },
  { upsert: true }
)
```

El refresco es periódico (`NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS`): no hace falta reiniciar.

## 5. Advertencias operativas

- No habilitar `wiretap` ni nivel DEBUG de `reactor.netty.http.client` con credenciales reales: volcaría
  la cabecera `Authorization`, la URL con el identificador de cuenta, el número y el cuerpo.
- Variables de entorno para credenciales: insuficiente para producción (gestión de secretos de plataforma).
- Las plantillas se aprueban y se gestionan en la consola del proveedor; el componente no las registra.
- Cuenta de pruebas y sandbox: solo para humo y demo, no para producción.
