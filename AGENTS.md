# Agent Instructions

This repository is the backend for Reals, a structured dating / connection product. The backend is a Kotlin + Spring Boot modular monolith with explicit state transitions. Preserve the state-machine architecture when making changes.

## Stack

- Kotlin 2.3.21 on Java 21.
- Spring Boot 4.0.6.
- Spring Web, Security, Data JPA, JDBC, Cache and WebFlux WebClient.
- H2 for local `local-firebase` and `local-nodb` development.
- PostgreSQL is the supported non-local database driver. Do not add another database driver unless a concrete environment needs it.
- Flyway migrations live under `src/main/resources/db/migration`; local H2 profiles disable Flyway and use Hibernate `ddl-auto: update`.
- ShedLock protects scheduler jobs when a `LockProvider` bean exists.
- Firebase Admin dependency and Firebase auth classes exist. The default local profile uses Firebase auth; local no-auth testing uses `local-nodb` or `local-postgres`.

## Local Development

- Default active Spring profile: `local-firebase`.
- Default local database: H2 file database at `./data/realsdb`.
- H2 console: `http://localhost:8080/h2-console`.
- H2 JDBC URL: `jdbc:h2:file:./data/realsdb`.
- Local Firebase auth expects `./secrets/reals-backend-firebase-credentials-dev.json` and a real Firebase ID token.
- Local no-auth profiles `local-nodb` and `local-postgres` use `DevAutoAuthFilter`, which injects user `00000000-0000-0000-0000-000000000001` with `ROLE_USER`.
- Sanity endpoint: `GET /api/ping`.
- Maven CLI may not be installed on the target machine. Prefer IntelliJ IDEA run/build actions unless the user explicitly confirms CLI availability.

## Architecture Rules

- Controllers are thin HTTP adapters.
- DTOs live under `src/main/kotlin/com/reals/backend/controller/dto`.
- Services own business rules and state transitions.
- `ChatService` owns chat creation, activation, messages, first-chat approval decisions and timeout/abandonment endings.
- `ChatExitService` owns mutual cancellation, unilateral cancellation, safety-report cancellation and cancellation penalties.
- Repositories are Spring Data JPA persistence adapters only.
- Schedulers call services and must not duplicate transition logic.
- Domain classes under `domain` represent persisted entities and enums.
- Matching-specific logic belongs under `service.matching`.
- Reputation-specific logic belongs under `service.reputation`.
- Identity-verification-specific logic belongs under `service.identity`.
- Configuration belongs under `config`.

Use this flow unless there is a strong reason not to:

```text
Controller -> Service -> Repository
```

Do not mutate domain state directly from controllers, schedulers or repositories.

## Domain Invariants

- The product is anonymous-first and state-driven.
- Do not add swipe behavior, popularity ranking, ELO, visible reputation badges, reveal quotas, WebSockets, notifications or ML scoring unless explicitly requested.
- Do not silently create missing domain objects unless the service method clearly owns that behavior.
- Validate state transitions in services with clear failures.
- Terminal states should not be mutated except by explicit, justified service methods.
- Active engagement limits are counted from `ActiveEngagementLock`, not inferred from `Match` or `Connection` state.

## Core Flow

- A `Profile` starts as `DRAFT`; only `ACTIVE` profiles can enter matchmaking.
- `MatchmakingService.enqueue` validates eligibility and queues the current user.
- `MatchmakingService.findCandidatePairs` finds candidate pairs.
- `MatchService.createMatch` creates the `Match`, creates `MATCH` locks for both users and removes both users from the queue.
- `ChatService.startFirstChat` starts the anonymous first chat separately.
- Mutual first-chat approval moves the match from `CHAT_ACTIVE` to `VISUAL_PHASE` and initializes `VisualReview`.
- First-chat rejection is unilateral cancellation: it moves the match to `CHAT_REJECTED`, releases locks and evaluates cancellation penalties.
- Mutual chat cancellation closes without penalty; safety cancellation records a report and penalizes the reported participant.
- Mutual visual approval moves the match to `VISUAL_APPROVED`, creates a `Connection`, upgrades locks to `CONNECTION` and initializes scheduling.
- Any visual rejection moves the match to `VISUAL_REJECTED` and releases locks.
- Scheduling confirmation moves the connection to `SECOND_CHAT_SCHEDULED`; `ScheduledSecondChatStartJob` makes the second chat `AVAILABLE` when `confirmedDateTime` is due, and user entry or first message activates it.
- Scheduling proposals are for the second chat inside the app, not for an in-person meeting outside the app.
- Scheduling proposal submissions are ordered lists of 1 to `scheduling.max-proposals-per-round` unique future half-hour slots per user per round. Overlaps auto-confirm by lowest combined preference order, then earliest agreed slot. If there is no overlap, a participant must explicitly reject the round to open the next one.
- Closing or expiring a connection releases `CONNECTION` locks.

