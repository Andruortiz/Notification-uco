# Notification-uco

## Trazabilidad del desarrollo asistido por IA

Desde el 2026-09-07 este repositorio adopta [spec-kit](https://github.com/github/spec-kit): cada
historia nueva desarrollada con asistencia de IA queda documentada de punta a punta en
`specs/<historia>/` (`spec.md` → `plan.md` → `tasks.md`), gobernada por las reglas de
`.specify/memory/constitution.md`.

**Nota retrospectiva:** el trabajo anterior a esa fecha (PRs #1–22, dominio, casos de uso CU-01 a
CU-04, contrato OpenAPI inicial) se desarrolló con asistencia de IA pero sin este proceso
estructurado — la evidencia disponible para ese período es el historial real de commits/PRs en
GitHub y las pruebas automatizadas del repositorio (en particular `HexagonalArchitectureTest` como
verificación objetiva de la arquitectura). No se reconstruye retroactivamente para simular que el
proceso existía desde el inicio.