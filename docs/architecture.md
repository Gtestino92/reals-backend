# Architecture

`reals-backend` is a Kotlin + Spring Boot modular monolith for a structured dating / connection backend. The central design rule is that product flow is represented through explicit persisted states and validated service transitions.

## Layers

- `controller`: HTTP endpoints. Controllers parse request DTOs, call services and map responses.
- `controller.dto`: API request/response DTOs.
- `service`: business rules, validations, state transitions and external push notification orchestration.
- `service.matching`: matchmaking queue orchestration, availability checks, diagnostics, hard matching filters, compatibility evaluation and scoring.
- `service.authenticity`: profile authenticity verification provider abstraction.
- `service.identity`: Firebase/external account identity lifecycle services.
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

Engagement capacity limits are matchmaking admission controls. The match cap
gates new Match opportunities, the Visual Advancement Cap gates future
matchmaking from recent visual-review advancement throughput, and the connection
cap gates future matchmaking from active downstream commitments. The neutral
baselines are 5 active Matches and 4 active Connections. When user reliability
is enabled, `EngagementCapacityPolicy` derives effective Match and Connection
caps from the current decayed reliability score; when reliability is disabled,
effective caps are exactly the configured baselines. Existing engagements
continue through later lifecycle phases even if that temporarily takes active
counts above a configured or derived limit.

Chat responsibilities are split conservatively:

- `ChatService`: chat session lookup plus first-chat and second-chat creation/materialization compatibility entry points.
- `ChatAccessService`: shared chat lookup, participant validation and pair-block validation.
- `ChatMessageService`: text/audio message persistence, client-message idempotency validation, reply-target resolution, reactions and message reads.
- `FirstChatResolutionService`: first-chat approval/rejection decisions, decision-only resolution, reliability events and transition into visual review.
- `ChatLifecycleService`: first-chat timeout/inactivity endings, second-chat read-only cleanup helpers, scheduler candidate queries and shared lifecycle-window validation.
- `SecondChatLifecycleService`: explicit second-chat attendance, join classification, no-show claims and no-show terminal handling.
- `SecondChatConversationLifecycleService`: second-chat mutual completion, partner inactivity, initial silence and conversation-phase request resolution.
- `ChatExitService`: first-chat mutual/unilateral cancellation plus safety-report cancellation. Ordinary mutual/unilateral cancellation is rejected for `SECOND_CHAT`.

Push notification responsibilities are split between application orchestration
and provider transport:

- `service.notification`: event-specific notification services that decide
  notification-specific eligibility, payloads and idempotency keys.
- `PushRecipientPreparationService`: shared recipient-level preparation. It
  checks existing delivery rows, applies account-level notification preferences,
  records `SKIPPED_USER_PREFERENCE` before token lookup, handles no-token skips
  and creates `PreparedPushCommand` instances.
- `NotificationPreferenceService`: account-level configurable groups
  (`ACTIVITY`, `REMINDERS`, `AVAILABILITY`) with default-enabled missing rows
  and an internal always-on `SYSTEM` category for technical notifications such
  as `MATCH_FOUND_INVALIDATED`.
- `service.notification.sender`: provider adapters for external push delivery,
  such as Firebase and no-op local/test senders.

Profile responsibilities are split between profile-level state and profile-photo
lifecycle orchestration:

- `ProfileService`: profile creation, profile text/location/filter updates,
  activation, profile-level validation and authenticity verification.
- `service.photo.ProfilePhotoService`: profile-photo reads, read URL
  resolution, upload, replacement, deletion, reordering, storage coordination,
  durable cleanup-task coordination and photo-mutation consequences on profile
  state and authenticity.
- Focused `service.photo` components continue to own provider-specific analysis,
  semantic photo policy, moderation policy and admin moderation review behavior.

Affinity-question responsibilities are intentionally isolated:

