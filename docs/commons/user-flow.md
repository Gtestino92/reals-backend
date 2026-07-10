# User Flow

This document describes the current backend flow. It separates implemented behavior from future product ideas.

## 1. User And Profile

Local no-auth development can inject a fixed authenticated user through `DevAutoAuthFilter`. The default local Firebase profile and shared environments use Firebase-backed current-user resolution.

A user creates one profile. The profile starts as `DRAFT`; only `ACTIVE` profiles can enter matchmaking. Activation validates configured photo requirements.

Profile trust-provider shortcuts are execution-profile aware. Outside `prod`,
provider `none` preserves MVP compatibility for local/dev/test flows: identity
verification may return `VERIFIED`, photo moderation may return `APPROVED`, and
technical photo validation may produce `isPersonPhoto=true`,
`isFullBody=true` and `validationStatus=VALIDATED`. In `prod`, provider `none`
does not create positive trust facts: identity verification returns
`409 IDENTITY_VERIFICATION_NOT_CONFIGURED`, photo moderation persists
`NEEDS_REVIEW`, and technical photo validation alone persists
`false`/`false`/`PENDING`. Successful image decoding is not semantic
person/full-body validation. Production activation defaults to requiring
moderation approval in addition to the existing validated/person/full-body photo
counts.

Photo moderation has a small admin human-review loop. Automated/provider
moderation can produce `APPROVED`, `REJECTED` or `NEEDS_REVIEW`.
`NEEDS_REVIEW` photos appear in `/api/admin/profile-photos/review` for admins
with `ROLE_ADMIN`; an admin can resolve them through
`POST /api/admin/profile-photos/{photoId}/moderation` as `APPROVED` or
`REJECTED` by submitting the `expectedPhotoVersion` returned by the queue item
they reviewed. If the photo changed after the queue was loaded, the resolution
returns `409 PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE`; the admin should
refresh and review the current photo again. This is a content-moderation
decision, not user-visible moderation scoring. It does not change semantic
validation fields such as `validationStatus`, `isPersonPhoto` or `isFullBody`.

Legal compliance is backend-authoritative for protected participation/content
writes. After provisioning, clients can call `GET /api/me/legal-status`; when
`requirementsSatisfied=false`, they should show the current legal requirements,
use `GET /api/legal/documents/current` for URL metadata as needed, submit the
required factual actions with `POST /api/me/legal-document-actions`, and refresh
legal status. The Android client may route based on this status, but backend
protected operations are the enforcement boundary.

Any protected backend request may return `409 LEGAL_ACTION_REQUIRED`. For
future Android clients, that stable code means refresh legal status and route to
the legal requirements UI. Legal state is not part of Home and is not modeled as
a Home pending action.

Reads, legal endpoints, account deletion/reactivation, chat exit/cancellation
and safety/reporting flows remain available without current legal compliance.
`APPROVED` first-chat and visual decisions require compliance; `REJECTED`
decisions remain available. Scheduling proposal submission, proposal acceptance
and scheduling-round rejection require compliance.

## 2. Matchmaking Queue

Users enter the queue through:

```text
MatchmakingService.enqueue(userId)
```

Eligibility checks include:

- no active penalty
- existing active profile
- not already queued
- below active match limit
- below active connection limit

Candidate pairs are processed by `MatchmakingProcessorService`, normally through `MatchmakingJob` in dev/prod or through the dev-only manual endpoint in local/Bruno flows. Candidate selection is delegated to `MatchmakingService.findNextCandidatePair`. The queue repository first returns up to `matchmaking.candidate-pair-limit` hard-filtered candidate pairs using active profiles, mutual gender preference, intention and mutual preferred age range. `MatchmakingService` then enforces mutual maximum distance from the search location captured when each user entered the queue, and `CompatibilityScorer` chooses the best remaining pair. Scores below `matchmaking.min-compatibility-score` are ignored; a score at or above `matchmaking.early-accept-compatibility-score` is accepted immediately; otherwise the highest score wins with FIFO order as the tie-breaker. Match creation is delegated to `MatchService.createMatch`, which creates the match, creates locks and removes both users from the queue. `ChatService.startFirstChat` then creates the anonymous first chat.

