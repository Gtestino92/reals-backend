# User Reliability Score

`UserReliabilityScore` is an internal operational score for participation reliability in Reals flows. It is invisible to users and is separate from safety/moderation sanctions.

## Feature Flag

The v0 system is controlled by one global flag:

```text
USER_RELIABILITY_ENABLED=false
```

When disabled, the backend does not create reliability events, calculate score effects, or change matchmaking behavior. Effective engagement capacities resolve directly to the configured neutral baselines: Match `5` and Connection `4` by default. There is no observe-only mode in v0, but the event-sourced design can support one later.

Local/dev manual testing can inspect the current score breakdown through:

```http
GET /api/local-dev/user-reliability/{userId}
```

This endpoint is profile-gated with the existing local-dev controller pattern and is not available in production. It is read-only: it does not create, update, delete, recalculate persistently or clean up reliability events. When `USER_RELIABILITY_ENABLED=false`, it returns `enabled=false`, the neutral base score, `weightedDelta=0` and an empty event list.

## Score Model

The score is recomputed from active events:

```text
effectiveScore = USER_RELIABILITY_BASE_SCORE + weightedSum(activeReliabilityEvents)
```

Defaults:

```text
USER_RELIABILITY_BASE_SCORE=100
USER_RELIABILITY_FULL_WEIGHT_DAYS=10
USER_RELIABILITY_HALF_WEIGHT_DAYS=10
USER_RELIABILITY_EXPIRATION_DAYS=20
```

Events from day 0 through day 9 count at 100%. Events from day 10 through day 19 count at 50%. Events at day 20 or later are expired and deleted by `UserReliabilityEventCleanupJob`.

There is no score cache in v0. The active event window is small, so recomputation is simpler and keeps the event table as the source of truth.

## Dimensions

Events store one dimension for future analysis, but v0 collapses all dimensions into one internal score:

- `ResponsivenessScore`
- `ResolutionQualityScore`
- `SchedulingCommitmentScore`
- `ConversationParticipationScore`

Dimensional scores are not exposed to users.

## Event Matrix

| Event | Affected user | Dimension | Delta |
| --- | --- | --- | --- |
| `FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION` | both users | `ResolutionQualityScore` | `+2` |
| `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE` | both users | `ResolutionQualityScore` | `+2` |
| `FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION` | closing user | `ResolutionQualityScore` | `-1` |
| `FIRST_CHAT_EARLY_UNILATERAL_CLOSE` | closing user | `ResolutionQualityScore` | `-2` |
| `FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE` | inactive counterpart | `ResponsivenessScore` | `-2` |
| `FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED` | user who ignored the request | `ResponsivenessScore` | `-2` |
| `FIRST_CHAT_EXPIRED_NO_DECISION` | unresolved user | `ResponsivenessScore` | `-3` |
| `VISUAL_REVIEW_EXPIRED_NO_DECISION` | unresolved user | `ResponsivenessScore` | `-2` |
| `VISUAL_PERSONAL_MESSAGE_SUBMITTED` | submitting user | `ConversationParticipationScore` | `+1` |
| `SCHEDULING_SLOTS_PROPOSED_ON_TIME` | proposing user | `SchedulingCommitmentScore` | `+1` |
| `SCHEDULING_EXPIRED_NO_PROPOSAL` | user with no proposal on the connection | `SchedulingCommitmentScore` | `-3` |
| `SECOND_CHAT_CONFIRMED_ATTENDED` | explicit on-time second-chat join | `SchedulingCommitmentScore` | `+4` |
| `SECOND_CHAT_LATE_ARRIVAL` | explicit late second-chat join before entry closes | `SchedulingCommitmentScore` | `-2` |
| `SECOND_CHAT_NO_SHOW` | unresolved absence at claim expiry or hard cutoff | `SchedulingCommitmentScore` | `-10` |
| `SECOND_CHAT_MUTUAL_COMPLETION` | accepted mutual second-chat completion | `ResolutionQualityScore` | `+2` |
| `SECOND_CHAT_ABANDONED_AFTER_JOIN` | participant failed to answer after conversation started | `ConversationParticipationScore` | `-5` |
| `SECOND_CHAT_NO_CONVERSATION_STARTED` | both joined but neither sent a message before initial-silence closure | `ConversationParticipationScore` | `-5` |
| `SAFETY_REPORT_DETERMINED_ABUSIVE` | abusive/unjustified reporter | `ResolutionQualityScore` | `-8` |
| `SAFETY_REPORT_CONFIRMED_AGAINST_USER` | reported user on confirmed report with temporary ban | `ResolutionQualityScore` | `-8` |

