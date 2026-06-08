# API

This file summarizes the current controller surface for human readers.
The formal OpenAPI contract lives in `docs/openapi.yaml`.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users

- `POST /api/me/provision`: create or link the authenticated Firebase identity to a local backend user. This is the only Firebase flow endpoint that provisions a missing local user.
- `GET /api/me`: fetch the authenticated user.
- `DELETE /api/me`: schedule soft deletion for the authenticated user account. The account remains recoverable during `account.deletion.recovery-window-days`.
- `POST /api/me/reactivation`: reactivate an account that is still inside the deletion recovery window.

Most current-user flows should prefer `@CurrentUserId` instead of accepting arbitrary user ids.

## Profiles

- `POST /api/me/profile`: create the authenticated user's profile.
- `GET /api/me/profile`: get authenticated user's profile.
- `PATCH /api/me/profile`: update authenticated user's editable profile fields.
- `POST /api/me/profile/activation`: activate authenticated user's profile.
- `PUT /api/me/profile/match-filters`: replace dynamic matchmaking filters. Body: `preferredMinAge`, `preferredMaxAge`, `maxDistanceKm`.
- `POST /api/me/profile/identity-verification`: optionally run identity verification for the authenticated user's profile. Current provider `none` keeps `identityVerified=false`.
- `POST /api/me/profile/photos`: add a profile photo. Supports legacy JSON URL bodies and multipart file upload with `file` and `position`.
- `GET /api/me/profile/photos`: list profile photos.
- `DELETE /api/me/profile/photos/{photoId}`: delete photo by id.
- `PUT /api/me/profile/photos/position/{position}`: replace a photo URL by position. Legacy JSON URL flow.
- `PUT /api/me/profile/photos/{photoId}/file`: replace an existing photo file by id.

## Matchmaking

- `POST /api/matchmaking/queue`: enqueue authenticated user. Body requires current search location: `latitude`, `longitude`, optional `accuracyMeters`.
- `DELETE /api/matchmaking/queue`: remove authenticated user from queue.
- `GET /api/matchmaking/queue`: check queue status for authenticated user.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present.
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile for visual phase or later.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision.
- `PUT /api/matches/{matchId}/personal-messages/me`: store the authenticated user's personal visual-review message.
- `GET /api/matches/{matchId}/personal-messages/partner`: get the partner's personal message from `VISUAL_PHASE` onwards. If present, it must be read before visual approval.

## Chats

- `GET /api/chats/{chatId}`: fetch chat.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user.
- `GET /api/chats/{chatId}/messages`: list messages as an authenticated chat participant.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation; chat remains active.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Exempts reporter and penalizes reported participant.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/chat`: fetch visible second chat for connection. If the chat is `AVAILABLE`, this activates it for the authenticated participant and starts its timeout window.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation.
- `POST /api/connections/{connectionId}/proposals`: submit the authenticated user's ordered scheduling proposal list for the current round. Body: `{ "proposedDateTimes": ["..."] }`, 1 to `scheduling.max-proposals-per-round` future half-hour slots.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept partner proposal and schedule second chat at the accepted time.
- `POST /api/connections/{connectionId}/negotiation/rejections`: user explicitly rejects the current scheduling round after reviewing partner proposals. This opens the next round, or fails/closes if max rounds are exceeded.

## Local Dev Tooling Endpoints

These endpoints are profile-gated for local manual testing:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`: manually process queued candidate pairs and start first chats.
- `POST /api/local-dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/local-dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.

The scheduled second-chat availability job is available at:

- `POST /api/local-dev/jobs/scheduled-second-chat-start/run`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-start-now`

## Error Shape

`GlobalExceptionHandler` returns:

```json
{
  "code": "DOMAIN_CONFLICT",
  "error": "Conflict",
  "message": "..."
}
```

Common mappings:

- `NoSuchElementException`: `404 Not Found`
- `IllegalArgumentException`: `400 Bad Request`
- `IllegalStateException`: `409 Conflict`
- generic exception: `500 Internal Server Error`

Selected stable frontend-facing domain codes:

- `PROFILE_REQUIRED`: user must create a profile before matchmaking.
- `PROFILE_NOT_ACTIVE`: profile must be activated before matchmaking.
- `ACTIVE_PENALTY`: user cannot enter matchmaking while an active penalty exists.
- `ACTIVE_MATCH_LIMIT_REACHED`: user has reached the active match limit.
- `INVALID_SEARCH_LOCATION`: provided matchmaking search location is invalid.
- `PROFILE_ALREADY_EXISTS`: user attempted to create a second profile.
- `PROFILE_NOT_ACTIVATABLE`: profile cannot be activated from its current status.
- `PROFILE_PHOTOS_REQUIRED`: activation requires more profile photos.
- `PROFILE_PERSON_PHOTO_REQUIRED`: activation requires more person photos.
- `PROFILE_FULL_BODY_PHOTO_REQUIRED`: activation requires a full-body photo.
- `PROFILE_PHOTO_LIMIT_REACHED`: profile already has the maximum number of photos.
- `ACCOUNT_PENDING_DELETION`: account/email is still inside the deletion recovery window.
- `ACCOUNT_DELETION_FINALIZED`: account deletion can no longer be recovered.
- `INVALID_PROFILE_BIRTH_DATE`: birth date is invalid for profile creation.
- `INVALID_MATCH_FILTERS`: dynamic match filters are internally inconsistent or out of range.
- `PHOTO_POSITION_INVALID`: requested photo position is outside the configured range.
- `PHOTO_POSITION_OCCUPIED`: requested photo position is already used.
- `PHOTO_URL_INVALID`: profile photo URL is not a valid HTTPS URL.
- `INVALID_PROFILE_PHOTO`: uploaded profile photo file is invalid.
- `PROFILE_PHOTO_NOT_FOUND`: requested profile photo does not belong to the current profile.
- `USER_NOT_FOUND`: authenticated user id could not be locked for a state-changing operation.
