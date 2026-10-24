# Diagramas — Notification-uco

Diagramas de arquitectura en Mermaid — texto plano, editable directamente y renderizable en GitHub,
en la mayoría de editores, o en cualquier herramienta compatible con Mermaid. Portados desde Notion
el 2026-09-11; reflejaban el estado del código al 2026-09-04 — revisar si algo cambió desde
entonces antes de darlos por vigentes.

1. [`01-modelo-de-dominio.md`](01-modelo-de-dominio.md) — clases del dominio (`Notification`, VOs,
   políticas, eventos)
2. [`02-arquitectura-hexagonal.md`](02-arquitectura-hexagonal.md) — componentes `core`/`infrastructure`
3. [`03-contenedores-c4.md`](03-contenedores-c4.md) — C4 Nivel 2, mismo estilo que usa el equipo de
   Seguridad
4. [`04-secuencia-envio-despacho.md`](04-secuencia-envio-despacho.md) — secuencia completa de enviar
   y despachar una notificación
5. [`secuencias/`](secuencias/README.md) — diagramas de secuencia por flujo (draw.io), con el índice de
   flujos dibujados y pendientes

Si el dominio o la arquitectura cambian, actualizar el diagrama correspondiente en el mismo PR que
hace el cambio — no dejarlo para después.
