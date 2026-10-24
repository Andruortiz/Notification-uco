# Documentación — Notification-uco

Este directorio contiene la documentación estable y ya decidida del proyecto: decisiones de
arquitectura, casos de uso formales y diagramas. Es la contraparte versionada de lo que antes vivía
únicamente en Notion — Notion sigue siendo el bloc de notas de trabajo (backlog vivo, historias
pendientes, decisiones en curso), pero lo ya decidido y estable vive aquí, en el repositorio, para
que cualquier persona o modelo que clone el repo tenga el contexto completo sin depender de una
herramienta externa.

## Contenido

```text
docs/
├── README.md          este índice
├── adr/                decisiones de arquitectura (una por archivo)
├── casos-de-uso.md     CU-01 a CU-13, con flujos completos trazados a puertos
├── requisitos.md       RF-01 a RF-26 y RNF-01 a RNF-15 (línea base v1.0)
└── diagramas/          diagramas de arquitectura (Mermaid, texto plano)
```

## Estado de esta carpeta (2026-09-11)

- **`casos-de-uso.md`**: contenido completo, portado desde Notion.
- **`requisitos.md`**: contenido completo, transcrito directamente por el autor desde la línea base
  v1.0 (documento Word, 26/07/2026) — es el único archivo de esta carpeta que no vino de Notion, ni
  reconstruido; es el texto original de los RF/RNF.
- **`diagramas/`**: contenido completo (4 diagramas Mermaid), portado desde Notion.
- **11 ADR en total** (`adr/0001` a `adr/0011`), numerados secuencialmente sin huecos. Se eliminaron
  deliberadamente las decisiones que fueron requisitos de arranque impuestos por el curso, no
  decisiones deliberadas del equipo (arquitectura hexagonal, cobertura 80/70, GitFlow, Kubernetes,
  frontend diferido, 15 factores) — un ADR documenta una decisión propia entre alternativas, no un
  requisito externo. Esas reglas siguen vigentes y bloqueantes igual (constitución, pruebas
  automáticas), solo que no llevan una ADR propia con una "deliberación" que nunca ocurrió así.
- **`adr/0001` a `adr/0009`** (salvo `0010`): reconstruidos — estas decisiones ya se tomaron y se
  aplican en el código, pero nunca se habían escrito como documentos ADR completos, solo existían
  como referencias sueltas en el backlog. Contexto/Decisión se reconstruyó a partir de esas
  referencias; Consecuencias y Alternativas consideradas se completaron con el razonamiento técnico
  ya visible en el código. Revisar cada uno igual — puede haber matices del razonamiento original
  que solo el autor conoce.
- **`adr/0010`** (Concurrencia entre réplicas y estrategia de caché): contenido completo desde el
  origen, sin reconstruir — fue la primera ADR redactada en formato completo (sesión 2026-09-07).

## Cómo leer esta base

Los ADR están numerados en el orden en que se tomaron las decisiones — no hace falta leerlos en
orden estricto, pero cada uno referencia a qué parte del código o de la constitución aplica.