- `service.affinity`: static catalog loading/validation, private current-profile answer writes, the pure pairwise evidence evaluator and answer-free first-chat/visual snapshot initialization.
- `AffinityQuestionAnswer`: the private current-answer table; catalog questions remain a versioned UTF-8 resource.
- `conversation_prompt_snapshots`: immutable first-chat prompt sequence rows created once when a first chat starts. They store prompt text and source metadata, but never answer codes, labels, ranking contributions, scores or compatibility percentages.
- `visual_review_affinity_indicators`: immutable match-owned positive category labels created from the same initialization evidence and exposed only through visual-profile access. They store at most three category ids/titles and no question, answer, score, kind or evidence-count fields.
- `GET /api/reference/affinity-questions` exposes only client-safe catalog data. It does not expose scoring policies, matrices or weights.
- `GET/PATCH/DELETE /api/me/profile/affinity-answers` are current-user-only endpoints. They never accept arbitrary profile or user ids.
- Affinity answer writes lock the current `Profile` row before reading or mutating answers. `PATCH` and `DELETE` use the same profile-lock order; read endpoints remain lock-free.

Raw affinity answers remain private and are returned only through current-user
answer endpoints. Affinity can derive two answer-free outputs when a new first
chat is initialized: prompt text in the first-chat guidance sequence and
positive shared category labels in visual review. These snapshots do not expose
exact answers, answer labels, scores, percentages, confidence, factors or
ranking internals. Private affinity answers can also be evaluated inside
probabilistic matchmaking only when `matchmaking.ranking.affinity` is `SHADOW`
or `ACTIVE`; that behavior is unchanged and affinity never becomes a hard
eligibility filter. Free-text profile prompts remain a separate future system.

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
- `UserReliabilityEventCleanupJob`
- `SchedulingNegotiationTimeoutJob`
- `SecondChatLifecycleJob`
- `SecondChatReminderNotificationJob`
- `VisualReviewReminderNotificationJob`
- `VisualPhaseExpirationJob`
- `AccountDeletionFinalizationJob`

Jobs are guarded with ShedLock infrastructure and should be idempotent where practical. They should log useful progress, catch per-item failures and call services for business transitions.


Once both participants have joined a second chat, the conversation lifecycle is anchored at `conversationStartedAt = max(joinedAtA, joinedAtB)`. Mutual completion requires 10 minutes of conversation and at least one message from each participant; accepted completion sets `FINISHED / SECOND_CHAT_MUTUAL_COMPLETION` and read-only retention. Partner inactivity is based only on conversational messages, but its response clock does not start before both users have joined: `responseClockStartedAt = max(lastMessageAt, conversationStartedAt)`. A waiting message sent before the partner joins remains the latest reference message, while claimability and automatic closure are clamped to conversation start. The latest-message author may claim after 5 minutes, automatic closure occurs at 10 minutes, and the silent participant receives the reliability penalty. If neither user sends any message for 10 minutes after conversation start, the chat becomes `ABANDONED / SECOND_CHAT_NO_CONVERSATION_STARTED`. Control requests do not update `lastMessageAt` or `lastMessageSenderId`.

`POST /api/connections/{connectionId}/second-chat/join` owns second-chat materialization for user entry. It creates or activates the `SECOND_CHAT` idempotently when `confirmedDateTime <= now < confirmedDateTime + 20 minutes`, records exactly one attendance classification for the caller and keeps `timeoutAt` anchored to the agreed `confirmedDateTime`. `GET /api/connections/{connectionId}/chat`, Home reads, polling and message fetches are side-effect free and do not imply attendance.


### Public Profile Questions

Public profile questions use a dedicated classpath JSON catalog
(`profile-questions.es-AR.json`) and a dedicated `profile_question_answers`
table. The catalog is validated at startup for stable ids, positive versions,
unique display order, bounded single-line prompts and deterministic order. The
implementation intentionally does not reuse affinity entities, repositories,
answer tables or DTOs.

Writes serialize on the owning profile row through
`ProfileRepository.findByUserIdForUpdate(...)`, then mutate only
`profile_question_answers` and `Profile.updatedAt`. This lock order is shared by
answer upsert, answer delete and selection replacement, so concurrent operations
for one profile cannot create non-contiguous public ordering. Selection
replacement validates the full request before mutation, clears positions, flushes
past the partial unique index, then assigns contiguous positions.

Counterpart exposure is limited to the guarded visual-profile endpoint. The
controller calls `visualReviewService.requireVisualContentAccess(...)` before
resolving and loading the partner's selected profile-question answers. The data
is live at request time and is not copied to match, chat, visual-review,
connection or affinity snapshot rows.

## Matchmaking Persistence

Matchmaking queue CRUD and candidate discovery are intentionally separate.
`MatchmakingQueueRepository` owns queue-entry CRUD and status/count operations.
Candidate claiming uses a focused JDBC repository with native PostgreSQL SQL
and participates in the same Spring transaction as anchor claim, partner
discovery, scoring, partner claim, match creation and first-chat creation.

