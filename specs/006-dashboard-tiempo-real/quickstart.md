# Quickstart: Ver notificaciones en tiempo real en el dashboard

Guía de validación manual una vez implementada la historia. No sustituye la prueba E2E automatizada
(`NotificationLiveUpdatesE2ETest`) — es para confirmar visualmente el comportamiento contra un backend
real, ya que el dashboard (`Front-Notification`) es un repositorio separado y no está disponible aquí.

## Prerrequisitos

- `docker compose up -d mongodb rabbitmq`.
- El servicio corriendo con `./mvnw -pl infrastructure spring-boot:run`.
- Una herramienta que pueda consumir un stream SSE y mostrar cada evento a medida que llega — `curl -N`
  sirve para esto (no bufferiza), o el panel de Network del navegador contra un `EventSource` simple.

## Escenario: ver una notificación cambiar de estado en vivo (US1)

1. Abrir el stream antes de que exista actividad:
   ```bash
   curl -N -H "X-Tenant-Id: tenant-demo" "http://localhost:8060/notifications:subscribe"
   ```
   Confirmar que la conexión queda abierta (no se cierra sola) y que, si no hay notificaciones aún,
   no llega ningún evento de datos (solo, eventualmente, comentarios de keep-alive).
2. En otra terminal, aceptar una notificación:
   ```bash
   curl -X POST http://localhost:8060/notifications \
     -H "X-Tenant-Id: tenant-demo" -H "Content-Type: application/json" \
     -d '{"externalId":"live-demo-1","channelType":"EMAIL","recipientId":"r1","content":"hola"}'
   ```
3. En la terminal del `curl -N`, confirmar que llega un evento `UPSERT` para esa notificación en
   segundos (SC-001), y que — a medida que el proveedor simulado la despacha — llegan eventos
   adicionales reflejando su estado final (`DELIVERED` o `RECOVERABLE`/`FAILED` según
   `SIMULATED_PROVIDER_RESULT`), cada uno con el historial de intentos acumulado hasta ese momento.
4. Forzar un fallo transitorio (`SIMULATED_PROVIDER_RESULT=RECOVERABLE_FAILURE`) y dejar que el
   scheduler de reencolado la reintente; confirmar que llega un evento `UPSERT` cuando pasa a
   `RECOVERABLE`, otro cuando se reencola a `PENDING`, y otro con el resultado final del reintento —
   sin haber hecho ninguna solicitud manual adicional.

## Escenario: la vista filtrada se actualiza sola (US2)

1. Abrir el stream con un filtro:
   ```bash
   curl -N -H "X-Tenant-Id: tenant-demo" "http://localhost:8060/notifications:subscribe?status=FAILED"
   ```
2. Aceptar y dejar fallar definitivamente una notificación distinta (agotando reintentos) — confirmar
   que aparece un evento `UPSERT` cuando pasa a `FAILED`, aunque el stream se abrió antes de que
   existiera.
3. Con otra conexión filtrada por `status=RECOVERABLE`, forzar que una notificación visible en esa
   vista pase a `DELIVERED` — confirmar que llega un evento `REMOVE` para esa notificación en esa
   conexión (ya no cumple el filtro), aunque su fila técnicamente sigue existiendo con otro estado.

## Escenario: resincronización tras reconexión (US3)

1. Abrir el stream, dejar que se acumulen 2-3 notificaciones en distintos estados.
2. Cerrar la conexión (`Ctrl+C` sobre el `curl -N`) simulando una caída.
3. Mientras está "desconectado", cambiar el estado de una de esas notificaciones (ej. forzar un
   reintento).
4. Reconectar con el mismo filtro:
   ```bash
   curl -N -H "X-Tenant-Id: tenant-demo" "http://localhost:8060/notifications:subscribe"
   ```
5. Confirmar que la primera ráfaga de eventos `UPSERT` recibidos refleja el estado **actual** de cada
   notificación (incluyendo el cambio ocurrido durante la desconexión), no el estado que tenían antes
   de cerrar la conexión — sin que se haya perdido ese cambio.

## Aislamiento por tenant

Repetir cualquiera de los escenarios anteriores con dos valores de `X-Tenant-Id` distintos en paralelo
y confirmar que ningún evento de un tenant llega jamás al stream abierto con el `X-Tenant-Id` del
otro, sin importar los filtros usados (FR-002).

## Multi-réplica (validación de la Decisión 2 de research.md)

Si se levantan dos instancias del servicio contra el mismo RabbitMQ/MongoDB (puertos distintos),
conectar un stream a cada una con el mismo `X-Tenant-Id` y confirmar que una notificación aceptada
contra **cualquiera** de las dos instancias produce el evento correspondiente en **ambos** streams —
confirma que el fanout entrega a cada réplica, no a una sola.
