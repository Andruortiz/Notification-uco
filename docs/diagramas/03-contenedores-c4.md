# 3. Contenedores (C4 Nivel 2) — mismo estilo que usó el equipo de Seguridad

```mermaid
flowchart LR
    CLIENTE["Sistema cliente<br/>Sistema externo<br/>Envía notificaciones y consulta su estado"]
    OPERADOR["Operador<br/>Sistema/actor externo<br/>Consulta estado, reintenta envíos"]

    subgraph SYS["Notification-uco -- MVP técnico"]
        direction TB
        RESTAPI["API REST<br/>Contenedor Spring Boot + WebFlux<br/>POST /notifications, GET /notifications/{id}"]
        DISPATCH["Listener de despacho<br/>Contenedor Spring Boot + WebFlux<br/>Consume la cola de despacho, invoca DispatchNotificationUseCase"]
        PROVIDERADAPT["Adaptador de proveedor<br/>SimulatedNotificationProvider hoy, Brevo u otro a futuro"]
    end

    MONGODB[("MongoDB<br/>Base de datos operada por la plataforma")]
    RABBITMQ{{"RabbitMQ<br/>Broker operado por la plataforma<br/>Cola de despacho + exchange de eventos"}}
    PROVEEDOR["Proveedor(es) de notificación<br/>Sistema externo<br/>Brevo u otro, según canal"]
    SEGURIDAD["Plataforma Central de Seguridad<br/>Sistema externo -- bloqueado DEP-01<br/>PEP/PDP, valida acceso antes de llegar a la API"]

    CLIENTE -->|HTTPS| SEGURIDAD
    OPERADOR -->|HTTPS| SEGURIDAD
    SEGURIDAD -->|Reenvía solo solicitudes permitidas| RESTAPI

    RESTAPI -->|guarda / consulta| MONGODB
    RESTAPI -->|encola para despacho| RABBITMQ
    RESTAPI -.->|publica eventos de dominio| RABBITMQ
    RABBITMQ -->|mensaje de despacho| DISPATCH
    DISPATCH -->|consulta / actualiza| MONGODB
    DISPATCH --> PROVIDERADAPT
    PROVIDERADAPT -->|envía notificación| PROVEEDOR

    classDef external fill:#94a3b8,color:#fff,stroke:#64748b;
    classDef own fill:#2563eb,color:#fff,stroke:#1d4ed8;
    classDef tech fill:#0ea5e9,color:#fff,stroke:#0284c7;

    class CLIENTE,OPERADOR,PROVEEDOR,SEGURIDAD external;
    class RESTAPI,DISPATCH,PROVIDERADAPT own;
    class MONGODB,RABBITMQ tech;
```

**Nota (2026-09-11):** portado tal cual desde Notion (2026-09-04) — no incluye todavía el disparador
programado de HU2-030 como contenedor propio; revisar si amerita agregarse antes de darlo por
vigente.