Candidate claiming composes trusted SQL fragments from two independent pair
policy dimensions: active-interaction exclusion and historical-cooldown
exclusion. This supports active-plus-history, active-only, history-only and no
pair-history exclusion modes without Boolean `OR` predicates that hide disabled
filters from PostgreSQL. User-block exclusion is always present. The flow locks
one eligible anchor queue row
with `FOR UPDATE OF qa SKIP LOCKED`, uses a non-locking `JOIN LATERAL` probe so
old unmatchable anchors do not block progress, then reads a bounded partner
window without row locks. Hard filters, including exact mutual Haversine
distance, run before the partner `LIMIT`.

Partners are ranked in application code. `LEGACY_EARLY_ACCEPT` preserves the
previous compatibility plus bounded reliability-modifier behavior, early-accept
FIFO group and score-descending fallback. `PROBABILISTIC_WEIGHTED` gates by raw
compatibility, combines compatibility quality with individual reliability-score
similarity, relaxes reliability similarity as partner wait time grows and uses a
Gumbel weighted permutation without replacement. Reliability scores are loaded
once for the partner window. When private affinity ranking is enabled in
probabilistic mode, affinity answers are also loaded once for the bounded window
by profile id, evaluated in memory and used only as a bounded multiplicative
factor; affinity never becomes a hard filter. `SHADOW` mode records aggregate
privacy-safe diagnostics without changing weights or consuming extra random
values. The selected partners are claimed one at a time by exact queue-entry id
with hard revalidation and `FOR UPDATE OF qb SKIP LOCKED`; normal partner
contention falls through to the next ranked candidate instead of becoming a
processing failure. See `docs/matchmaking-ranking.md`.

Defensive direct match creation still locks both users through
`UserRepository.findAllByIdForUpdate` before persisting a match. After those
locks, it captures one authoritative backend `now` for that final-admission
decision, then runs pair eligibility/cooldown checks, Visual Advancement
capacity and reliability-derived Match/Connection capacity with that timestamp.
The focused JDBC pair-eligibility query returns either active-interaction,
previous-pairing-cooldown or no blocker in one database round trip. Historical
cooldown classification uses the persisted terminal evidence and its applicable
cutoff, including the separate first-chat decision-mismatch cutoff. User blocks
remain a separate permanent exclusion checked before this pair policy.
`matchmaking.allow-active-pair-duplicates=true` omits active-interaction
blocking from candidate claim, partner discovery, partner claim and defensive
match creation, but factual state inspection such as `hasActiveInteraction`
still reports the persisted active interaction. Historical cooldown and
engagement-capacity checks remain independent.

`SearchLocationMatchFilter` remains as a defensive service-layer parity check
for SQL distance filtering. PostGIS, spatial indexes driven by real query
plans, canonical pair identity and derived eligibility state remain deferred.

`SecondChatLifecycleJob` owns second-chat lifecycle cleanup after scheduling confirmation. In deterministic bounded batches it resolves no-show claims and hard entry cutoff first, then conversation-phase requests, initial silence, automatic partner inactivity, neutral absolute timeout and read-only cleanup. Request-triggered terminal transitions return typed rejected results from the transaction and map to HTTP conflicts after commit, avoiding rollback of committed lifecycle outcomes. Already-due initial silence or partner inactivity is evaluated before a mutual-completion decision, so a late acceptance cannot override an inactivity terminal outcome.

`SecondChatReminderNotificationJob` sends privacy-safe external push reminders
before a confirmed second-chat `confirmedDateTime` while the connection is still
`SECOND_CHAT_SCHEDULED`. The default lead-time list
is `[10]` minutes and is configured through
`notifications.second-chat-reminder.minutes-before`; cadence is configured with
`scheduler.second-chat-reminder-job.fixed-delay`. A reminder is due only when
`confirmedDateTime - minutesBefore` falls within the current job window, so lead
times already in the past are skipped. Delivery is deduplicated per user,
notification type, connection id and lead time. The provider payload includes a
display title/body plus data fields; the data contract contains only `type`,
`connectionId` and `availableAt`.

