# Quickstart — Integrar Brevo como primer proveedor real de correo

**Feature**: 008-brevo-proveedor-correo | **Date**: 2026-09-21

Guía de validación. No contiene implementación; describe cómo comprobar que la historia hace lo que
dice. Las rutas son relativas a la raíz del repositorio.

---

## 1. Validación automatizada (lo que exige el Principio IV)

### Prerrequisitos

- Docker en marcha (Testcontainers levanta Mongo y RabbitMQ).
- Sin credenciales del proveedor: **ninguna** prueba automatizada llama al proveedor real (SC-010).

### Comandos

```bash
# Puerta completa, igual que CI
./mvnw -B -ntp verify

# Solo las pruebas de esta historia
./mvnw -B -ntp -pl core test
./mvnw -B -ntp -pl infrastructure -am test -Dtest=BrevoNotificationProviderTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl infrastructure -am test -Dtest=BrevoEmailDeliveryE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Arquitectura: deben seguir en verde
./mvnw -B -ntp -pl infrastructure -am test -Dtest='HexagonalArchitectureTest,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false
```

Si el entorno local tiene Docker Engine 29, exporta antes
`JAVA_TOOL_OPTIONS="-Dapi.version=1.44"` (nunca `-DargLine`: reemplaza el agente de JaCoCo y la
cobertura sale falsamente baja).

### Qué debe demostrar cada prueba

| Prueba | Qué demuestra | Criterios |
|---|---|---|
| `BrevoResponseClassifierTest` | Las quince filas de la tabla de clasificación, incluidas las de transporte. | FR-006, SC-005 |
| `BrevoNotificationProviderTest` | Construcción de la petición contra el servidor simulado: destinatario, asunto, cuerpo, remitente, cabecera `api-key`, clave de idempotencia; adaptador deshabilitado; notificación sin asunto; tiempo de espera con **aserción explícita de `Duration`**. | FR-001, FR-004, FR-007, FR-009, FR-012, SC-006, SC-008, SC-009 |
| `BrevoEmailDeliveryE2ETest` | Flujo completo `POST /notifications` → RabbitMQ → despacho → proveedor: la notificación queda entregada con `providerId = brevo` y el servidor simulado recibió exactamente una petición; con el proveedor deshabilitado la notificación sigue `PENDING`, sin intentos, y el mensaje llega a la DLQ con el motivo; ni los registros ni los mensajes publicados contienen la clave, el asunto, el cuerpo ni la dirección. | FR-001, FR-005, FR-008, FR-010, SC-001, SC-003, SC-004 |
| `ChannelCatalogSeederTest` (ampliada) | El canal `EMAIL` sembrado desde cero lista `brevo`. | FR-002, SC-002 |
| Suite existente completa | Sin regresiones con el valor por defecto de la configuración. | FR-013, SC-007 |

---

## 2. Validación manual en local (opcional, sin cuenta real)

```bash
docker compose up -d mongodb rabbitmq
./mvnw -pl infrastructure spring-boot:run
```

Sin `BREVO_API_KEY` ni `BREVO_SENDER_EMAIL`, en el arranque debe aparecer **un** registro `WARN` que
nombre al proveedor `brevo` y el motivo (qué variable falta), **sin ningún valor de credencial**. El
canal `EMAIL` sigue enviando por el proveedor simulado, así que el flujo normal no cambia:

```bash
curl -s -X POST http://localhost:8060/notifications \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-1' \
  -d '{"externalId":"humo-local-1","channelType":"EMAIL","recipientId":"recipient-1",
       "recipientAddress":"alice@example.com","subject":"Prueba","body":"Hola",
       "priority":"NORMAL"}'
```

Para ver el camino de proveedor deshabilitado, arranca con
`NOTIFICATION_EMAIL_PROVIDERS=brevo` y sin credenciales: la notificación debe quedarse en `PENDING`,
sin intentos, y el mensaje debe terminar en `notification.dispatch.dlq.queue` con el motivo en el header
de causa.

---

## 3. Prueba manual de humo con cuenta real (FUERA de CI)

> **Nunca** se ejecuta en integración continua ni desde una prueba automatizada. Requiere una cuenta
> real del proveedor, consume cuota y envía correo de verdad.

### Prerrequisitos

