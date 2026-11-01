# Requisitos — Notification-uco

Transcrito desde la línea base v1.0 (documento Word, 26/07/2026), secciones 5.2 y 5.3, el
2026-09-11. Es el listado formal de requisitos que los casos de uso (`docs/casos-de-uso.md`) y los
ADR citan por número en sus columnas de Trazabilidad.

## 5.2 Requisitos funcionales

| Código | Descripción | Prioridad |
|---|---|---|
| RF-01 | Recibir una solicitud de notificación individual y devolver un identificador de seguimiento. | Alta |
| RF-02 | Recibir un lote de notificaciones en una sola solicitud y registrarlo como unidad. | Alta |
| RF-03 | Validar la solicitud contra el esquema declarado para el canal indicado. | Alta |
| RF-04 | Rechazar la solicitud con un error descriptivo cuando el canal no exista o el contenido no cumpla su esquema. | Alta |
| RF-05 | Persistir la notificación con su estado inicial antes de confirmar la recepción. | Alta |
| RF-06 | Encolar la notificación respetando su prioridad. | Alta |
| RF-07 | Determinar el proveedor que atenderá cada notificación consultando el catálogo. | Alta |
| RF-08 | Ejecutar el envío a través del adaptador del proveedor seleccionado. | Alta |
| RF-09 | Registrar el resultado de cada envío y actualizar el estado de la notificación. | Alta |
| RF-10 | Clasificar los fallos en recuperables y definitivos. | Alta |
| RF-11 | Reintentar automáticamente los envíos con fallo recuperable, con espera creciente y un máximo de intentos. | Alta |
| RF-12 | Conmutar al siguiente proveedor del canal cuando el preferente falla. | Media |
| RF-13 | Aislar las notificaciones con fallo definitivo para su revisión posterior. | Alta |
| RF-14 | Reencolar automáticamente las notificaciones que quedaron sin despachar. | Media |
| RF-15 | Permitir al operador ordenar el reintento manual de una notificación. | Media |
| RF-16 | Consultar el estado actual de una notificación por su identificador. | Alta |
| RF-17 | Consultar el histórico y la trazabilidad completa de una notificación. | Alta |
| RF-18 | Consultar el estado agregado de un lote. | Media |
| RF-19 | Registrar un canal nuevo con su esquema de validación, sin desplegar código. | Alta |
| RF-20 | Registrar un proveedor y asociarlo a uno o varios canales, con su orden de preferencia. | Alta |
| RF-21 | Habilitar o deshabilitar canales y proveedores sin reiniciar el servicio. | Media |
| RF-22 | Gestionar las preferencias de contacto y la baja voluntaria del destinatario. | Media |
| RF-23 | Evitar que una solicitud repetida con el mismo identificador externo genere un envío duplicado. | Alta |
| RF-24 | Actualizar la configuración operativa al recibir un cambio publicado por el Componente de Parámetros. | Media |
| RF-25 | Resolver el contenido del mensaje a partir de una plantilla parametrizable. | Baja |
| RF-26 | Programar el envío de una notificación para un momento futuro. | Baja |

## 5.3 Requisitos no funcionales

| Código | Atributo | Criterio medible |
|---|---|---|
| RNF-01 | Disponibilidad | Igual o superior al 99,5 % mensual. |
| RNF-02 | Latencia de aceptación | La solicitud se acepta en 200 ms o menos en el percentil 95; la entrega es posterior y asíncrona. |
| RNF-03 | Rendimiento | Al menos 500 notificaciones por minuto por réplica. |
| RNF-04 | Escalabilidad | Escalado horizontal sin pérdida ni duplicación de mensajes. |
| RNF-05 | No pérdida | Ninguna notificación aceptada se pierde, ni siquiera ante la caída abrupta de una réplica. |
| RNF-06 | Cobertura de pruebas | Cobertura unitaria igual o superior al 80 %, verificada como puerta bloqueante. |
| RNF-07 | Trazabilidad | El 100 % de las notificaciones es auditable de extremo a extremo. |
| RNF-08 | Extensibilidad | Incorporar un proveedor nuevo toma como máximo una jornada de trabajo. |
| RNF-09 | Seguridad de credenciales | Ninguna credencial reside en el código ni en el repositorio. |
| RNF-10 | Observabilidad | Registros estructurados con identificador de correlación y métricas técnicas y de negocio expuestas. |
| RNF-11 | Operabilidad | Sondas de salud disponibles para la plataforma de contenedores. |
| RNF-12 | Disponibilidad desechable | Arranque en 30 s o menos y apagado ordenado sin perder el trabajo en curso. |
| RNF-13 | Control de acceso | Toda operación de la API exige autenticación y autorización. |
| RNF-14 | Aislamiento | La información de un sistema cliente no es accesible por otro. |
| RNF-15 | Mantenibilidad | El código cumple las reglas de estilo, complejidad y duplicación verificadas por el análisis estático. |

## Estado de cumplimiento — notas rápidas (2026-09-11)

Esto no reemplaza una auditoría formal, es solo lo que ya se sabe de sesiones anteriores:

- **RNF-06** (cobertura ≥80%) — cumplido y verificado (gate bloqueante en CI, `.specify/memory/constitution.md` Principio IV; no tiene ADR propio — es un requisito de arranque del curso, no una decisión deliberada del equipo).
- **RNF-09** (sin credenciales en código) — cumplido (variables de entorno, ver ADR-0004/0005).
- **RNF-10** (logs estructurados + CorrelationId) — **no cumplido**, brecha documentada (cero
  infraestructura de logging en el código a esta fecha).
- **RNF-13/RNF-14** (autenticación + aislamiento multi-tenant) — parcialmente cumplido: el
  aislamiento por `tenantId` existe a nivel de aplicación, pero no está verificado a nivel de índice
  de MongoDB (HU2-043, Fase 7, no construida); la autenticación real depende de CU-10, bloqueada por
  `DEP-01` del equipo de Seguridad.
- **RF-11/RF-14** (reintento automático + reencolado) — cumplido: `DispatchNotificationService`
  (reintento inmediato) + `RequeuePendingNotificationsService` (HU2-030, reencolado periódico).
- **RF-12** (conmutar al siguiente proveedor) — **no cumplido**, marcado como brecha en el propio
  backlog ("RF-12 subestimado como solo configuración").
- El resto no se ha verificado formalmente uno por uno contra el código actual.