`SecondChatStartNotificationJob` sends a privacy-safe external push shortly
after a confirmed second-chat start. Eligibility is exact at the backend clock:
`confirmedDateTime <= now <= confirmedDateTime + 5 minutes` by default. The job
runs every 4 minutes by default, and startup validation requires that cadence to
remain lower than the latest-send window. Candidate scanning uses a seek cursor
ordered by `confirmedDateTime` and negotiation id, skips already fully handled
connections, and continues through the bounded five-minute source window until
the per-run batch of unhandled connections is full or the source is exhausted.
The query requires `CONFIRMED`, and allows only `SECOND_CHAT_SCHEDULED`,
`SECOND_CHAT_AVAILABLE` or `SECOND_CHAT` connections. The service rechecks the
connection, negotiation window and each participation under the connection lock
before preparing sends. Participants already joined through `ON_TIME` or `LATE`
attendance with `joinedAt` set are recorded as `SKIPPED_ALREADY_JOINED` and are
not sent to. Delivery is deduplicated per user, notification type and
`secondChatStartedAggregateId(connectionId)`. The provider data contract is
`type=SECOND_CHAT_STARTED`, `connectionId`, `matchId` and `availableAt`. The
Android FCM notification tag is `second-chat-<connectionId>` for both the
reminder and start notification; transport TTL is capped at the confirmed start
for reminders and at the configured second-chat on-time cutoff for start pushes.

`VisualReviewReminderNotificationJob` sends privacy-safe external push reminders
for active visual reviews whose persisted `reminderEligibleAt` is due and whose
`expiresAt` is still in the future. `reminderEligibleAt` is calculated once when
`VisualReview` is created from `chat.visual-phase.duration-minutes` and
`notifications.visual-review-reminder.remaining-percentage` (default 40%
remaining). The job runs about every 30 minutes, does not calculate percentages
or phase duration, and sends only to users whose own visual decision is still
pending. Delivery is deduplicated per user, notification type and match id.
Legacy rows with `reminderEligibleAt = null` are ignored unless manually
backfilled outside Flyway.


`MatchmakingAvailabilityNotificationJob` owns the first `AVAILABILITY` push,
`MATCHMAKING_AVAILABLE`. The trigger is intentionally narrow: recovery from the
rolling Visual Advancement Cap, not generic search availability. New
`VisualReview` creation flushes the review row, then reconciles both
participants under the same transaction so the current cap query includes the
new advancement. Replays that return an existing review do not create new
notification state. The durable episode table stores `PENDING`, `HANDLED` and
`CANCELLED` states, with a partial unique index enforcing at most one pending
episode per user. The episode UUID is the push aggregate id.

The job runs in bounded batches and uses ShedLock for cross-instance scheduler
exclusion. Each due episode is row-locked and revalidated against
`VisualAdvancementCapService.statusFor(userId, now)`. `nextCheckAt` is only a
wake-up hint: if the rolling cap still blocks, the episode remains pending and
is moved to the current authoritative threshold. If the cap is clear, the
service checks queue state and delegates final eligibility to
`MatchmakingAvailabilityService`; another blocker cancels this visual-cap
episode without sending. Push preparation happens transactionally through
`PushRecipientPreparationService`, including preference and no-token skip
persistence. Provider calls run afterward through `PreparedPushCommandProcessor`,
outside database transactions and without holding episode locks.

`UserReliabilityEventCleanupJob` deletes expired internal reliability events
after their scoring window ends. User reliability is feature-flagged off by
default and recomputed from active events instead of a cache. Reliability-derived
capacity intentionally creates an observable feedback-loop experiment: good
behavior can lead to more admission opportunities, and poor behavior can reduce
opportunities. Aggregate Micrometer metrics under `reals.engagement.capacity.*`
record evaluation phase, reliability direction and allowed/blocked outcomes
without user ids or raw-score tags. Capacity evaluation phases are
`availability`, `final_match_admission` and internal `queue_reconciliation`.
Actuator exposes Micrometer meters in configured runtimes, but durable metrics
retention requires an external registry/backend that is not introduced here.

Local execution profiles expose `/api/local-dev/jobs/.../run` endpoints to
trigger the same job beans manually, plus `/api/local-dev/timeouts/...`
endpoints to move selected deadlines into the past for deterministic manual
testing. Hosted `dev` registers the same tooling as authenticated
administrator-only operational endpoints. These endpoints are profile-gated and
are not part of the production API.

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
