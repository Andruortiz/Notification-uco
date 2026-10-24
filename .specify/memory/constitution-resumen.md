# Resumen de la constitución (v2.0.1)

Resumen para lectura rápida. La fuente vinculante es `constitution.md`; léela completa solo si una decisión depende del texto exacto o si este resumen no alcanza.

## Principios
- **I. Hexagonal (no negociable):** `core` sin Spring ni frameworks; todo lo externo pasa por puertos; `HexagonalArchitectureTest` lo verifica.
- **II. Contract-first, rutas literales:** el endpoint se define antes en `api-notificaciones.yaml`; rutas solo con `/`, letras, dígitos y `. _ ~ -` (sin `:` ni `{}`); ids en query string o cuerpo; cada operación con `x-status` y `x-minimum-role`.
- **III. Cero comentarios explicativos:** solo Javadoc de API pública si una herramienta lo exige; el razonamiento va en spec/plan/PR.
- **IV. Calidad verificada:** CI en verde, cobertura ≥80 % líneas y ≥70 % ramas, SpotBugs + FindSecBugs + Spotless, y al menos una prueba E2E por historia. La unitaria no sustituye la E2E.
- **V. Trazabilidad en git:** commits de una línea `tipo(ámbito): descripción`, rama `feature/HU2-XXX-descripcion`; razonamiento en el PR o en `specs/`; la IA nunca mergea.
- **VI. IA gobernada por spec-kit:** specify → plan → tasks → implement; el usuario aprueba constitución, spec y plan antes de implementar.
- **VII. Sin atajos:** nada temporal es definitivo; lo pendiente se documenta como excepción con dueño y fecha.
- **VIII. Confiabilidad:** una notificación aceptada se persiste antes de responder; todo fallo posterior queda recuperable, en reintento o terminal trazable; agotadas van a DLQ; nunca se pierde en silencio.
- **IX. Observabilidad:** el recorrido completo es reconstruible con correlación, tenant y notificationId, sin secretos.

## Restricciones técnicas clave
- Java 25, Spring Boot 4, WebFlux/Reactor, MongoDB, RabbitMQ; canales y proveedores por catálogo y adaptadores sin tocar el núcleo.
- Sin credenciales en código, logs, respuestas HTTP, mensajes ni artefactos de CI; la config no sensible se versiona y los secretos llegan por variables de entorno.
- Logs JSON estructurados con correlación, `tenantId` y `notificationId`; métricas técnicas y de negocio expuestas.
- Varias réplicas: bloqueo optimista con `version`, único `(tenantId, externalId)` en base de datos, "tomar" recursos con actualización atómica condicionada, despacho idempotente ante redelivery.
- RabbitMQ: ack manual solo tras persistir el resultado; los mensajes agotados van a DLQ con la causa.
- Arranque ≤30 s, apagado ordenado, sondas liveness y readiness reales en `/actuator/health`.
- Caché local por réplica con invalidación por evento + TTL; estado estrictamente consistente (límite de tasa) en almacén compartido.

## Flujo
- Rama por historia; artefactos de spec-kit en la misma rama que el código; `tasks.md` siempre con una tarea E2E explícita.
- Base de ramas y PR: `main` (trunk-based), vigente desde 2026-10-14.