No reliability events are created for visual approval, visual rejection, visual decisions made on time, reading a partner personal message, creating a safety report, pending safety reports, ordinary insufficient-evidence dismissals, or confirmed safety reports that apply a permanent ban.

## First Chat Mapping

Mutual approval records `FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION` for both users after the first chat is finished and the match moves toward visual review.

Accepted mutual no-spark closure records `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE` for both users. Explicit mutual close rejection remains score-neutral in v0.

First-chat decision mismatch (`APPROVED` plus `REJECTED` after one participant already approved) is treated as a healthy resolved interaction. It records `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE` once for each participant and does not record unilateral-close events or cancellation penalties.

Mutual close request timeout records `FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED` for the responder who did not answer. The requester receives no negative event.

Unilateral first-chat closure uses v0 minimum participation:

```text
FIRST_CHAT_MIN_PARTICIPATION_MESSAGES_PER_USER=2
FIRST_CHAT_MIN_PARTICIPATION_MINUTES=5
```

Minimum participation is met when both users sent at least the configured number of messages and the elapsed time since first-chat start is at least the configured minutes. Closures before that create `FIRST_CHAT_EARLY_UNILATERAL_CLOSE`; closures after that create `FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION`.

If the closer sent at least one message, the counterpart sent no messages, and the configured time threshold has passed, the closer receives no negative event and the inactive counterpart receives `FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE`.

First-chat absolute timeout and inactivity closure record `FIRST_CHAT_EXPIRED_NO_DECISION` for users without a first-chat decision. If one participant already approved and the counterpart times out or abandons without deciding, only the unresolved counterpart receives this event.

Future work: the UI may hide non-safety close options during the first configured minutes. Safety reporting must always remain available.

## Visual Review

Visual approval and rejection do not affect reliability. If the visual review expires, unresolved users receive `VISUAL_REVIEW_EXPIRED_NO_DECISION`.

A successful optional personal-message submission records
`VISUAL_PERSONAL_MESSAGE_SUBMITTED` once per user per match. This is a small
`+1` participation incentive for making the optional personal-message effort.
Opening the partner personal message persists its visual-review read timestamp
but does not create a positive or negative reliability event. Leaving a partner
message unread also has no reliability effect and does not block visual
approval or rejection.

## Scheduling And Second Chat

Submitting scheduling slots before the scheduling deadline records `SCHEDULING_SLOTS_PROPOSED_ON_TIME` once per user per connection.

If scheduling expires, users who never submitted proposals on that connection receive `SCHEDULING_EXPIRED_NO_PROPOSAL`.

Second-chat attendance is explicit-join-based in v0:

```text
CHAT_SECOND_CHAT_ON_TIME_WINDOW_MINUTES=10
CHAT_SECOND_CHAT_ENTRY_WINDOW_MINUTES=20
CHAT_SECOND_CHAT_NO_SHOW_CLAIM_COUNTDOWN_SECONDS=60
CHAT_SECOND_CHAT_MUTUAL_COMPLETION_MINIMUM_CONVERSATION_MINUTES=30
CHAT_SECOND_CHAT_MUTUAL_COMPLETION_REQUEST_COUNTDOWN_SECONDS=60
CHAT_SECOND_CHAT_MUTUAL_COMPLETION_REQUESTER_COOLDOWN_SECONDS=60
CHAT_SECOND_CHAT_INACTIVITY_CLAIMABLE_AFTER_MINUTES=5
CHAT_SECOND_CHAT_INACTIVITY_AUTOMATIC_CLOSE_AFTER_MINUTES=10
CHAT_SECOND_CHAT_INACTIVITY_CLAIM_COUNTDOWN_SECONDS=60
CHAT_SECOND_CHAT_INITIAL_SILENCE_AUTOMATIC_CLOSE_AFTER_MINUTES=10
```

