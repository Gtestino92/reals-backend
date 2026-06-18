# API

This file summarizes the current controller surface for human readers.
The formal OpenAPI contract lives in `docs/openapi.yaml`.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users

- `POST /api/me/provision`: create or link the authenticated Firebase identity to a local backend user. This is the only Firebase flow endpoint that provisions a missing local user.
- `GET /api/me`: fetch the authenticated user.
- `GET /api/me/home`: fetch the authenticated user's current app state for home/navigation. Includes profile status, matchmaking availability, active interaction counts, pending actions, next steps and passive notices. Home is an explicit navigation contract; clients should not infer actions from raw match or connection states.
- `DELETE /api/me`: schedule soft deletion for the authenticated user account. The account remains recoverable during `account.deletion.recovery-window-days`.
- `POST /api/me/reactivation`: reactivate an account that is still inside the deletion recovery window.

For first-chat navigation, `GET /api/me/home` exposes a
`pendingActions[]` item with `type = FIRST_CHAT` only while the match remains in
`CHAT_ACTIVE`, the first chat exists, the chat is active, the chat has not
expired and the current user has not decided. Once the current user decides, the
action disappears from Home.

For visual review navigation, Home exposes a `pendingActions[]` item with
`type = VISUAL_REVIEW` only while the match remains in `VISUAL_PHASE`, the
visual review exists, the visual phase has not expired and the current user has
not decided. Expired or already-decided visual reviews are not returned as
actions.

Home returns `matchmaking` for search UX:

- `inQueue`: current user is already in matchmaking queue.
- `canSearch`: current user may attempt to enter matchmaking now. This is
  informative for UX; `POST /api/matchmaking/queue` remains the transactional
  authority.
- `blockedReason`: stable code/message when search is blocked by profile,
  penalty or active engagement limits.

Home also returns `activeInteractionsSummary`:

- `activeInitialCount`: active initial interactions currently visible/actionable in Home.
- `activeConnectionCount`: active connections that occupy connection capacity, including `SCHEDULING_PENDING`.
- `pendingSchedulingConnectionCount`: connections created after mutual visual approval whose scheduling phase is not actionable yet.
- `actionableConnectionCount`: connections returned in `nextSteps[]`.

`nextSteps[]` includes `SCHEDULING`, `SECOND_CHAT_SCHEDULED` and
`SECOND_CHAT_AVAILABLE` items. `SCHEDULING_PENDING` is not actionable and is
surfaced through `activeInteractionsSummary.pendingSchedulingConnectionCount`
plus a `passiveNotices[]` item with `type = SCHEDULING_PREPARING` until the
activation job moves the connection to `SCHEDULING_PHASE`.

Most current-user flows should prefer `@CurrentUserId` instead of accepting arbitrary user ids.

User-generated text fields are plain text. The backend rejects control
characters and markup delimiters `<` / `>` in profile text, chat messages,
visual personal messages and cancellation/report details. Frontends must render
these values as text, not HTML.

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

Photo response `url` values are renderable read URLs. For private S3/R2/MinIO
storage they may be presigned and time-limited, so clients should use them for
display and refetch them when needed instead of persisting them permanently.

## Matchmaking

- `POST /api/matchmaking/queue`: enqueue authenticated user. Body requires current search location: `latitude`, `longitude`, optional `accuracyMeters`. This operation is idempotent: if the user is already queued, it keeps a single queue entry and refreshes `latitude`, `longitude` and `accuracyMeters`.
- `DELETE /api/matchmaking/queue`: remove authenticated user from queue.
- `GET /api/matchmaking/queue`: check queue status for authenticated user.

