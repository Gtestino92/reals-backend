# API

This file summarizes the current controller surface. It is not a full OpenAPI contract.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users

- `POST /api/users`: create a user from request body email.
- `GET /api/users/{userId}`: fetch a user by id.

Most current-user flows should prefer `@CurrentUserId` instead of accepting arbitrary user ids.

## Profiles

- `POST /api/profiles`: create the authenticated user's profile.
- `GET /api/profiles/me`: get authenticated user's profile.
- `PATCH /api/profiles/me`: update authenticated user's editable profile fields.
- `POST /api/profiles/me/activate`: activate authenticated user's profile.
- `POST /api/profiles/{profileId}/photos`: add a profile photo.
- `GET /api/profiles/{profileId}/photos`: list profile photos.
- `POST /api/profiles/{profileId}/activate`: activate a profile by id.
- `PATCH /api/profiles/{profileId}`: update editable fields by profile id.
- `DELETE /api/profiles/{profileId}/{position}`: delete photo at position.
- `PUT /api/profiles/{profileId}/photos/{position}`: replace photo at position.

## Matchmaking

- `POST /api/matchmaking/enqueue`: enqueue authenticated user.
- `DELETE /api/matchmaking/dequeue`: remove authenticated user from queue.
- `GET /api/matchmaking/status`: check queue status for authenticated user.
- `POST /api/matchmaking/process?batchSize=5`: manually process candidate pairs and start first chats. This is currently a testing/manual worker endpoint.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present.
- `GET /api/matches/{matchId}/visual/profile`: fetch partner profile for visual phase or later.
- `POST /api/matches/{matchId}/chat/decision`: submit first-chat continuation decision.
- `POST /api/matches/{matchId}/visual/decision`: submit visual decision.
- `POST /api/matches/{matchId}/visual/message`: store personal visual-review message.
- `GET /api/matches/{matchId}/visual/message`: get partner message after `VISUAL_APPROVED`.

## Chat

- `GET /api/chat/{chatId}`: fetch chat.
- `GET /api/chat/by-match/{matchId}`: fetch active first chat for match.
- `GET /api/chat/by-connection/{connectionId}`: fetch active second chat for connection.
- `POST /api/chat/{chatId}/messages`: send message as authenticated user.
- `GET /api/chat/{chatId}/messages`: list messages.
- `POST /api/chat/{chatId}/close`: close an active second chat as authenticated user.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation.
- `POST /api/connections/{connectionId}/proposals`: add scheduling proposal.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/accept`: accept partner proposal and start second chat.
- `POST /api/connections/{connectionId}/negotiation/next-round`: reject pending proposals and open the next scheduling round, or fail/close if max rounds are exceeded.

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
