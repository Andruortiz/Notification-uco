# Phase 0 Research: CORS para el dashboard de frontend

Sin `[NEEDS CLARIFICATION]` pendientes en el Technical Context ni en el checklist de calidad del spec — esta sección documenta las decisiones técnicas, no resuelve ambigüedades.

## 1. Mecanismo de CORS en WebFlux

**Decision**: un bean `CorsWebFilter` (de `org.springframework.web.cors.reactive`), construido sobre un `UrlBasedCorsConfigurationSource` que registra la configuración para `/**`.

**Rationale**: es un filtro que se ejecuta antes de que la petición llegue a cualquier controller, así que cubre de forma transversal toda la API existente y cualquier endpoint futuro sin tocarlo (FR-005) — no depende de que cada controller/endpoint nuevo se acuerde de anotarse. `spring-boot-starter-webflux` ya trae esta clase en el classpath, sin dependencia nueva.

**Alternatives considered**:
- `@CrossOrigin` por método de controller: descartado — exige anotar cada endpoint uno por uno, y cualquier endpoint nuevo que alguien olvide anotar queda sin CORS. Viola directamente FR-005 (transversal).
- Implementar `WebFluxConfigurer.addCorsMappings(...)`: alternativa válida y equivalente en efecto, pero exige implementar una interfaz adicional solo para este propósito; un bean `CorsWebFilter` directo es más simple y no presupone nada sobre cómo se definan los endpoints futuros (anotados o por función de enrutamiento).

## 2. Cómo probar esto de punta a punta (Principio IV)

**Decision**: prueba E2E con `WebTestClient` contra el contexto Spring real (`@SpringBootTest`, puerto aleatorio), enviando una petición real con la cabecera `Origin` y verificando las cabeceras `Access-Control-Allow-*` en la respuesta — para un origen permitido y para uno no permitido.

**Rationale**: CORS es, por definición, un comportamiento a nivel de transporte HTTP — no tiene sentido de negocio si se prueba llamando directamente al método Java del filtro fuera de un ciclo real de petición/respuesta. El Principio IV exige una prueba E2E para todo flujo observable de punta a punta, y "el navegador recibe o no recibe las cabeceras" es exactamente ese flujo.

**Alternatives considered**:
- Probar `CorsConfigurationSource.getCorsConfiguration(exchange)` de forma unitaria, con un `MockServerWebExchange`: más rápido, pero no demuestra que el filtro está realmente registrado y activo en la cadena de filtros del contexto real — se queda corto del criterio de "E2E" del Principio IV.

## 3. Cómo externalizar la lista de orígenes permitidos

**Decision**: un solo `@Value("${notification.cors.allowed-origins:http://localhost:5173}")` inyectado directamente como parámetro del método `@Bean`, con Spring convirtiendo el valor separado por comas a `List<String>` automáticamente.

**Rationale**: es una sola propiedad de configuración, sin estructura interna (a diferencia del catálogo de canales, que sí justificó su propio `@ConfigurationProperties` record en HU2-042/HU2-107). Crear una clase de propiedades dedicada para un solo campo sería una abstracción sin necesidad real. Mismo patrón ya usado para `pendingOrphanThresholdMs` en `RequeuePendingNotificationsUseCase` (HU2-037).

**Alternatives considered**:
- `@ConfigurationProperties` record dedicado (`CorsProperties`): descartado por desproporcionado para un solo campo — se puede introducir después si esta configuración crece (ej. métodos/headers permitidos también configurables).
