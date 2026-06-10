# Architecture

`reals-backend` is a Kotlin + Spring Boot modular monolith for a structured dating / connection backend. The central design rule is that product flow is represented through explicit persisted states and validated service transitions.

## Layers

- `controller`: HTTP endpoints. Controllers parse request DTOs, call services and map responses.
- `controller.dto`: API request/response DTOs.
- `service`: business rules, validations and state transitions.
- `service.matching`: hard matching filters, compatibility evaluation and scoring.
- `service.identity`: identity-verification provider abstraction.
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

- Kotlin 2.3.21.
- Java 21.
- Spring Boot 4.0.6.
- Spring Web, Security, Data JPA, JDBC, Cache and WebFlux WebClient.
- PostgreSQL is the supported shared/dev/prod database and the default Docker local database.
- H2 is used for local no-auth development and the in-memory test profile.
- Flyway is present; migrations live under `src/main/resources/db/migration`.
- Caffeine cache.
- ShedLock for scheduler locking.
- Firebase Admin SDK dependency with Firebase auth configuration classes.

## Core Modules

`Match` and `Connection` are different concepts:

- `Match`: temporary system-generated pairing for first chat and visual review.
- `Connection`: confirmed interaction after mutual visual approval.

A match can produce one connection. Connection creation upgrades engagement locks from `MATCH` to `CONNECTION`.

Chat responsibilities are split conservatively:

- `ChatService`: chat creation, activation, messages, first-chat approval decisions and timeout/abandonment endings.
- `ChatExitService`: mutual cancellation, unilateral cancellation, safety-report cancellation and cancellation penalties.

## Persistence

- Entities use UUID primary keys.
- Enums are persisted as strings.
- Migrations live under `src/main/resources/db/migration`.
- Local H2 profiles disable Flyway and use Hibernate `ddl-auto: update`; `local-nodb` is the no-auth local H2 profile. `local-firebase` is the default local Firebase profile and currently targets the Docker PostgreSQL datasource.

## Background Jobs

Known scheduler jobs:

- `MatchmakingJob`
- `ChatTimeoutJob`
- `InactivityCheckJob`
- `MatchExpirationJob`
- `PenaltyExpirationJob`
- `SchedulingNegotiationTimeoutJob`
- `ScheduledSecondChatStartJob`
- `VisualPhaseExpirationJob`
- `AccountDeletionFinalizationJob`

Jobs are guarded with ShedLock infrastructure and should be idempotent where practical. They should log useful progress, catch per-item failures and call services for business transitions.

`ScheduledSecondChatStartJob` only makes the second chat visible as `AVAILABLE`; user entry or the first message activates the chat and starts its timeout window.

Local profiles expose `/api/local-dev/jobs/.../run` endpoints to trigger the same job beans manually, plus `/api/local-dev/timeouts/...` endpoints to move selected deadlines into the past for deterministic manual testing. These endpoints are profile-gated and are not part of the cloud dev or production API.

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
