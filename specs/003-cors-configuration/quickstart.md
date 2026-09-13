# Quickstart: CORS para el dashboard de frontend

Guía de validación manual end-to-end — no reemplaza la prueba automatizada exigida en `tasks.md`, sirve para comprobar el flujo completo con la app y el navegador reales.

## Prerrequisitos

- Backend corriendo localmente: `./mvnw -pl infrastructure spring-boot:run` (con Mongo/RabbitMQ ya levantados)
- Frontend (`Front-Notification`, repositorio separado) corriendo con `npm run dev` (Vite, puerto 5173 por defecto)

## Escenario 1 — El dashboard llama la API sin error de CORS (US1, SC-001)

1. Con el backend arrancado, abre la consola de DevTools del navegador en el dashboard (`http://localhost:5173`).
2. Ejecuta desde esa consola una llamada directa (sin el proxy de Vite) a la API real:
   ```js
   fetch('http://localhost:8060/notifications', { headers: { 'X-Tenant-Id': 'demo-tenant' } })
     .then(r => console.log(r.status))
   ```
3. Confirma que la petición completa sin ningún error de CORS en la consola (el navegador puede seguir mostrando un 404/501 si el endpoint todavía no está implementado — eso es un tema aparte; lo que valida este escenario es que la petición **llega y responde**, no queda bloqueada antes de salir del navegador).

## Escenario 2 — Un origen no permitido sí queda bloqueado (SC-002)

1. Sirve cualquier página estática desde un origen distinto (ej. `http://localhost:9999`, con `python -m http.server 9999` en cualquier carpeta).
2. Desde la consola de DevTools de esa página, repite la misma llamada `fetch` del Escenario 1.
3. Confirma que el navegador bloquea la respuesta con un error de CORS en la consola — esto demuestra que la restricción es real, no un permiso abierto de facto.

## Escenario 3 — Cambiar el origen permitido sin recompilar (US2, SC-003)

1. Detén el backend.
2. Arráncalo de nuevo con la variable de entorno `NOTIFICATION_CORS_ALLOWED_ORIGINS` apuntando a otro origen (ej. `NOTIFICATION_CORS_ALLOWED_ORIGINS=http://localhost:4000`).
3. Repite el Escenario 1 desde `http://localhost:5173` (el origen ya no está permitido) — confirma que ahora se bloquea.
4. Repite el Escenario 1 desde `http://localhost:4000` (servido con `python -m http.server 4000`, por ejemplo) — confirma que ahora sí pasa.

## Qué prueba esto

Confirma en el navegador real (no solo en tests) los criterios de aceptación de `spec.md`: un origen permitido puede llamar la API sin bloqueo (US1), uno no permitido queda bloqueado (SC-002), y el origen permitido es configurable sin tocar código (US2, SC-003).
