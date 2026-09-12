# Quickstart: Catálogo de canales persistido en Mongo

Guía de validación manual end-to-end — no reemplaza la prueba automatizada exigida en `tasks.md`, sirve para comprobar el flujo completo con la app corriendo de verdad.

## Prerrequisitos

- `docker compose up -d mongodb rabbitmq` (infraestructura local)
- Variables de entorno mínimas ya documentadas en `.env.example`
- `mongosh` disponible para inspeccionar/editar la colección directamente

## Escenario 1 — La migración deja el catálogo con el mismo comportamiento observable (FR-006, SC-002)

1. Arranca el servicio contra un Mongo vacío (primera vez en este ambiente):
   ```bash
   ./mvnw -pl infrastructure spring-boot:run
   ```
2. Verifica que la migración corrió y sembró la entrada que hoy existe en `application.yml`:
   ```bash
   mongosh "mongodb://$MONGO_USERNAME:$MONGO_PASSWORD@localhost:27018/notification?authSource=admin" \
     --eval "db.channel_catalog.find().pretty()"
   ```
   Debe mostrar un documento `{ _id: "EMAIL", providers: ["simulated"], contentSchema: ... }`.
3. Acepta una notificación de canal `EMAIL` como siempre (`POST /notifications`) y confirma que se despacha con normalidad — el comportamiento no cambió respecto a antes de esta historia.

## Escenario 2 — Cambiar el catálogo sin redeploy (US1, criterio de aceptación 2)

1. Con el servicio ya corriendo, agrega un proveedor nuevo directamente en Mongo:
   ```bash
   mongosh "mongodb://$MONGO_USERNAME:$MONGO_PASSWORD@localhost:27018/notification?authSource=admin" \
     --eval 'db.channel_catalog.updateOne({_id:"EMAIL"},{$set:{providers:["simulated","otro-proveedor"]}})'
   ```
2. Espera el intervalo de refresco configurado (`notification.catalog.refresh-interval-ms`, por defecto 30s).
3. Acepta una notificación de canal `EMAIL` y confirma en los logs/estado que el proveedor preferido sigue siendo `simulated` (primero en la lista) pero que la ruta ahora tiene dos proveedores candidatos — sin haber reiniciado el proceso.

## Escenario 3 — Resiliencia ante caída de Mongo (US2)

1. Con el servicio ya corriendo y el catálogo ya cargado al menos una vez, detén Mongo:
   ```bash
   docker compose stop mongodb
   ```
2. Acepta y despacha una notificación de canal `EMAIL` — debe resolver la ruta con normalidad usando la última configuración conocida, sin fallar por la caída de Mongo.
3. Reinicia Mongo (`docker compose start mongodb`) y espera el intervalo de refresco — confirma en los logs que el refresco se reanuda sin error.

## Qué prueba esto

Confirma en la app real (no solo en tests) los criterios de aceptación de `spec.md`: comportamiento equivalente tras la migración (US1, escenario 4), cambios sin redeploy (US1, escenario 2), y resiliencia ante caída temporal de la base de datos (US2, escenarios 1-2).
