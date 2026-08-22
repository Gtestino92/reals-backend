# State Machine

Business state transitions must be explicit and validated in services.

## MatchState

Allowed transitions:

- `CHAT_ACTIVE -> VISUAL_PHASE`
- `CHAT_ACTIVE -> CHAT_REJECTED`
- `CHAT_ACTIVE -> EXPIRED`
- `VISUAL_PHASE -> VISUAL_APPROVED`
- `VISUAL_PHASE -> VISUAL_REJECTED`
- `VISUAL_PHASE -> EXPIRED`

`VISUAL_PHASE` starts immediately after mutual positive first-chat resolution.
The associated `VisualReview` may be hidden until its persisted `availableAt`;
this waiting period does not introduce another Match state. During
`VISUAL_PHASE`, visual review actions are allowed only while
`serverNow >= availableAt` and `serverNow < expiresAt`.

Terminal states:

- `CHAT_REJECTED`
- `VISUAL_REJECTED`
- `EXPIRED`

`VISUAL_APPROVED` is terminal for the match flow and leads to connection creation.

## ChatStatus

Allowed transitions:

- `AVAILABLE -> ACTIVE`
- `AVAILABLE -> CANCELLED` only for explicit account-deletion closure
- `AVAILABLE -> CLOSED` only for expired unactivated second-chat cleanup
- `ACTIVE -> FINISHED`
- `ACTIVE -> CANCELLED`
- `ACTIVE -> EXPIRED`
- `ACTIVE -> ABANDONED`
- `EXPIRED -> CLOSED` only for second-chat read-only retention cleanup
- `ABANDONED -> CLOSED` for second-chat no-show read-only retention cleanup

`ACTIVE -> ABANDONED` is the first-chat inactivity closure. It can be applied by
the inactivity job or by endpoint validation before a stale mutation is accepted.

`ChatStatus` remains the operational state. Persisted `ChatEndReason` records
why a chat ended, such as safety report, unilateral cancellation, first-chat
decision mismatch, absolute timeout, inactivity timeout, account deletion or
second-chat read-only cleanup.

Terminal states:

- `FINISHED`
- `CANCELLED`
- `EXPIRED` for first chats; for second chats this is read-only until cleanup
- `ABANDONED` for second-chat no-show with an existing chat; readable until `readOnlyUntil`
- `ABANDONED`
- `CLOSED`

## ConnectionState

Allowed transitions:

- `SCHEDULING_PENDING -> SCHEDULING_PHASE`
- `SCHEDULING_PENDING -> CLOSED`
- `SCHEDULING_PHASE -> SECOND_CHAT_SCHEDULED`
- `SCHEDULING_PHASE -> CLOSED`
- `SECOND_CHAT_SCHEDULED -> SECOND_CHAT_AVAILABLE`
- `SECOND_CHAT_SCHEDULED -> CLOSED`
- `SECOND_CHAT_AVAILABLE -> SECOND_CHAT`
- `SECOND_CHAT_AVAILABLE -> CLOSED`
- `SECOND_CHAT -> CLOSED`

Terminal state:

- `CLOSED`

## NegotiationStatus

Allowed transitions:

- `PENDING -> CONFIRMED`
- `PENDING -> FAILED`

Terminal states:

- `CONFIRMED`
- `FAILED`

## ProposalStatus

Allowed transitions:

- `PENDING -> ACCEPTED`
- `PENDING -> REJECTED`

Terminal states:

- `ACCEPTED`
- `REJECTED`

## ChatExitRequestStatus

Allowed transitions:

- `PENDING -> ACCEPTED`
- `PENDING -> REJECTED`
- `PENDING -> TIMED_OUT`

Terminal states:

- `ACCEPTED`
- `REJECTED`
- `TIMED_OUT`

All terminal mutual-cancellation resolutions close the chat as `CANCELLED`.
`TIMED_OUT` is client-triggered after the configured mutual cancellation
timeout; it is not a unilateral cancellation and must not penalize the
requester under future scoring semantics.

## First-Chat Decision Boundary

While both first-chat decisions are `PENDING`, ordinary conversation, mutual
cancellation, unilateral cancellation, safety report and manual block follow the
normal first-chat rules. Once one participant persists `APPROVED` and the other
participant remains `PENDING`, the active first chat is in decision-only mode:
reads, polling, safety/report and manual block remain available for both
participants, but ordinary text/audio send, guidance advancement, new mutual
cancellation and direct unilateral cancellation are rejected for both
participants with `FIRST_CHAT_DECISION_ONLY`. Only the unresolved participant
can submit the remaining final `APPROVED` or `REJECTED`; the already-decided
participant cannot replace their persisted decision. Decision-only mode disables
first-chat inactivity timeout and exposes no `inactivityExpiresAt`; the original
absolute `timeoutAt` continues to apply.

