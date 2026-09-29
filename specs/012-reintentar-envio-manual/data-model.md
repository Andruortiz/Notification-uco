# Data Model: Reintentar envío manualmente (HU2-027 + HU2-028)

**Feature**: 012-reintentar-envio-manual | **Date**: 2026-09-26

**Sin cambios en el modelo persistido.** Ni `NotificationDocument` ni `DeliveryAttemptDocument` cambian;
`DeliveryAttempt.origin` ya se guarda y se expone. Lo que cambia es quién produce `MANUAL` y por dónde viaja
ese dato hasta el intento.

## Agregado `Notification` (sin cambios de código)

Transiciones relevantes, tal como están en `StatusTransitionPolicy`:

| Desde | Hacia | Quién |
|---|---|---|
| `FAILED` | `PENDING` | reintento manual (**nuevo uso**) |
| `RECOVERABLE` | `PENDING` | reintento automático (existente) o reintento manual (**nuevo uso**) |
| `PENDING` | `IN_PROCESS` | despacho (existente) |
| `IN_PROCESS` | `DELIVERED` / `RECOVERABLE` / `FAILED` | despacho (existente); el intento registrado lleva el origen recibido |

`PENDING`, `IN_PROCESS`, `DELIVERED`, `DISCARDED` → el reintento manual responde 400 sin mutar.

## `DeliveryAttempt` (sin cambios de código)

| Campo | Regla nueva |
|---|---|
| `origin` | `MANUAL` si el intento lo produjo un mensaje de despacho encolado por un reintento manual; `AUTOMATIC` en cualquier otro caso (despacho inicial, reintento automático, recuperación de huérfanos, mensajes sin encabezado). |

## Mensaje de despacho (RabbitMQ, cola `notification.dispatch.queue`)

| Parte | Antes | Ahora |
|---|---|---|
| Cuerpo | `notificationId` (texto) | sin cambios |
| `messageId` | `notificationId` | sin cambios |
| Encabezado `x-attempt-origin` | — | `MANUAL` o `AUTOMATIC`; siempre presente en mensajes nuevos. Ausente → `AUTOMATIC`. Valor desconocido → mensaje inválido (agota reintentos y va a la DLQ). |

## Tipos nuevos en `core`

| Tipo | Paquete | Forma |
|---|---|---|
| `RetryNotificationCommand` | `port.in` | record `(TenantId tenantId, NotificationId notificationId)`, ambos obligatorios |
| `RetryNotificationUseCase` | `port.in` | `Mono<NotificationStatusView> retry(RetryNotificationCommand command)` |
| `RetryNotificationService` | `usecase` | implementa el anterior con `NotificationRepository` + `NotificationEventPublisherPort` |
| `NotificationNotRetryableException` | `exception` | `RuntimeException`; mensaje `"Notification <id> cannot be retried from status <STATUS>"` → 400 |

## Firmas que cambian en `core`

| Antes | Ahora |
|---|---|
| `DispatchNotificationUseCase.dispatch(NotificationId)` | `dispatch(NotificationId, AttemptOrigin)` |
| `NotificationEventPublisherPort.enqueueForDispatch(Notification)` | `enqueueForDispatch(Notification, AttemptOrigin)` |
| `GetNotificationStatusService.toView` (privado) | `NotificationStatusView.from(Notification)` (fábrica estática compartida) |
