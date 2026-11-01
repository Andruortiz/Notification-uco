# 2. Componentes — arquitectura hexagonal

```mermaid
flowchart TB
    subgraph CORE["core -- dominio + casos de uso + puertos, sin Spring"]
        direction TB
        DOM["domain: Notification, VOs, policies, eventos"]
        UC["usecase: SendNotificationService, SendNotificationBatchService, DispatchNotificationService, GetNotificationStatusService, RequeuePendingNotificationsService"]
        PIN["port.in: SendNotificationUseCase, SendNotificationBatchUseCase, DispatchNotificationUseCase, GetNotificationStatusUseCase, RequeuePendingNotificationsUseCase"]
        POUT["port.out: ChannelCatalogPort, NotificationSenderPort, NotificationEventPublisherPort"]
        REPO["repository: NotificationRepository"]
        UC --> DOM
        UC --> POUT
        UC --> REPO
        PIN -.implementado por.-> UC
    end

    subgraph INFRA["infrastructure -- adaptadores + wiring"]
        direction TB
        REST["adapter.in.rest: NotificationController"]
        RABBITIN["adapter.in.rabbit: NotificationDispatchListener"]
        SCHED["adapter.in.scheduler: PendingNotificationSchedulerAdapter"]
        MONGO["adapter.out.mongo: NotificationMongoAdapter"]
        RABBITOUT["adapter.out.rabbit: NotificationRabbitPublisher"]
        CATALOG["adapter.out.catalog: ConfigurationChannelCatalogAdapter"]
        PROVIDER["adapter.out.provider: SimulatedNotificationProvider"]
        CONFIG["config: UseCaseConfig, RabbitConfig"]
    end

    UTILS["utils: Preconditions"]

    REST --> PIN
    RABBITIN --> PIN
    SCHED --> PIN
    MONGO -.implementa.-> REPO
    RABBITOUT -.implementa.-> POUT
    CATALOG -.implementa.-> POUT
    PROVIDER -.implementa.-> POUT
    CONFIG --> UC
    CONFIG --> REST
    CONFIG --> RABBITIN
    CONFIG --> SCHED

    CORE --> UTILS
    INFRA --> UTILS

    MONGODB[(MongoDB)]
    RABBITMQ{{RabbitMQ}}
    CLIENT["Sistema cliente"]

    CLIENT -->|HTTPS| REST
    MONGO --> MONGODB
    RABBITOUT --> RABBITMQ
    RABBITMQ --> RABBITIN
```

**Nota (2026-09-11):** actualizado respecto a la versión de Notion (2026-09-04) para incluir
`RequeuePendingNotificationsService`/`RequeuePendingNotificationsUseCase` y el adaptador
`adapter.in.scheduler` (HU2-030). Revisar el resto antes de darlo por vigente.
