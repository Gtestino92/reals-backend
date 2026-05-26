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

Candidate pairs are selected by `MatchmakingService.findCandidatePairs`. Match creation is delegated to `MatchService.createMatch`, which creates the match, creates locks and removes both users from the queue.

## 3. First Chat

A new match starts in `CHAT_ACTIVE`. The first chat is created separately:

```text
ChatService.startFirstChat(matchId)
```

Messages can be sent only when the chat is active, not timed out and the sender belongs to the match. Sending a message updates `Chat.lastMessageAt`.

## 4. Chat Decision

Each user submits one `ChatContinueDecision`.

- Mutual `APPROVED`: first chat becomes `FINISHED`, match moves to `VISUAL_PHASE`, visual review is initialized.
- Any `REJECTED`: first chat becomes `FINISHED`, match moves to `CHAT_REJECTED`, locks are released.

No transition occurs until both users have decided.

## 5. Visual Review

Each user submits one `VisualDecision`.

- Mutual `APPROVED`: personal messages become visible, match moves to `VISUAL_APPROVED`, connection is created and scheduling is initialized.
- Any `REJECTED`: match moves to `VISUAL_REJECTED`, locks are released.

Personal messages are stored on `VisualReview` and are only visible after mutual visual approval.

## 6. Connection Creation

`ConnectionService.createFromMatch(match)` creates a connection after visual approval.

It validates active connection limits and upgrades engagement locks from `MATCH` to `CONNECTION`. A connection starts in `SCHEDULING_PHASE`.

## 7. Scheduling

Scheduling is initialized once per connection. Users submit future date/time proposals for the second chat inside the app. This is not the same as scheduling an in-person meeting; any real-world meeting is outside the backend's current scope.

Rules:

- one pending proposal per user per round
- proposal must be in the future
- user must belong to the connection
- user cannot accept their own proposal
- acceptor must have submitted their own proposal before accepting the partner proposal
- exact matching proposed instants can auto-confirm

Confirmation marks the negotiation as `CONFIRMED`, moves the connection to `SECOND_CHAT` and the controller starts the second chat.

If max rounds are exceeded or scheduling expires, the negotiation becomes `FAILED` and the connection closes.

## 8. Second Chat

Second chat starts after scheduling confirmation:

```text
ChatService.startSecondChat(matchId, connectionId)
```

Closing the second chat closes the connection and releases locks. Timeout closes the connection. Abandonment may create penalties for abandoned users before closure.

## 9. Completion

A connection eventually reaches `CLOSED`. Closure releases active connection locks, so users are no longer counted against the connection limit for that interaction.