## 3. First Chat

A new match starts in `CHAT_ACTIVE`. The first chat is created separately:

```text
ChatService.startFirstChat(matchId)
```

Messages can be sent only when the chat is active, not timed out, not abandoned
by inactivity and the sender belongs to the match. Sending a message updates
`Chat.lastMessageAt`.

Clients discover active first chats through `GET /api/me/home`. While a match is
in `CHAT_ACTIVE`, Home includes a `pendingActions[]` item with
`type = FIRST_CHAT`, the first-chat id and a partner summary (`userId`,
`profileId`, `displayName`) only if the chat is active, not expired and the
current user has not decided. When the match moves to `VISUAL_PHASE`, the first
chat action disappears. Expired or already-decided actions are not returned by
Home.

Home also returns `matchmaking`, `activeInteractionsSummary`, `nextSteps` and
`passiveNotices` so clients can render navigation without deriving actions from
raw `MatchState`, `ConnectionState` or expiration timestamps.

`GET /api/matches/{matchId}/chat` returns the active first chat plus `partner`,
`myDecision`, `partnerDecision`, `expiresAt`, `inactivityExpiresAt` and nullable
`guidance` metadata. New first chats initialize guidance; legacy chats may have
`guidance = null`. The decision fields are API-facing statuses from the current
user's perspective: `PENDING`, `APPROVED`, `REJECTED` or `ABANDONED`.

First-chat guidance is an MVP conversation prompt mechanic owned by the backend.
The Spanish question catalog is a static resource, and each first chat derives a
deterministic sequence from the chat id and catalog order. One active question is
shared by both participants and persisted as an id/text snapshot when activated.
Users can chat freely; the backend does not semantically evaluate answers. A
participant can request another question only after sending at least the
configured `chat.first-chat.guidance.required-characters` threshold during the
current question interval. One long message can satisfy this threshold. The
question advances only after both
participants independently request it, and partner readiness/request state is not
exposed. A first chat has at most 3 questions. When both users request
continuation from the penultimate question and the final configured question
becomes active, guidance completes immediately, no question 4 is selected, and
the final question remains available as the final prompt. Clients observe
question changes through the existing first-chat polling response. No analytics
events are implemented for guidance.

For compatibility with rows created before this completion semantics change, a
guidance row already at the final configured ordinal with `completedAt = null`
is treated as completed. The backend normalizes `completedAt` and clears
next-request timestamps when that row is read or mutated.

Client countdowns are advisory. The backend remains the source of truth and
rejects first-chat mutations with `CHAT_EXPIRED` after the absolute deadline or
`CHAT_ABANDONED` after the inactivity deadline.

Message polling can use `GET /api/chats/{chatId}/messages` for the initial legacy full list, then `GET /api/chats/{chatId}/messages?after={messageId}` or `afterMessageId={messageId}` for incremental responses shaped as `{ "messages": [...], "hasMore": false, "serverTime": "..." }`.

## 4. Chat Decision

Each user can approve continuation, request mutual cancellation or cancel explicitly.

- Mutual `APPROVED`: first chat becomes `FINISHED`, match moves to `VISUAL_PHASE`, visual review is initialized.
- `REJECTED` is treated as unilateral cancellation: first chat becomes `CANCELLED`, match moves to `CHAT_REJECTED`, locks are released and penalty policy is evaluated.
- Mutual cancellation request accepted by the other participant cancels the chat without penalty.
- Mutual cancellation request rejected by the other participant also cancels the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- Mutual cancellation request timeout is resolved by a client call after `chat.exit-request.mutual-timeout-seconds`; it cancels the chat without penalty. This is not a unilateral cancellation, and the requester must not be penalized for resolving an unanswered request.
- Safety cancellation cancels the chat, records `ChatEndReason.SAFETY_REPORT`, exempts the reporter, creates a pending `SafetyReport` and creates a directional block from reporter to reported. `CHILD_SAFETY_CONCERN` is preserved explicitly from the accepted exit request to the report reason. It is a reported concern, not a confirmed violation, and does not penalize the reported participant until admin/backoffice review confirms the report.

