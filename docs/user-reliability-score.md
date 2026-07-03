# User Reliability Score

`UserReliabilityScore` is an internal operational score for participation reliability in Reals flows. It is invisible to users and is separate from safety/moderation sanctions.

## Feature Flag

The v0 system is controlled by one global flag:

```text
USER_RELIABILITY_ENABLED=false
```

When disabled, the backend does not create reliability events, calculate score effects, or change matchmaking behavior. There is no observe-only mode in v0, but the event-sourced design can support one later.

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
| `SCHEDULING_SLOTS_PROPOSED_ON_TIME` | proposing user | `SchedulingCommitmentScore` | `+1` |
| `SCHEDULING_EXPIRED_NO_PROPOSAL` | user with no proposal on the connection | `SchedulingCommitmentScore` | `-3` |
| `SECOND_CHAT_CONFIRMED_ATTENDED` | user with a message inside the grace window | `SchedulingCommitmentScore` | `+4` |
| `SECOND_CHAT_NO_SHOW` | user with no message inside the grace window | `SchedulingCommitmentScore` | `-10` |
| `SAFETY_REPORT_DETERMINED_ABUSIVE` | abusive/unjustified reporter | `ResolutionQualityScore` | `-8` |

No reliability events are created for visual approval, visual rejection, visual decisions made on time, creating a safety report, pending safety reports, ordinary insufficient-evidence dismissals, or confirmed safety reports against the reported user.

## First Chat Mapping

Mutual approval records `FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION` for both users after the first chat is finished and the match moves toward visual review.

Accepted mutual no-spark closure records `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE` for both users. Explicit mutual close rejection remains score-neutral in v0.

Mutual close request timeout records `FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED` for the responder who did not answer. The requester receives no negative event.

Unilateral first-chat closure uses v0 minimum participation:

```text
FIRST_CHAT_MIN_PARTICIPATION_MESSAGES_PER_USER=2
FIRST_CHAT_MIN_PARTICIPATION_MINUTES=5
```

Minimum participation is met when both users sent at least the configured number of messages and the elapsed time since first-chat start is at least the configured minutes. Closures before that create `FIRST_CHAT_EARLY_UNILATERAL_CLOSE`; closures after that create `FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION`.

If the closer sent at least one message, the counterpart sent no messages, and the configured time threshold has passed, the closer receives no negative event and the inactive counterpart receives `FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE`.

First-chat absolute timeout and inactivity closure record `FIRST_CHAT_EXPIRED_NO_DECISION` for users without a first-chat decision.

Future work: the UI may hide non-safety close options during the first configured minutes. Safety reporting must always remain available.

## Visual Review

Visual approval and rejection do not affect reliability. If the visual review expires, unresolved users receive `VISUAL_REVIEW_EXPIRED_NO_DECISION`.

## Scheduling And Second Chat

Submitting scheduling slots before the scheduling deadline records `SCHEDULING_SLOTS_PROPOSED_ON_TIME` once per user per connection.

If scheduling expires, users who never submitted proposals on that connection receive `SCHEDULING_EXPIRED_NO_PROPOSAL`.

Second-chat attendance is message-based in v0:

```text
SECOND_CHAT_NO_SHOW_GRACE_MINUTES=10
```

A user who sends any message within the first configured grace minutes after the confirmed second-chat start time receives `SECOND_CHAT_CONFIRMED_ATTENDED`. A user who sends no message in that window receives `SECOND_CHAT_NO_SHOW`. If neither user appears, both receive `SECOND_CHAT_NO_SHOW`.

Future work: early second-chat cancellation should move the connection back to scheduling; late cancellation should reduce reliability less than no-show but more than early cancellation; proper rescheduling may have a small positive score effect.

## Safety Admin Resolution

Admins can resolve a pending report as abusive or unjustified through:

```text
POST /api/admin/safety-reports/{reportId}/abusive-dismissal
```

This sets `SafetyReportStatus.DISMISSED_ABUSIVE_OR_UNJUSTIFIED`, creates no safety sanction, and records `SAFETY_REPORT_DETERMINED_ABUSIVE` against the reporter when a user reporter exists and `USER_RELIABILITY_ENABLED=true`.

Ordinary dismissal and confirmed reports do not create reliability events.

## Matchmaking Impact

When disabled, matchmaking remains unchanged.

When enabled, `MatchmakingService` applies a small bounded deterministic modifier after hard filters and compatibility scoring. The modifier is capped by:

```text
USER_RELIABILITY_MATCHMAKING_MAX_MODIFIER=0.05
```

Reliability never bypasses eligibility, safety blocks, active locks, distance, profile filters, or the base compatibility threshold. It does not hard-ban or suspend low-score users.

Future work: replace the deterministic modifier with a probabilistic modifier to avoid overly rigid queue behavior.

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
- no probabilistic matchmaking modifier
- no admin dashboard redesign beyond the minimal abusive/unjustified report resolution
- no direct change to safety sanction semantics
