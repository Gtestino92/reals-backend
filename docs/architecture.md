# Architecture

`reals-backend` is a Kotlin + Spring Boot modular monolith for a structured dating / connection backend. The central design rule is that product flow is represented through explicit persisted states and validated service transitions.

## Layers

- `controller`: HTTP endpoints. Controllers parse request DTOs, call services and map responses.
- `controller.dto`: API request/response DTOs.
- `service`: business rules, validations and state transitions.
- `service.matching`: compatibility evaluation.
- `service.reputation`: trust score / reputation evaluation.
- `repository`: Spring Data JPA persistence access.
- `domain`: persisted entities and enums.
- `scheduler`: time-based background jobs.
- `config`: Spring configuration, security, local auth, cache, Firebase wiring and web MVC support.

Preferred dependency direction:

```text
Controller -> Service -> Repository
```

Schedulers should also call services rather than mutating repositories directly.

## Current Stack

- Kotlin 2.2.0.
- Java 17.
- Spring Boot 3.5.3.
- Spring Web, Security, Data JPA, JDBC, Cache and WebFlux WebClient.
- H2 local database.
- Oracle and PostgreSQL JDBC drivers are present.
- Flyway is present; migrations live under `src/main/resources/db/migration`.
- Caffeine cache.
- ShedLock for scheduler locking.
- Firebase Admin SDK dependency with Firebase auth configuration classes.

## Core Modules

`Match` and `Connection` are different concepts:

- `Match`: temporary system-generated pairing for first chat and visual review.
- `Connection`: confirmed interaction after mutual visual approval.

A match can produce one connection. Connection creation upgrades engagement locks from `MATCH` to `CONNECTION`.

## Persistence

- Entities use UUID primary keys.
- Enums are persisted as strings.
- Initial migration: `src/main/resources/db/migration/V1__init.sql`.
- Local profile `local-nodb` disables Flyway and uses Hibernate `ddl-auto: update` against H2 file storage.

## Background Jobs

Known scheduler jobs:

- `ChatTimeoutJob`
- `InactivityCheckJob`
- `MatchExpirationJob`
- `PenaltyExpirationJob`
- `SchedulingNegotiationTimeoutJob`
- `VisualPhaseExpirationJob`

Jobs are guarded with ShedLock infrastructure and should be idempotent where practical. They should log useful progress, catch per-item failures and call services for business transitions.

## Non-Goals

These are not current backend behavior:

- swipe-based matching
- popularity ranking, ELO or attractiveness scoring
- reveal quotas
- WebSocket/SSE real-time chat
- notification delivery
- ML-based compatibility
- gamified reputation badges

Do not introduce these as side effects of refactors.
