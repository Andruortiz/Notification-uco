# Roadmap — Notification-uco

Corte: 2026-10-08. Cierre del periodo del proyecto: 2026-11-12.

## Estado real hoy

- `develop` en verde (CI #64). 20 specs: 17 cerradas, 3 con trabajo abierto (013, 017, 018) y la 019 solo con la demo manual cerrada.
- Funciona de punta a punta: envío individual y por lote, despacho por RabbitMQ con reintento, reencolado, DLQ, búsqueda, panel en vivo por SSE, Brevo/Twilio/FCM, adjuntos, catálogo en Mongo con consulta, autenticación interina JWT con roles, logs con correlación, secretos en Key Vault, configuración sincronizada desde Parámetros, observabilidad (puerto 8061, Prometheus), CI/CD con Sonar, Trivy y CodeQL.
- `master` va 66 commits atrás de `develop`.

## Qué dejar listo para la entrega de hoy

| # | Pendiente | Acción | Esfuerzo |
| --- | --- | --- | --- |
| 1 | Versión demostrable | Servicio local aislado con `SPRING_PROFILES_ACTIVE=local`, Mongo, Rabbit y MinIO arriba, y una notificación EMAIL real por Brevo | 1 h |
| 2 | Guion de demo | Envío → despacho → `GET` de estado → búsqueda → panel SSE → fallo y reintento → `/actuator/prometheus` | 1 h |
| 3 | Evidencia de calidad | Última corrida verde de CI, cobertura ≥ 80/70, Spotless, SpotBugs, ArchUnit y Modulith | 30 min |
| 4 | Release | Decidir si se promueve `develop` a `master` antes de presentar | decisión |
| 5 | Trazabilidad | `docs/` (ADR, casos de uso, diagramas) y `specs/` como prueba del proceso spec-kit | 30 min |
| 6 | Honestidad sobre brechas | Mostrar la tabla de "Brechas conocidas" de abajo, no esconderla | 15 min |

Falta definir qué pide exactamente el semillero en esta entrega (demo en vivo, informe, tablero de avance). Si es solo un tablero de avance, basta el resumen de arriba.

## Brechas conocidas (declararlas)

- Failover entre proveedores y circuit breaker (RF-12): hoy el despacho usa solo el proveedor preferente.
- Autenticación real: bloqueada por DEP-01 (Seguridad). La interina cubre desarrollo y pruebas.
- Reintento manual (018) y preferencias del destinatario (013): solo especificados.
- Administración del catálogo (CU-07/08) y rate limit por proveedor y por tenant.
- Pruebas de carga que respalden RNF-03 (≥ 500/min por réplica): el número nunca se midió.
- Despliegue en Kubernetes (Helm, probes, secretos): sin empezar.
- Observabilidad: el catálogo `ErrorCode`, las métricas, el trazado y la correlación con Brevo ya están en `develop` (spec 020). Twilio y FCM no admiten correlación por mensaje.

## Roadmap hasta el 12 de noviembre

### Semana 1 · 9–15 oct — Cerrar deuda y vencimientos

- Cerrar la spec 017: T050, T058 y T065 (E2E de proveedor deshabilitado y cierre de E4/E5), reconciliación de specs 011–018 (T066–T073), y cierre T074–T077.
- Pasar `develop` a `master` con la release.

### Semana 2 · 16–22 oct — Operación de notificaciones

- Spec 018, reintento manual (22 tareas). Decidir antes si el reintento reinicia el contador de intentos; la 017 ya propone el ciclo de intentos (D9).
- Spec 013, preferencias del destinatario (19 tareas). Responder antes las clarificaciones Q1–Q4 de su `spec.md`.

### Semana 3 · 23–29 oct — Resiliencia

- Failover entre proveedores y circuit breaker con Resilience4j sobre `ChannelRoute` ordenada (RF-12).
- Rate limit por proveedor. Decidir si el límite es por proveedor, canal o global y si el exceso queda `RECOVERABLE`.
- Batería de pruebas de contrato de proveedores (`NotificationProviderContractTest`, RNF-08).

### Semana 4 · 30 oct–5 nov — Verificación y despliegue

- Pruebas de carga para RNF-03 y de latencia para RNF-02, con el resultado en el dashboard de métricas.
- Administración del catálogo (CU-07/08) si queda holgura.
- Manifiestos base de Kubernetes, probes sobre `/actuator/health` en 8061 y Secrets. El despliegue real depende de SUP-05 (acceso al clúster).

### Semana 5 · 6–12 nov — Congelamiento

- Sin funcionalidad nueva. Reconciliar `docs/` con el código y actualizar el README.
- Ensayo de la demo final y corrida verde completa de `clean verify`.
- Cerrar o anotar con dueño y fecha las excepciones del Principio VII que sigan abiertas.

## Frontend (repositorio Front-Notification)

Va en paralelo y no bloquea el backend. Prioridad: sincronizar tipos desde el contrato, pruebas con Vitest y MSW, listado con filtros, detalle y panel en vivo. La suscripción SSE con ticket de un solo uso ya existe en el backend (spec 017 E2). El selector de tenant temporal sigue sin construirse.

## Fuera de alcance del periodo

Plantillas (CU-12), programación (CU-13), cuotas por tenant, retención de datos, OpenTelemetry, integración real con Seguridad (DEP-01) y API Gateway. Las excepciones del Principio VII de las specs 017 y 019 vencen el 15 y el 30 de noviembre, después del cierre del periodo. Conviene decidir con el asesor si se traspasan.