Only explicit second-chat join records attendance. A user who joins during `confirmedDateTime <= now < confirmedDateTime + 10 minutes` receives `SECOND_CHAT_CONFIRMED_ATTENDED`. A user who joins during `confirmedDateTime + 10 minutes <= now < confirmedDateTime + 20 minutes` receives `SECOND_CHAT_LATE_ARRIVAL` and not the on-time event. At the hard cutoff, unresolved absences receive `SECOND_CHAT_NO_SHOW`; if neither user joined, both receive it. Sending or fetching messages never creates attendance events. After both users join, accepted mutual completion records `SECOND_CHAT_MUTUAL_COMPLETION` once for each participant. Partner inactivity records `SECOND_CHAT_ABANDONED_AFTER_JOIN` once only for the participant who failed to answer the latest conversational message. Initial silence records `SECOND_CHAT_NO_CONVERSATION_STARTED` once for each participant. Absolute timeout is score-neutral.

Future work: early second-chat cancellation should move the connection back to scheduling; late cancellation should reduce reliability less than no-show but more than early cancellation; proper rescheduling may have a small positive score effect.

## Safety Admin Resolution

Admins can resolve a pending report as abusive or unjustified through:

```text
POST /api/admin/safety-reports/{reportId}/abusive-dismissal
```

This sets `SafetyReportStatus.DISMISSED_ABUSIVE_OR_UNJUSTIFIED`, creates no safety sanction, and records `SAFETY_REPORT_DETERMINED_ABUSIVE` against the reporter when a user reporter exists and `USER_RELIABILITY_ENABLED=true`.

Ordinary dismissal does not create reliability events. Confirming a report with `TEMPORARY_BAN` records `SAFETY_REPORT_CONFIRMED_AGAINST_USER` against the reported user with `relatedSafetyReportId`; confirming with `PERMANENT_BAN` remains reliability-neutral.

## Matchmaking Impact

When disabled, matchmaking remains unchanged.

When enabled in `LEGACY_EARLY_ACCEPT` ranking mode, `MatchmakingService`
applies a small bounded deterministic modifier after hard filters and
compatibility scoring. The modifier is capped by:

```text
USER_RELIABILITY_MATCHMAKING_MAX_MODIFIER=0.05
```

In `PROBABILISTIC_WEIGHTED` ranking mode, reliability contributes through
pairwise reliability similarity and waiting-time relaxation instead of the
legacy deterministic modifier.

Reliability never bypasses eligibility, safety blocks, active locks, distance,
profile filters, or the base compatibility threshold. It does not hard-ban or
suspend low-score users.

## Engagement Capacity Impact

`UserReliabilityScore` also affects admission capacity for new Match
opportunities. The normal neutral baselines are:

```text
engagement.max-active-matches=5
engagement.max-active-connections=4
```

`EngagementCapacityPolicy` derives effective Match and Connection caps from the
current decayed score. The derived caps are not persisted, and there is no
persisted reliability tier. One explicit backend `now` is used for a capacity
decision; final two-user Match admission captures that `now` after acquiring
the participant user locks, then evaluates both users with the same timestamp
and a batched reliability score lookup.

The curve is continuous and then discretized with normal Kotlin nearest-integer
rounding:

```text
delta = effectiveScore - reliabilityBaseScore
saturating(x, scale) = x² / (x² + scale²)

delta >= 0:
  cap = round(base + (max - base) * saturating(delta, rewardScale))

delta < 0:
  cap = round(base - (base - min) * saturating(abs(delta), penaltyScale))

cap is coerced to [min, max]
```