Approval still requires both users. Cancellation can end the chat earlier through mutual acceptance, mutual rejection, mutual timeout, unilateral cancellation or safety cancellation.

## 5. Visual Review

Each user submits one `VisualDecision`.

- The deciding user's visual review disappears from Home and that user's match lock is released immediately.
- Repeating the same decision is idempotent. Trying to change a recorded decision is rejected.
- If one user rejects while the other has not decided, the match remains in `VISUAL_PHASE` for the pending participant. This avoids an immediate rejection signal through Home.
- When both users have decided, mutual `APPROVED` moves the match to `VISUAL_APPROVED` and creates a pending connection.
- When both users have decided and at least one rejected, the match moves to `VISUAL_REJECTED` and remaining match locks are released.

Personal messages are stored on `VisualReview`. Current behavior allows reading
the partner message during visual review once it exists, and requires reading it
before deciding if the partner already submitted one. A successful optional
personal-message submission also records a small backend-internal reliability
participation event when user reliability is enabled. The message remains
optional, and reading the partner message does not create a reliability event.

Match and visual-profile responses expose `visualExpiresAt` so clients can warn
before the visual phase expires. New visual decisions after that deadline are
rejected by the backend.

## 6. Connection Creation

`ConnectionService.createFromMatch(match)` creates a connection after visual approval.

It validates active connection limits and creates `CONNECTION` locks immediately. A connection starts in `SCHEDULING_PENDING` with `schedulingAvailableAt`; it occupies connection capacity but is not yet actionable in Home. `SCHEDULING_PENDING` is a deferred activation state, not a user-driven coordination state.

## 7. Scheduling

Scheduling is activated later by `SchedulingActivationJob` when
`schedulingAvailableAt <= now`. The job moves the connection to
`SCHEDULING_PHASE` and initializes negotiation idempotently. Until then, Home
does not include the connection in `nextSteps`; clients can see it only through
`activeInteractionsSummary.pendingSchedulingConnectionCount` and the passive
notice `SCHEDULING_PREPARING`.

`SchedulingNegotiationTimeoutJob` applies only after activation, while the
connection is in `SCHEDULING_PHASE`. The `schedulingExpiresAt` value created
with `SCHEDULING_PENDING` is provisional; activation recalculates the actionable
deadline from the moment scheduling becomes available. In local profiles, where
schedulers are disabled, run `SchedulingActivationJob` manually before testing
scheduling proposals or scheduling timeout.

Once active, users submit ordered lists of future date/time proposals for the second chat inside the app. This is not the same as scheduling an in-person meeting; any real-world meeting is outside the backend's current scope.

Rules:

- each user submits one proposal list per round
- each list must contain 1 to `scheduling.max-proposals-per-round` unique future slots
- slots must be aligned to half-hour boundaries
- user must belong to the connection
- user cannot accept their own proposal
- a participant can accept a partner proposal without first submitting their own list
- overlapping proposed instants auto-confirm

If more than one slot overlaps, the backend chooses the slot with the lowest combined preference order. If that still ties, it chooses the earliest agreed slot. If there is no overlap after both users submit, the backend does not immediately open the next round. Proposals remain visible so either participant can accept one partner slot or explicitly reject the current round. A user-triggered rejection opens the next round automatically unless max rounds has been reached.

Confirmation marks the negotiation as `CONFIRMED`, stores `confirmedDateTime` as the agreed second-chat start time and moves the connection to `SECOND_CHAT_SCHEDULED`.

If max rounds are exceeded or scheduling expires, the negotiation becomes `FAILED` and the connection closes.

Negotiation responses expose the parent connection's `schedulingExpiresAt` so
clients can warn before the scheduling phase expires. Scheduling mutations after
that deadline are rejected by the backend; the timeout job performs the
persistent failed/closed transition when it runs.