- Cuenta del proveedor con una clave de API activa.
- Un remitente **verificado** en esa cuenta.
- Un buzón de destino al que tengas acceso.

### Procedimiento

1. Exportar las variables (nunca escribirlas en un archivo versionado):

   ```bash
   export BREVO_API_KEY='<clave real>'
   export BREVO_SENDER_EMAIL='<remitente verificado>'
   export BREVO_SENDER_NAME='Notification UCO'
   export NOTIFICATION_EMAIL_PROVIDERS='brevo,simulated'
   ```

2. Levantar dependencias y arrancar el servicio:

   ```bash
   docker compose up -d mongodb rabbitmq
   ./mvnw -pl infrastructure spring-boot:run
   ```

3. Comprobar en el arranque que **no** aparece el aviso de proveedor deshabilitado.

4. Si el Mongo local ya tenía catálogo sembrado, actualizar el canal `EMAIL` a mano (ver sección 4);
   si no, el catálogo se siembra solo y ya lista `brevo`.

5. Aceptar una notificación hacia un buzón real:

   ```bash
   curl -s -X POST http://localhost:8060/notifications \
     -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-humo' \
     -d '{"externalId":"humo-real-1","channelType":"EMAIL","recipientId":"humo-1",
          "recipientAddress":"<tu buzon real>","subject":"Humo HU2-089",
          "body":"Prueba manual de humo del proveedor real.","priority":"NORMAL"}'
   ```

6. Verificar, en este orden:
   - llega el correo al buzón, con el remitente verificado;
   - `GET /notifications/{id}` devuelve estado `DELIVERED` y `providerId` `brevo`;
   - el registro del despacho **no** contiene la clave, el asunto, el cuerpo ni la dirección;
   - la cuenta del proveedor muestra el envío en su historial.

7. **Comprobación de la clave de idempotencia** (el punto que la documentación del proveedor no
   resuelve, ver `research.md`, Decisión 8): forzar un segundo despacho de **la misma** notificación
   (reencolar su `notificationId` en `notification.dispatch.queue`) y anotar si llega **un** correo o
   **dos**.
   - Si llega uno: el proveedor deduplica por esa clave — anotarlo y cerrar el riesgo residual.
   - Si llegan dos: el proveedor **no** deduplica — el riesgo de duplicado sigue abierto y debe
     escalarse como historia propia (deduplicación del lado del componente).

8. Limpiar: `unset BREVO_API_KEY BREVO_SENDER_EMAIL BREVO_SENDER_NAME NOTIFICATION_EMAIL_PROVIDERS`.

### Registro del resultado

Anotar aquí cada ejecución (este archivo está versionado; **nunca** pegar la clave):

| Fecha | Quién | Commit | Correo recibido | Estado final | ¿Deduplica la clave? | Notas |
|---|---|---|---|---|---|---|
| *(pendiente)* | | | | | | |

---

## 4. Actualizar el catálogo en un entorno ya sembrado

`ChannelCatalogSeeder` siembra **solo si la colección está vacía**, así que listar `brevo` en la
configuración no actualiza por sí solo un entorno que ya tenía catálogo. En ese caso, actualizar el
documento a mano:

```javascript
// mongosh, base de datos del componente
db.channel_catalog.updateOne(
  { _id: "EMAIL" },
  { $set: { providers: ["simulated", "brevo"] } }
)
```

El refresco de la caché es periódico (`NOTIFICATION_CATALOG_REFRESH_INTERVAL_MS`, 30 s por defecto), así
que el cambio se ve sin reiniciar. Limitación conocida, con dueño y fecha en `research.md`, Decisión 3.

---

## 5. Advertencias operativas

- **No habilitar** `logging.level.reactor.netty.http.client=DEBUG` ni `wiretap` en ningún entorno con
  credenciales reales: volcaría la cabecera `api-key` y el cuerpo del correo al registro. El código no
  puede impedirlo; es una regla de operación.
- La superficie expuesta del actuator es la de por defecto (solo `health`). Si algún día se expone
  `configprops` o `env`, verificar que la propiedad siga llamándose `api-key` para que el saneamiento
  por nombre siga aplicando.
- Las credenciales se entregan por variable de entorno. Es insuficiente para producción; ver HU2-052 en
  `research.md`, Decisión 13, con dueño y fecha.
