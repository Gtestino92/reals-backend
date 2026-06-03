# User Flow

This document describes the current backend flow. It separates implemented behavior from future product ideas.

## 1. User And Profile

Local development injects a fixed authenticated user through `DevAutoAuthFilter`. Real authentication is intended to use Firebase-backed current-user resolution.

A user creates one profile. The profile starts as `DRAFT`; only `ACTIVE` profiles can enter matchmaking. Activation validates configured photo requirements.

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

Candidate pairs are processed by `MatchmakingProcessorService`, normally through `MatchmakingJob` in dev/prod or through the dev-only manual endpoint in local/Bruno flows. Candidate selection is delegated to `MatchmakingService.findNextCandidatePair`. The queue repository first returns up to `matchmaking.candidate-pair-limit` hard-filtered candidate pairs using active profiles, mutual gender preference, intention and mutual preferred age range. `MatchmakingService` then enforces mutual maximum distance from the search location captured when each user entered the queue, and `CompatibilityScorer` chooses the best remaining pair. Scores below `matchmaking.min-compatibility-score` are ignored; a score at or above `matchmaking.early-accept-compatibility-score` is accepted immediately; otherwise the highest score wins with FIFO order as the tie-breaker. Match creation is delegated to `MatchService.createMatch`, which creates the match, creates locks and removes both users from the queue. `ChatService.startFirstChat` then creates the anonymous first chat.

## 3. First Chat

A new match starts in `CHAT_ACTIVE`. The first chat is created separately:

```text
ChatService.startFirstChat(matchId)
```

Messages can be sent only when the chat is active, not timed out and the sender belongs to the match. Sending a message updates `Chat.lastMessageAt`.

## 4. Chat Decision

Each user can approve continuation, request mutual cancellation or cancel explicitly.

- Mutual `APPROVED`: first chat becomes `FINISHED`, match moves to `VISUAL_PHASE`, visual review is initialized.
- `REJECTED` is treated as unilateral cancellation: first chat becomes `CANCELLED`, match moves to `CHAT_REJECTED`, locks are released and penalty policy is evaluated.
- Mutual cancellation request accepted by the other participant cancels the chat without penalty.
- Safety cancellation cancels the chat, exempts the reporter and applies a penalty to the reported participant.

Approval still requires both users. Cancellation can end the chat earlier through mutual acceptance, unilateral cancellation or safety cancellation.

## 5. Visual Review

Each user submits one `VisualDecision`.

- Mutual `APPROVED`: personal messages become visible, match moves to `VISUAL_APPROVED`, connection is created and scheduling is initialized.
- Any `REJECTED`: match moves to `VISUAL_REJECTED`, locks are released.

Personal messages are stored on `VisualReview` and are only visible after mutual visual approval.

## 6. Connection Creation

`ConnectionService.createFromMatch(match)` creates a connection after visual approval.

It validates active connection limits and upgrades engagement locks from `MATCH` to `CONNECTION`. A connection starts in `SCHEDULING_PHASE`.

## 7. Scheduling

Scheduling is initialized once per connection. Users submit ordered lists of future date/time proposals for the second chat inside the app. This is not the same as scheduling an in-person meeting; any real-world meeting is outside the backend's current scope.

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

## 8. Second Chat

Second chat becomes visible when the agreed `confirmedDateTime` arrives. `ScheduledSecondChatStartJob` finds confirmed negotiations whose start time is due, creates an `AVAILABLE` second chat and moves the connection to `SECOND_CHAT_AVAILABLE`:

```text
ChatService.startSecondChat(matchId, connectionId)
```

The chat becomes `ACTIVE` only when a participant enters it through `GET /api/connections/{connectionId}/chat` or sends the first message. At that moment the backend sets `activatedAt`, recalculates `timeoutAt` from the activation time and moves the connection to `SECOND_CHAT`.

Explicit second-chat cancellation closes the connection and releases locks. It can be mutual without penalty, unilateral with penalty policy evaluation or safety-based with a penalty for the reported participant. Timeout closes the connection. Abandonment may create penalties for abandoned users before closure.

## 9. Completion

A connection eventually reaches `CLOSED`. Closure releases active connection locks, so users are no longer counted against the connection limit for that interaction.
