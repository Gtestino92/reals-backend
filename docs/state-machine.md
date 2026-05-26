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

- `ACTIVE -> FINISHED`
- `ACTIVE -> EXPIRED`
- `ACTIVE -> ABANDONED`

Terminal states:

- `FINISHED`
- `EXPIRED`
- `ABANDONED`

## ConnectionState

Allowed transitions:

- `SCHEDULING_PHASE -> SECOND_CHAT`
- `SCHEDULING_PHASE -> CLOSED`
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

## Lock Behavior

- Match creation creates `MATCH` locks for both users.
- Match rejection or expiration deletes `MATCH` locks.
- Connection creation upgrades locks from `MATCH` to `CONNECTION`.
- Connection closure deletes `CONNECTION` locks.

State transitions and lock changes should stay coupled in service methods.