## State Machines

Allowed `MatchState` transitions:

- `CHAT_ACTIVE -> VISUAL_PHASE`
- `CHAT_ACTIVE -> CHAT_REJECTED`
- `CHAT_ACTIVE -> EXPIRED`
- `VISUAL_PHASE -> VISUAL_APPROVED`
- `VISUAL_PHASE -> VISUAL_REJECTED`
- `VISUAL_PHASE -> EXPIRED`

Allowed `ChatStatus` transitions:

- `AVAILABLE -> ACTIVE`
- `ACTIVE -> FINISHED`
- `ACTIVE -> CANCELLED`
- `ACTIVE -> EXPIRED`
- `ACTIVE -> ABANDONED`

Allowed `ConnectionState` transitions:

- `SCHEDULING_PHASE -> SECOND_CHAT_SCHEDULED -> SECOND_CHAT_AVAILABLE -> SECOND_CHAT`
- `SCHEDULING_PHASE -> CLOSED`
- `SECOND_CHAT_AVAILABLE -> CLOSED`
- `SECOND_CHAT -> CLOSED`

Allowed scheduling transitions:

- `NegotiationStatus.PENDING -> CONFIRMED`
- `NegotiationStatus.PENDING -> FAILED`
- `ProposalStatus.PENDING -> ACCEPTED`
- `ProposalStatus.PENDING -> REJECTED`

## Configured Limits

From `application.yml`:

- `engagement.max-active-matches: 5`
- `engagement.max-active-connections: 2`
- `chat.first-chat.duration-minutes: 1440`
- `chat.first-chat.min-messages-per-user: 0`
- `chat.first-chat.min-messages-before-free-cancel: 0`
- `chat.visual-phase.duration-minutes: 1440`
- `chat.second-chat.duration-minutes: 2880`
- `chat.second-chat.min-messages-before-free-cancel: 0`
- `scheduling.negotiation-duration-minutes: 2880`
- `scheduling.max-rounds: 3`
- `scheduling.max-proposals-per-round: 3`
- default profile photos: required `9`, max `9`, min person `3`, min full-body `1`

From local H2 profiles:

- local profile photos: required `4`, max `9`, min person `1`, min full-body `1`

## Coding Style

- Use Kotlin idioms and constructor injection.
- Use `@Transactional` on services that mutate state.
- Use `OffsetDateTime` for persisted timestamps.
- Prefer explicit parameter names in service calls when it improves readability.
- Keep methods focused on one business action.
- Avoid broad refactors while making narrow behavior changes.
- Do not introduce dependencies unless necessary and consistent with the project.

## Testing And Verification

- Do not run Maven or Docker commands unless the user explicitly requests it. Prefer telling the user the exact Maven or Docker command to run outside IntelliJ IDEA, then use their reported output to continue.
- Integration tests live under `src/test/kotlin/com/reals/backend/integration` and use the `test` Spring profile with H2 in-memory.
- Shared integration fixtures belong under `integration/support`; keep base classes out of the concrete test package levels.
- Prefer service-level integration tests for business rules that depend on JPA, transactions, repositories or schema.
- Use controller integration tests for HTTP contract coverage: routing, JSON shape, status codes, exception mapping and current-user resolution.
- Important areas to test when touched: state transitions, invalid transitions, engagement limits, queue behavior, scheduling confirmation/failure, profile activation, penalties and scheduler-triggered expiration.
- If automated tests cannot be run, state that clearly and describe the manual/code-level verification performed.

## Documentation

- Canonical docs live under `docs/`.
- `docs/architecture.md` explains structure and ownership.
- `docs/domain.md` explains entities, enums and invariants.
- `docs/state-machine.md` lists allowed transitions.
- `docs/user-flow.md` explains the product/backend flow.
- `docs/local-development.md` explains local setup.
- `docs/api.md` summarizes current controllers and endpoints.
- `docs/testing.md` explains the test strategy and how to run tests.
- `docs/technical-debt-mvp.md` lists known non-implemented or undecided behavior for mvp.
- `docs/technical-debt-prod.md` lists known non-implemented or undecided behavior for prod.

## When Unsure

Preserve the current explicit state flow. Ask before changing product behavior, authentication model, persistence schema, matching criteria, trust-score behavior or local development assumptions.
