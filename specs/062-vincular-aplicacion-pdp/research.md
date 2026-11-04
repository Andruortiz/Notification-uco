# Research: HU2-146

Base: [028 research](../028-aprovisionar-pdp/research.md). No se ejecutó ninguna llamada contra un PDP real.

## D1. Qué es la "aplicación" y cómo se identifica

- Decisión: se guarda el `id` que devuelve `GET /api/v1/applications` del PDP (campo `id` de cada elemento de `data.content`), más el `name` como dato de lectura. Comparación por `id`.
- Razón: el criterio de la historia pide el mismo valor que el PDP; roles y perfiles del PDP ya referencian `applicationId`.
- Alternativa: usar `client_id`; descartada hasta que Seguridad confirme la equivalencia (pregunta abierta 1).

## D2. Cómo se comprueba la existencia

- Decisión: `GET /api/v1/applications?name=...` no sirve para buscar por id; se pagina `GET /api/v1/applications?offset&limit=100` con la cookie del administrador y se busca el `id` (tope de 5 páginas, luego "no encontrada"). Si al implementar se confirma `GET /api/v1/applications/{id}`, se usa ese y se retira la paginación.
- Razón: único endpoint verificado por descompilación. 404/vacío es inexistente, 401/403 es prohibido, timeout o 5xx es indisponible.
- Alternativa: token técnico; no existe en el PDP 0.1.6 (pregunta abierta de Seguridad).

## D3. Alcance de "configuración de negocio"

- Decisión: esta historia entrega solo el vínculo (ancla) y su auditoría. Plantillas por aplicación no existen en el código y los parámetros actuales son globales/por canal/por proveedor (`ParameterScope`).
- Alternativa: añadir `applicationId` a los parámetros; descartada: exige modelar configuración por aplicación, historia propia.

## D4. Atomicidad alta + auditoría

- Decisión: insertar el vínculo (índice único `(tenantId, applicationId)`), luego insertar la auditoría; si la auditoría falla, borrar el vínculo recién creado y responder error. Si el insert choca con el índice, devolver el existente sin auditar. La baja audita primero y borra después; si el borrado falla, queda una auditoría sin baja, que se documenta como ruido conservador.
- Razón: Mongo sin transacciones garantizadas; la auditoría de solo agregado nunca pierde un cambio confirmado.
- Alternativa: transacción multi-documento; descartada por requerir réplica y no estar en la infraestructura actual.

## D5. Contrato

- Decisión: `POST /application-bindings` (cuerpo `{applicationId}`), `GET /application-bindings`, `DELETE /application-bindings?applicationId=`, `GET /application-bindings/audit`. Todas ADMINISTRADOR. La cookie se lee en el controller y viaja en el comando; no se loguea.
- Enlace de gestión: `managementUrl` en cada vínculo, de `pdp.admin.console-url` (vacío si no está configurado).

## Preguntas abiertas para Seguridad

1. ¿`applicationId` = `client_id`?
2. ¿Una aplicación puede estar en varios tenants? (el plan lo permite).
3. ¿Habrá credencial técnica para llamar al PDP sin sesión de administrador?
4. ¿`GET /api/v1/applications/{id}` existe y qué responde a un no administrador?