## 8. Second Chat

Second chat is materialized on demand when a participant enters at or after the
agreed `confirmedDateTime`. `GET /api/connections/{connectionId}/chat` validates
that the connection belongs to the authenticated user, checks that the confirmed
window is open, creates the `SECOND_CHAT` if needed, and moves the connection to
`SECOND_CHAT`:

```text
ChatService.findVisibleSecondChatOrThrow(connectionId, userId)
```

Home exposes the agreed start time as `nextSteps[].secondChat.availableAt` for
`SECOND_CHAT_SCHEDULED`. Clients may enable
entry from `availableAt` and should show the agreed time before
that window. `secondChat.expiresAt` is the end of the writable second-chat
window, and `secondChat.durationMinutes` exposes the configured writable
duration (`chat.second-chat.duration-minutes`, currently 120 minutes).

The chat is created as `ACTIVE` when a participant enters it through `GET /api/connections/{connectionId}/chat`. At that moment the backend sets `activatedAt`; `timeoutAt` remains the configured end of the agreed writable window (`availableAt + durationMinutes`).

If the agreed second-chat window expires before a chat is created, the backend
does not create a stale chat and the lifecycle job closes the scheduled
connection. This case had no messages, so there is no read-only period.

`SecondChatLifecycleJob` owns the lifecycle after scheduling confirmation. When
an active second chat reaches `timeoutAt`, it moves the chat to `EXPIRED`, sets
`readOnlyUntil` using `chat.second-chat.read-only-retention-minutes` (currently
24 hours), and leaves the connection visible in Home as `SECOND_CHAT_READ_ONLY`.
Messages remain readable, but new messages are rejected because the chat is no
longer `ACTIVE`. When `readOnlyUntil` is reached, the same job marks the chat
`CLOSED`, closes the connection and releases locks; the interaction then
disappears from Home.

Explicit second-chat cancellation closes the connection and releases locks. Mutual acceptance, mutual rejection and mutual timeout all close without penalty today. Unilateral cancellation uses penalty policy evaluation, and safety-based cancellation creates a pending report for backoffice review without immediate penalty. Second-chat timeout moves the chat to read-only first; read-only retention cleanup closes the connection. First-chat timeout still expires the match.

## 9. Safety Report Review

Safety-report chat closure creates:

- an accepted `ChatExitRequest` with `type = SAFETY_REPORT`, used as operational chat-closure history;
- a `SafetyReport` with `status = PENDING`, used as the moderation source of truth.
- a directional `UserBlock` from reporter to reported; matchmaking treats any block between two users as a bidirectional rematch exclusion.

Admins access `/api/admin/safety-reports` with `ROLE_ADMIN`. Dismissing a pending report stores review metadata and creates no penalty. Confirming a pending report creates either a temporary or permanent penalty for the reported user, links it through `sourceReportId`/`penaltyId`, removes the reported user from the matchmaking queue if present and blocks future enqueue while the penalty remains active.

Pending reports with reason `CHILD_SAFETY_CONCERN` receive derived priority review: admin lists order them before other reports, then order by creation time descending. Priority is not persisted and becomes false after review. Direct user reports retain the existing directional block and active-interaction containment behavior; no penalty or ban is automatic from this reason.

Admins can also dismiss a report as abusive or unjustified. That resolution creates no safety penalty; when user reliability is enabled, it records an internal reliability event against the reporter. Pending reports, ordinary insufficient-evidence dismissals and confirmed reports against the reported user do not create reliability events.

## 10. Completion

A connection eventually reaches `CLOSED`. Closure releases active connection locks, so users are no longer counted against the connection limit for that interaction.

## 11. Manual block

A participant may submit `POST /api/matches/{matchId}/block`; the backend resolves the counterpart without exposing their user id. The block immediately excludes the pair and contains every active match or connection. Positive progression then returns `USER_PAIR_BLOCKED`, while reads, rejection, exit, cancellation, and safety remain available. Android must present definitive-action confirmation before submission; Android UI and unblock are not part of this backend MVP.
