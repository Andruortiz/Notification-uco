# Implementation Plan: Retirar el ticket de suscripción del stream (HU2-111)

**Branch**: `feature/HU2-111-stream-sin-ticket` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

## Estado del plan

**Estado**: Aprobado

**Versión del plan**: 1

## Summary

Eliminar de punta a punta el flujo de ticket de suscripción. El stream ya se autentica por cookie (HU2-101); esta historia
borra el camino muerto: contrato, controlador, caso de uso, puerto, adaptador Mongo, rama del `AuthenticationWebFilter`,
regla de `RouteAuthorizationPolicy`, propiedad de TTL y pruebas asociadas.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 | **Storage**: MongoDB (se deja de usar `subscription_tickets`)

**Testing**: JUnit 5, `StepVerifier`, Testcontainers (Mongo, RabbitMQ), `WebTestClient`, ArchUnit y Modulith existentes

## Constitution Check (2.0.0)

- I Hexagonal: se retira un puerto de `core`; no se añade dependencia alguna. Cumple.
- II Contrato primero: el cambio en `api-notificaciones.yaml` se hace antes que el código. Cumple.
- III Sin comentarios: no se añade código comentado. Cumple.
- IV Pruebas: E2E con dos tenants en `SessionCookieE2ETest`, pruebas unitarias del filtro, guardias de contrato con las cifras
  nuevas, `verify` completo. Cumple.
- V Commits de una línea. VII Sin soluciones temporales: ninguna excepción nueva.
- Consumidores RabbitMQ: no se tocan.

## Decisiones

1. Corte directo sin alias (coherente con el 2.0.0 sin versionado de prefijo): `POST /notifications/stream/tickets` pasa a no
   existir y, con el PEP inactivo, cae en la regla por defecto (ADMINISTRADOR) y 404/405 del enrutador.
2. `AuthenticationWebFilter` queda con dos constructores públicos (`TokenValidationPort`, `RouteAuthorizationPolicy`
   [, `SessionIdentityPort`|`ObjectProvider`]); sin Bearer ni cookie siempre responde 401.
3. `RejectionReason.INVALID_TICKET` se elimina.
4. Versión del contrato se mantiene en 2.0.0 (aún no publicada); el retiro se añade como punto 9 del registro de cambios.
5. Cifras tras el cambio: 12 rutas, 16 operaciones (11 `available`, 5 `planned`).

## Project Structure

- Contrato: `infrastructure/src/main/resources/static/openapi/api-notificaciones.yaml`
- Borrados en `core`: `SubscriptionTicketFingerprint`, `IssuedSubscriptionTicket`, `IssueSubscriptionTicketUseCase`,
  `SubscriptionTicketPort`, `IssueSubscriptionTicketService` y su prueba.
- Borrados en `infrastructure`: `SubscriptionTicketController`, `SubscriptionTicketResponse`, `SubscriptionTicketDocument`,
  `SubscriptionTicketMongoAdapter`, `SubscriptionTicketE2ETest`, `SubscriptionTicketMongoAdapterTest`.
- Editados: `AuthenticationWebFilter`, `RouteAuthorizationPolicy`, `RejectionReason`, `UseCaseConfig`, `application.yml`.