If the unresolved participant submits `REJECTED`, the backend persists that
decision and closes the completed decision process as
`FINISHED / FIRST_CHAT_DECISION_MISMATCH`; the match moves to `CHAT_REJECTED`
and match locks are released. This is not `MUTUAL_CANCEL` or
`UNILATERAL_CANCEL`, creates no cancellation request and creates no ordinary
cancellation penalty.

## Lock Behavior

- Match creation creates `MATCH` locks for both users.
- Chat rejection or match expiration deletes `MATCH` locks for both users.
- A visual decision deletes the deciding user's `MATCH` lock immediately.
- Mutual visual approval creates a `SCHEDULING_PENDING` connection and `CONNECTION` locks immediately. This pending connection counts against connection capacity even though it is not actionable in Home `nextSteps`.
- Visual rejection closes the match and releases any remaining `MATCH` locks after both users decide or visual phase expiration handles the match.
- Connection closure deletes `CONNECTION` locks.
- Account deletion closes active visible chats, rejects in-progress match phases, closes active connections, fails pending scheduling negotiations, deletes the deleted user's locks and moves the profile back to `DRAFT` while preserving historical rows. Reactivation restores the user account only; it does not reopen prior engagements.

State transitions and lock changes should stay coupled in service methods.

Capacity limits gate admission to new matchmaking opportunities; they are not
lifecycle transition guards for engagements that already exist. Match capacity
limits new Match creation. The Visual Advancement Cap limits future matchmaking
from recent `VisualReview.createdAt` throughput. Connection capacity limits
future matchmaking from active `CONNECTION` locks. Existing engagements may
temporarily take counts above current limits while progressing, and that
overshoot is valid until later closures release capacity.

## Block containment

- `CHAT_ACTIVE -> CHAT_REJECTED`; an active first chat becomes `CANCELLED / USER_BLOCK`.
- `VISUAL_PHASE -> VISUAL_REJECTED`; visual decisions are unchanged.
- Every active Connection state transitions to `CLOSED`.
- An `AVAILABLE` or `ACTIVE` second chat becomes `CANCELLED / USER_BLOCK`; terminal chat history is unchanged.

Existing rejection and closure operations release locks. Positive transitions are guarded by pair-wide `USER_PAIR_BLOCKED`; cleanup, rejection, exit, safety, and read paths remain available.

Scheduling mutations (`addProposals`, `acceptProposal`, partner proposal
rejection and scheduling expiration) serialize on the `ScheduleNegotiation` row
for the connection before reading or mutating current-round proposal state.
Clients include `expectedRoundNumber` for proposal submission and partner
proposal rejection; stale mutations return `SCHEDULING_ROUND_CHANGED` instead
of being silently applied to a newer round. Rejecting partner proposals marks
only the partner's pending proposals `REJECTED`. The scheduling round remains
`PENDING` until both users have submitted in that round and no current-round
proposal remains `PENDING`; then the backend opens the next round or fails the
negotiation and closes the connection on the final configured round.
Proposal instants can pass while their rows remain `PENDING`. The backend does
not automatically reject those rows or advance the round. Explicit acceptance
returns `SCHEDULING_PROPOSAL_NOT_AVAILABLE` when the proposal instant is not
strictly in the future, and overlap auto-confirm ignores expired overlapping
instants while still considering future overlaps.

Scheduling slot conflict checks compare instants against a symmetric inclusive
window around confirmed second-chat starts on other connections for the same
users. Only `SECOND_CHAT_SCHEDULED` and `SECOND_CHAT_AVAILABLE` reserve slots.
Confirmation paths lock both participant user rows in deterministic UUID order
before checking other confirmed negotiations and before mutating proposal or
negotiation status.


## Second Chat

Second-chat entry and conversation lifecycle are server-authoritative. Explicit join records attendance; mutual completion, partner inactivity, initial silence, absolute timeout and read-only cleanup are owned by `SecondChatLifecycleJob` and request-triggered lifecycle services. Ordinary mutual/unilateral cancellation is not available for second chats.

Text and audio chat messages are both conversational messages for lifecycle purposes. Before new insertion, message sends revalidate due terminal outcomes under the chat lock; if the chat remains writable, either message type updates conversational activity and cancels pending mutual-completion or partner-inactivity requests through the same pre-message path. Idempotent TEXT or AUDIO replay for an already-persisted `(chat, sender, clientMessageId)` returns the canonical message before ordinary mutable eligibility checks only when the semantic payload matches, including message type and direct reply target. TEXT equality uses normalized content plus direct target; AUDIO equality uses accepted audio SHA-256 plus direct target. Reusing the key with a different semantic payload returns `CHAT_MESSAGE_IDEMPOTENCY_CONFLICT`, while an exact replay can be retried after the chat later becomes terminal without reapplying activity side effects.

Message `HEART` reactions are metadata, not lifecycle transitions. A new reaction reuses the chat write lock and writable-state validation, but it does not create a message, update conversational activity, reset inactivity, extend deadlines, cancel pending second-chat requests, produce reliability/penalty effects or advance first-chat/second-chat state. Already-persisted reactions remain visible in read-only states for as long as the underlying `chat_messages` row is retained.
