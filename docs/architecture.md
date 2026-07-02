# Architecture

`reals-backend` is a Kotlin + Spring Boot modular monolith for a structured dating / connection backend. The central design rule is that product flow is represented through explicit persisted states and validated service transitions.

## Layers

- `controller`: HTTP endpoints. Controllers parse request DTOs, call services and map responses.
- `controller.dto`: API request/response DTOs.
- `service`: business rules, validations, state transitions and external push notification orchestration.
- `service.matching`: matchmaking queue orchestration, availability checks, diagnostics, hard matching filters, compatibility evaluation and scoring.
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
- Firebase Cloud Messaging is used for external push notifications when a Firebase messaging bean is configured.

## Core Modules

`Match` and `Connection` are different concepts:

- `Match`: temporary system-generated pairing for first chat and visual review.
- `Connection`: confirmed interaction after mutual visual approval.

A match can produce one connection. Mutual visual approval creates a
`SCHEDULING_PENDING` connection and creates `CONNECTION` locks immediately so it
counts against connection capacity; scheduling becomes actionable later when the
activation job transitions it to `SCHEDULING_PHASE`.

Chat responsibilities are split conservatively:

- `ChatService`: chat creation, activation, messages, first-chat approval decisions and timeout/abandonment endings.
- `ChatExitService`: mutual cancellation, unilateral cancellation, safety-report cancellation and cancellation penalties.

Push notification responsibilities are split between application orchestration
and provider transport:

- `service.notification`: event-specific notification services that decide
  recipients, eligibility, payloads, idempotency keys and delivery recording.
- `service.notification.sender`: provider adapters for external push delivery,
  such as Firebase and no-op local/test senders.

## Persistence

- Entities use UUID primary keys.
- Enums are persisted as strings.
- Migrations live under `src/main/resources/db/migration`.
- Local H2 profiles disable Flyway and use Hibernate `ddl-auto: update`; `local-nodb` is the no-auth local H2 profile. `local-firebase` is the default local Firebase profile and uses PostgreSQL with Flyway enabled.

## Background Jobs

Known scheduler jobs:

- `MatchmakingJob`
- `ChatTimeoutJob`
- `InactivityCheckJob`
- `MatchExpirationJob`
- `PenaltyExpirationJob`
- `SchedulingNegotiationTimeoutJob`
- `SecondChatLifecycleJob`
- `SecondChatReminderNotificationJob`
- `VisualPhaseExpirationJob`
- `AccountDeletionFinalizationJob`

Jobs are guarded with ShedLock infrastructure and should be idempotent where practical. They should log useful progress, catch per-item failures and call services for business transitions.

`GET /api/connections/{connectionId}/chat` owns second-chat materialization for user entry. It creates the `SECOND_CHAT` idempotently when the confirmed window is open, then activates it for the conversation.

`SecondChatLifecycleJob` owns second-chat lifecycle cleanup after scheduling confirmation: it closes expired scheduled windows that never created a chat, moves timed-out active second chats to read-only `EXPIRED`, then closes them and their connection after read-only retention.

`SecondChatReminderNotificationJob` sends privacy-safe external push reminders
before a confirmed second-chat `confirmedDateTime` while the connection is still
`SECOND_CHAT_SCHEDULED`. The default lead-time list
is `[10]` minutes and is configured through
`notifications.second-chat-reminder.minutes-before`; cadence is configured with
`scheduler.second-chat-reminder-job.fixed-delay`. A reminder is due only when
`confirmedDateTime - minutesBefore` falls within the current job window, so lead
times already in the past are skipped. Delivery is deduplicated per user,
notification type, connection id and lead time. The payload contains only
`type`, `connectionId` and `availableAt`.

Local auto-auth profiles expose `/api/local-dev/jobs/.../run` endpoints to trigger the same job beans manually, plus `/api/local-dev/timeouts/...` endpoints to move selected deadlines into the past for deterministic manual testing. The local matchmaking processor endpoint is also available in `local-firebase` for Android/Firebase manual flows. These endpoints are profile-gated and are not part of the cloud dev or production API.

## Non-Goals

These are not current backend behavior:

- swipe-based matching
- popularity ranking, ELO or attractiveness scoring
- reveal quotas
- WebSocket/SSE real-time chat
- internal notification inbox, notification bell or unread count
- ML-based compatibility
- gamified reputation badges

Do not introduce these as side effects of refactors.
