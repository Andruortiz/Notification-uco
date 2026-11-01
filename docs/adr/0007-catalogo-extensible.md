# ADR-0007 — Extensibilidad por catálogo declarativo + adaptadores

**Estado**: Aceptado — "la decisión que define el proyecto" (`.specify/memory/constitution.md`)
**Referenciado en**: `.specify/memory/constitution.md`, `NoHardcodedChannelTest`, casos de uso CU-07/CU-08

## Contexto

Agregar un canal de notificación nuevo (ej. SMS, push) o un proveedor nuevo (ej. Brevo, Twilio) no
debe requerir modificar el núcleo del componente.

## Decisión

Catálogo declarativo (canal → esquema de contenido → proveedores habilitados, con orden de
preferencia) combinado con el patrón adaptador: cada proveedor externo se integra escribiendo un
adaptador `NotificationProvider` nuevo, sin tocar el dominio ni los casos de uso existentes.
`NoHardcodedChannelTest` verifica automáticamente que ningún canal quede hardcodeado como enum o
constante.

## Consecuencias

- Registrar un canal (CU-07) o un proveedor (CU-08) es una operación administrativa, no un despliegue
  de código — siempre que el adaptador del proveedor ya exista.
- Integrar un proveedor externo genuinamente nuevo sí requiere escribir un adaptador (RNF-08, ≤1
  jornada) — el catálogo no elimina ese trabajo, solo evita que se repita para cada canal que use
  ese mismo proveedor.

## Alternativas consideradas

- **Plugins cargados dinámicamente en tiempo de ejecución** (ej. JARs externos descubiertos por
  classpath scanning): descartada — añade complejidad operativa y de seguridad (código no
  verificado en tiempo de build) desproporcionada frente al volumen de canales/proveedores
  esperado; un adaptador nuevo compilado y desplegado junto al resto del componente (RNF-08, ≤1
  jornada) es suficientemente rápido.
- **Canal como enum de Java hardcodeado**: descartada explícitamente — es justo lo que
  `NoHardcodedChannelTest` existe para impedir; añadir un canal habría requerido recompilar y
  redesplegar el núcleo cada vez.
