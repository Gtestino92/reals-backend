# API

This file summarizes the current controller surface. It is not a full OpenAPI contract.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users

- `POST /api/users`: create a user from request body email.
- `GET /api/me`: fetch the authenticated user.
- `GET /api/users/{userId}`: fetch a user by id.

Most current-user flows should prefer `@CurrentUserId` instead of accepting arbitrary user ids.

## Profiles

- `POST /api/me/profile`: create the authenticated user's profile.
- `GET /api/me/profile`: get authenticated user's profile.
- `PATCH /api/me/profile`: update authenticated user's editable profile fields.
- `POST /api/me/profile/activation`: activate authenticated user's profile.
- `POST /api/me/profile/photos`: add a profile photo.
- `GET /api/me/profile/photos`: list profile photos.
- `DELETE /api/me/profile/photos/{position}`: delete photo at position.
- `PUT /api/me/profile/photos/{position}`: replace photo at position.

## Matchmaking

- `POST /api/matchmaking/queue`: enqueue authenticated user.
- `DELETE /api/matchmaking/queue`: remove authenticated user from queue.
- `GET /api/matchmaking/queue`: check queue status for authenticated user.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present.
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile for visual phase or later.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision.
- `PUT /api/matches/{matchId}/personal-message`: store personal visual-review message.
- `GET /api/matches/{matchId}/partner-message`: get partner message after `VISUAL_APPROVED`.

## Chats

- `GET /api/chats/{chatId}`: fetch chat.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user.
- `GET /api/chats/{chatId}/messages`: list messages.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation; chat remains active.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Exempts reporter and penalizes reported participant.
- `POST /api/chats/{chatId}/closure`: legacy explicit close for active second chats; prefer `cancellations`.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/chat`: fetch visible second chat for connection. If the chat is `AVAILABLE`, this activates it for the authenticated participant and starts its timeout window.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation.
- `POST /api/connections/{connectionId}/proposals`: add scheduling proposal.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept partner proposal and schedule second chat at the accepted time.
- `POST /api/connections/{connectionId}/negotiation/rounds`: reject pending proposals and open the next scheduling round, or fail/close if max rounds are exceeded.

## Dev-Only Endpoints

These endpoints are profile-gated for local/dev manual testing:

- `POST /api/dev/matchmaking/process?batchSize=5`: manually process candidate pairs and start first chats.
- `POST /api/dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.

The scheduled second-chat availability job is available at:

- `POST /api/dev/jobs/scheduled-second-chat-start/run`
- `POST /api/dev/timeouts/connections/{connectionId}/second-chat-start-now`

## Error Shape

`GlobalExceptionHandler` returns:

```json
{
  "error": "Conflict",
  "message": "..."
}
```

Common mappings:

- `NoSuchElementException`: `404 Not Found`
- `IllegalArgumentException`: `400 Bad Request`
- `IllegalStateException`: `409 Conflict`
- generic exception: `500 Internal Server Error`