Default Match parameters: base `5`, min `3`, max `9`, reward scale `20`,
penalty scale `10`. Default Connection parameters: base `4`, min `2`, max `6`,
reward scale `30`, penalty scale `10`. The asymmetry is intentional: poor
reliability reduces capacity relatively quickly, positive reliability earns
extra capacity more gradually, and the Connection positive curve is more
conservative. With defaults, about `-10` reliability delta gives `4` Matches and
`3` Connections, while about `+10` gives `6` Matches and `4` Connections.
Startup validation requires the configured neutral baseline to fit inside its
dynamic curve range: `match.min <= engagement.max-active-matches <= match.max`
and
`connection.min <= engagement.max-active-connections <= connection.max`.

Capacity is an admission cap only. It does not affect already-admitted Matches,
already-admitted Visual Reviews, already-created Connections or lifecycle
progression. If a user's derived cap falls below current active locks, the
backend keeps every existing engagement and blocks only new Match admission
until counts fall below the current effective caps. `ConnectionService` does not
check Connection capacity when progressing an already-admitted Match into a
Connection, so natural overshoot is valid.

Availability and final admission keep stable domain codes
`ACTIVE_MATCH_LIMIT_REACHED` and `ACTIVE_CONNECTION_LIMIT_REACHED`, but backend
responses do not expose numeric effective caps to users.

## Capacity Observability

The capacity feedback loop is an explicit product experiment: good behavior can
increase opportunities, which can create more chances to produce positive
reliability events; poor behavior can reduce opportunities and reinforce lower
throughput. This is not treated as a bug by definition, but it must remain
observable.

Micrometer metrics under `reals.engagement.capacity.*` record low-cardinality
aggregate signals:

- evaluation phase: `availability`, `final_match_admission` or `queue_reconciliation`
- reliability direction: `below_base`, `neutral` or `above_base`
- outcome: `allowed`, `blocked_match_cap` or `blocked_connection_cap`
- effective Match cap distribution
- effective Connection cap distribution
- absolute distance from the reliability base score, tagged only by direction

Metrics do not tag by user id or raw score. The backend emits these Micrometer
meters and Actuator exposes them in configured runtimes, but this feature does
not add Prometheus, CloudWatch, OTLP, Grafana or any other durable metrics
backend. Durable longitudinal retention across process restarts or deployments
requires an external metrics registry/backend.

The operational diagnostic query in `docs/engagement-capacity-diagnostics.sql`
can inspect the current/recent population by derived score, effective caps,
active lock counts, headroom and overshoot.

Reliability events are temporally bounded and expired events are deleted. The
operational database can reconstruct current or recent reliability-derived
capacity from retained events, but it cannot provide indefinite historical
per-user score/cap trajectories after those events are gone. Long-term cohort
or causal analysis requires a separate analytics/history pipeline, which is
outside this task.

## Persistence And Idempotency

Reliability events live in `user_reliability_events`. Events include related match, connection, chat, and safety-report ids where applicable. Partial unique indexes prevent duplicate events for retried endpoints and repeated lifecycle jobs.

Expired events are deleted by `UserReliabilityEventCleanupJob`. General audit/history is handled outside this table.

## Local Debug Response

The local debug endpoint returns:

- `userId`
- `enabled`
- `baseScore`
- `weightedDelta`
- `effectiveScore`
- active reliability events
- each event's raw `delta`, temporal weight and effective delta
- related match, connection, chat and safety-report ids
- `metadata`, currently empty in v0

Manual validation flow:

1. Enable `USER_RELIABILITY_ENABLED=true` in a local profile.
2. Trigger a first-chat reliability event.
3. Call `GET /api/local-dev/user-reliability/{userId}`.
4. Confirm the event and weighted score are present.
5. Confirm visual approval/rejection does not add events.
6. Confirm mutual close and timeout paths show the expected score breakdown.

## Non-Goals

- no frontend changes
- no user-visible score or guide
- no score in API responses
- no Android UI blocking for early close
- no second-chat early cancellation
- no hard reliability tier or reliability-only rejection
- no admin dashboard redesign beyond the minimal abusive/unjustified report resolution
- no direct change to safety sanction semantics
