# ADR-0004 — MongoDB gestionado fuera de local

**Estado**: Aceptado
**Referenciado en**: `.specify/memory/constitution.md` ("Restricciones técnicas")

## Contexto

El componente persiste notificaciones y su historial de intentos en MongoDB. En cualquier ambiente
distinto a desarrollo local, la base de datos no debe ser administrada por el propio componente.

## Decisión

MongoDB gestionado externamente (por la plataforma) en todo ambiente distinto a desarrollo local.
En desarrollo local, `docker-compose` levanta una instancia propia.

## Consecuencias

- El componente nunca administra respaldos, alta disponibilidad ni parcheo de MongoDB directamente
  — eso es responsabilidad de la plataforma en los ambientes gestionados.
- Las credenciales de conexión siempre vienen por variables de entorno (`MONGO_USERNAME`,
  `MONGO_PASSWORD`, etc.), nunca hardcodeadas — coherente con RNF-09.
- En desarrollo local, `docker-compose up -d mongodb` es responsabilidad del propio desarrollador.

## Alternativas consideradas

- **MongoDB autoadministrado por el componente en todo ambiente** (incluyendo producción):
  descartada — duplicaría trabajo operativo que la plataforma ya resuelve de forma centralizada para
  todos los componentes de la organización, y añadiría riesgo de una configuración de respaldo mal
  hecha por el propio equipo.
