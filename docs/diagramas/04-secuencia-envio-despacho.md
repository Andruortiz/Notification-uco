# 4. Secuencia — enviar y despachar una notificación

> **Obsoleto.** Este diagrama es la versión inicial y ya no coincide con el código (usa `markQueued()`,
> no tiene reserva atómica, ack manual, reintentos ni reencolado). La versión vigente es
> [`secuencias/F01-envio-y-despacho.drawio`](secuencias/F01-envio-y-despacho.drawio).

```mermaid
sequenceDiagram
    participant C as Sistema cliente
    participant API as NotificationController
    participant UC as SendNotificationService
    participant CAT as ChannelCatalogPort
    participant REPO as NotificationRepository
    participant PUB as NotificationEventPublisherPort
    participant MQ as RabbitMQ (cola despacho)
    participant LIS as NotificationDispatchListener
    participant DISP as DispatchNotificationService
    participant SEND as NotificationSenderPort

    C->>API: POST /notifications
    API->>UC: send(command)
    UC->>CAT: findActiveRoute(channel, tenant)
    CAT-->>UC: ChannelRoute
    UC->>UC: ContentSchemaValidator.validate
    UC->>REPO: findByTenantAndExternalId
    REPO-->>UC: vacío (no duplicado)
    UC->>UC: Notification.accept()
    UC->>REPO: save(notification)
    UC->>PUB: publish(eventos)
    UC->>PUB: enqueueForDispatch(notification)
    PUB->>MQ: mensaje con notificationId
    UC-->>API: SendNotificationResult
    API-->>C: 202 Accepted

    MQ->>LIS: onMessage(notificationId)
    LIS->>DISP: dispatch(notificationId)
    DISP->>REPO: findById
    REPO-->>DISP: Notification
    DISP->>CAT: findActiveRoute
    CAT-->>DISP: ChannelRoute
    DISP->>DISP: markQueued()
    DISP->>SEND: send(notification)
    SEND-->>DISP: AttemptResult
    DISP->>DISP: markDelivered / markRecoverable / markFailed
    DISP->>REPO: save(notification)

    C->>API: GET /notifications/{id}
    API->>REPO: (vía GetNotificationStatusService)
    REPO-->>API: NotificationStatusView
    API-->>C: 200 OK
```

**Nota (2026-09-11):** portado tal cual desde Notion (2026-09-04) — no incluye el flujo de
reencolado automático (HU2-030). Revisar si vale la pena un diagrama de secuencia propio para ese
flujo antes de darlo por vigente.
