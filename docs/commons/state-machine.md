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

`ACTIVE -> ABANDONED` is the first-chat inactivity closure. It can be applied by
the inactivity job or by endpoint validation before a stale mutation is accepted.

`ChatStatus` remains the operational state. Persisted `ChatEndReason` records
why a chat ended, such as safety report, unilateral cancellation, absolute
timeout, inactivity timeout, account deletion or second-chat read-only cleanup.

Terminal states:

- `FINISHED`
- `CANCELLED`
- `EXPIRED` for first chats; for second chats this is read-only until cleanup
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

## Lock Behavior

- Match creation creates `MATCH` locks for both users.
- Chat rejection or match expiration deletes `MATCH` locks for both users.
- A visual decision deletes the deciding user's `MATCH` lock immediately.
- Mutual visual approval creates a `SCHEDULING_PENDING` connection and `CONNECTION` locks immediately. This pending connection counts against connection capacity even though it is not actionable in Home `nextSteps`.
- Visual rejection closes the match and releases any remaining `MATCH` locks after both users decide or visual phase expiration handles the match.
- Connection closure deletes `CONNECTION` locks.
- Account deletion closes active visible chats, rejects in-progress match phases, closes active connections, fails pending scheduling negotiations, deletes the deleted user's locks and moves the profile back to `DRAFT` while preserving historical rows. Reactivation restores the user account only; it does not reopen prior engagements.

State transitions and lock changes should stay coupled in service methods.

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
