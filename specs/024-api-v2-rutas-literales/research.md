# Research: Contrato v2 con rutas literales (HU2-098)

## Decisión 1: corte directo frente a alias o prefijo `/v2`

- **Decisión**: corte directo; `info.version` 2.0.0; sin alias ni prefijo.
- **Motivo**: sin clientes en producción; el PDP resuelve por igualdad exacta y un alias con `{}` no es registrable;
  coexistir duplicaría recursos y pruebas.
- **Alternativas**: alias temporales (rechazado: rutas no protegibles servidas, guardia (c) con excepciones); prefijo
  `/v2` (rechazado: duplica el catálogo del PDP sin necesidad).

## Decisión 2: identificadores en query o cuerpo

- **Decisión**: consultas (GET) llevan el identificador en la query; comandos (POST/PUT) lo llevan en el cuerpo, salvo la
  actualización de preferencias (PUT con `recipientId` en query y preferencias en el cuerpo, para que el cuerpo conserve la
  forma de `UpdatePreferencesRequest`).
- **Motivo**: el starter envía `getRawPath()` sin query; la ruta registrada queda literal.

## Decisión 3: operaciones sin implementar

- **Decisión**: `x-status: planned`, con ruta v2 definitiva y rol mínimo.
- **Alternativa**: retirarlas hasta que existan (rechazado: los frontends pierden tipos y HU2-099 no puede preparar los
  recursos).
- **Garantía**: la prueba (a) falla si una `planned` aparece servida, forzando a actualizar el contrato al implementarla.

## Decisión 4: forma de los errores del PEP

- **Decisión**: `PepErrorResponse` aparte de `ErrorResponse`; 401/403 admiten ambas formas por el modo interino.
- **Pendiente verificable**: el cuerpo exacto del starter 0.1.3 (T-07). Solo se tiene como evidencia verificada el
  conjunto de códigos y la cabecera `X-Decision-Id`.

## Decisión 5: la lista canónica sale del contrato

- **Decisión**: `x-minimum-role` y `x-status` por operación; derivación por prueba a un JSON de build.
- **Motivo**: una sola fuente evita la divergencia contrato / política / PDP.

## Hallazgos del código que condicionan el plan

- El contrato 3.1.0 usa `nullable: true` (sintaxis 3.0) en 19 sitios; en 3.1 corresponde `type: [x, "null"]`. Se corrige en
  los esquemas tocados.
- `AttachmentUploadResponse` usa `@JsonInclude(NON_NULL)`; los demás records no, por lo que sus nulos se serializan como
  `null`. La prueba (e) debe distinguir entre "ausente" y "nulo".
- La métrica HTTP etiqueta `uri` por plantilla de ruta; el cambio de ruta modifica ese valor.

## Verificacion T-07: forma de los errores del PEP (starter 0.1.3, leida del jar)

- Clase `PepEnforcementWebFilter`, metodo `write`. Cuerpo JSON armado como
  `{"type":"urn:security-pep:error:<code en minusculas>","title":"<code>","status":<n>,"detail":"<reason phrase HTTP>","code":"<code>"}`.
- `Content-Type: application/problem+json`, `Cache-Control: no-store`; en 401 anade `WWW-Authenticate: Bearer`.
- Estados: `TOKEN_INVALID` 401, `ACCESS_DENIED` 403, `PEP_UNAVAILABLE` 503. La cabecera `X-Decision-Id` se anade cuando el PEP
  la devuelve con la decision.
- Una solicitud sin credenciales utilizables (ni `Bearer` ni cookie `SECURITY_BASELINE_SESSION=`) se rechaza localmente con
  `TOKEN_INVALID` sin consultar al PEP.
- El esquema `PepErrorResponse` del contrato refleja estos cinco campos.
