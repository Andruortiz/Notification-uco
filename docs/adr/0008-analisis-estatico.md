# ADR-0008 — SpotBugs + FindSecBugs + Spotless bloqueantes

**Estado**: Aceptado
**Referenciado en**: `.specify/memory/constitution.md` Principio IV

## Contexto

Sostener código limpio y sin vulnerabilidades conocidas no puede depender solo de la disciplina
manual de revisión.

## Decisión

Análisis estático (SpotBugs + FindSecBugs) y formato (Spotless, Google Java Format) bloqueantes en
el pipeline desde el primer commit — un hallazgo bloqueante falla el build, no solo advierte.

## Consecuencias

- Todo código nuevo debe pasar `./mvnw spotless:check` y el análisis estático antes de mergear.

## Alternativas consideradas

- **Análisis estático solo informativo (no bloqueante)**: descartada — un hallazgo que solo advierte
  y no bloquea tiende a acumularse e ignorarse, el mismo patrón de erosión que motivó el
  Principio VII de la constitución ("Sin atajos") tras el incidente de cobertura relajada a 30% que
  nunca se revirtió.
- **Checkstyle en vez de Spotless**: no evaluado a fondo — Spotless con Google Java Format se eligió
  por aplicar el formato automáticamente (`spotless:apply`) en vez de solo señalar violaciones.