After enqueueing, clients should poll `GET /api/me/home` to discover whether the
queue entry has become an active match. Do not infer match/chat ids locally.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present.
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match. Includes `partner`, `myDecision`, `partnerDecision` and `expiresAt`.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile for visual phase or later. Includes `myPersonalMessageSubmitted`, which tells whether the authenticated user has already sent their visual personal message.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision. `APPROVED` is individual and requires both users to move the match to `VISUAL_PHASE`. `REJECTED` is unilateral cancellation: it closes the first chat, moves the match to `CHAT_REJECTED`, releases locks and applies cancellation penalty policy.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision. The current user's visual review disappears after deciding and that user's match lock is released. A repeated identical decision is idempotent; a contradictory decision is rejected. A rejection is not immediately surfaced to the other participant through Home while their own visual decision is still pending.
- `PUT /api/matches/{matchId}/personal-messages/me`: store the authenticated user's personal visual-review message. Personal messages are write-once; a second submission returns `409 Conflict` and does not overwrite the first message.
- `GET /api/matches/{matchId}/personal-messages/partner`: get the partner's personal message from `VISUAL_PHASE` onwards. If present, it must be read before visual approval.

## Chats

- `GET /api/chats/{chatId}`: fetch chat.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user.
- `GET /api/chats/{chatId}/messages`: list messages as an authenticated chat participant. With no cursor, returns the legacy array. With `after={messageId}` or `afterMessageId={messageId}`, returns `{ "messages": [...], "hasMore": false, "serverTime": "..." }`.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation. Returns `201 Created` when a new pending request is created. If the same requester repeats the call while their pending mutual request still exists, returns `200 OK` with the existing request and does not overwrite `reason` or `details`. If the partner already has a pending mutual request for the chat, returns `409 Conflict`.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close the chat without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation and close the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/timeout`: resolve an unanswered mutual cancellation after the configured timeout and close the chat. If the requester calls this because the responder did not answer in time, the requester must not be penalized; future scoring is pending.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Exempts reporter, penalizes reported participant and closes the chat.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/chat`: fetch visible second chat for connection. If the chat is `AVAILABLE`, this activates it for the authenticated participant and starts its timeout window.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation.
- `POST /api/connections/{connectionId}/proposals`: submit the authenticated user's ordered scheduling proposal list for the current round. Body: `{ "proposedDateTimes": ["..."] }`, 1 to `scheduling.max-proposals-per-round` future half-hour slots.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept partner proposal and schedule second chat at the accepted time.
- `POST /api/connections/{connectionId}/negotiation/rejections`: user explicitly rejects the current scheduling round after reviewing partner proposals. This opens the next round, or fails/closes if max rounds are exceeded.

After mutual visual approval the backend creates a connection in
`SCHEDULING_PENDING`. This connection already counts against each participant's
active connection limit, but scheduling endpoints are not actionable until
`SchedulingActivationJob` moves it to `SCHEDULING_PHASE` and initializes the
negotiation.

## Local Dev Tooling Endpoints

These endpoints are profile-gated for local manual testing:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`: manually process queued candidate pairs and start first chats.
- `POST /api/local-dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/local-dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.

The scheduled second-chat availability job is available at:

- `POST /api/local-dev/jobs/scheduled-second-chat-start/run`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-start-now`

Deferred scheduling activation can be tested locally with:

- `POST /api/local-dev/jobs/scheduling-activation/run`
- `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-available-now`

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
- Bean validation failures: `400 Bad Request` with `VALIDATION_ERROR`
- `DomainNotFoundException`: `404 Not Found` with stable domain code
- generic exception: `500 Internal Server Error`

Selected stable frontend-facing domain codes:

- `PROFILE_REQUIRED`: user must create a profile before matchmaking.
- `PROFILE_NOT_ACTIVE`: profile must be activated before matchmaking.
- `ACTIVE_PENALTY`: user cannot enter matchmaking while an active penalty exists.
- `ACTIVE_MATCH_LIMIT_REACHED`: user has reached the active match limit.
- `ACTIVE_CONNECTION_LIMIT_REACHED`: user has reached the active connection limit.
- `INVALID_SEARCH_LOCATION`: provided matchmaking search location is invalid.
- `PROFILE_ALREADY_EXISTS`: user attempted to create a second profile.
- `PROFILE_NOT_FOUND`: authenticated user or match partner profile was not found.
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
