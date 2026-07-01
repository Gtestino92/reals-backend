# API

This file summarizes the current controller surface for human readers.
The formal OpenAPI contract lives in `docs/openapi.yaml`.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users

- `POST /api/me/provision`: create or link the authenticated Firebase identity to a local backend user. This is the only Firebase flow endpoint that provisions a missing local user.
- `GET /api/me`: fetch the authenticated user.
- `GET /api/me/home`: fetch the authenticated user's current app state for home/navigation. Includes profile status, matchmaking availability, active interaction counts, pending actions, next steps and passive notices. Home is an explicit navigation contract; clients should not infer actions from raw match or connection states.
- `GET /api/me/home/status`: fetch the authenticated user's persisted Home `version`, `dirty` flag and `serverTime`. This is cheap and does not aggregate full Home state.
- `GET /api/me/home/pending`: fetch lightweight pending/actionable Home navigation state with the current Home `version`. It returns pending actions, next steps and passive notices without partner summaries, matchmaking availability or active interaction counts.
- `PUT /api/me/push-tokens`: register or refresh the authenticated user's Android FCM device token. Body: `{ "token": "...", "platform": "ANDROID" }`. Returns `{ "registered": true }`.
- `DELETE /api/me`: schedule soft deletion for the authenticated user account. The account remains recoverable during `account.deletion.recovery-window-days`.
- `POST /api/me/reactivation`: reactivate an account that is still inside the deletion recovery window.

For first-chat navigation, `GET /api/me/home` exposes a
`pendingActions[]` item with `type = FIRST_CHAT` only while the match remains in
`CHAT_ACTIVE`, the first chat exists, the chat is active, the chat has not
expired and the current user has not decided. Once the current user decides, the
action disappears from Home.

First-chat countdowns in the client are advisory UX. The backend remains the
source of truth: absolute timeout uses `expiresAt`/`timeoutAt`, and inactivity
timeout uses `inactivityExpiresAt`. Mutating endpoints reject stale first-chat
actions with `CHAT_EXPIRED` for absolute timeout or `CHAT_ABANDONED` for
inactivity timeout.

For visual review navigation, Home exposes a `pendingActions[]` item with
`type = VISUAL_REVIEW` only while the match remains in `VISUAL_PHASE`, the
visual review exists, the visual phase has not expired and the current user has
not decided. Expired or already-decided visual reviews are not returned as
actions.

When a visual review first becomes available, the backend also attempts a
privacy-safe external push notification with type `VISUAL_REVIEW_AVAILABLE`.
The push payload includes only `type` and `matchId`; tap behavior remains a
client concern and Home remains the source of actionable state. There is no
internal notification inbox, notification bell or unread count.

When a second chat has a confirmed scheduled time and the connection is still
`SECOND_CHAT_SCHEDULED`, `SecondChatReminderNotificationJob`
attempts privacy-safe external push reminders per participant before
`confirmedDateTime` using the list `notifications.second-chat-reminder.minutes-before`
(default `[10]`). Reminder windows whose target time
`confirmedDateTime - minutesBefore` is already in the past are skipped. The
notification type is `SECOND_CHAT_REMINDER`; the payload includes only `type`,
`connectionId` and `availableAt`. Delivery is deduplicated per user,
notification type, connection id and lead time.

Home returns `matchmaking` for search UX:

- `inQueue`: current user is already in matchmaking queue.
- `canSearch`: current user may attempt to enter matchmaking now. This is
  informative for UX; `POST /api/matchmaking/queue` remains the transactional
  authority.
- `blockedReason`: stable code/message when search is blocked by profile,
  penalty or active engagement limits.

Home also returns `activeInteractionsSummary`:

- `activeInitialCount`: active initial interactions currently visible/actionable in Home.
- `activeConnectionCount`: visible/revealed active connections, excluding `SCHEDULING_PENDING`.
- `pendingSchedulingConnectionCount`: unrevealed pending scheduling coordinations created after mutual visual approval.
- `actionableConnectionCount`: connections returned in `nextSteps[]`.

The lightweight pending endpoint intentionally omits partner summaries. Clients
that need full profile/matchmaking context should call `GET /api/me/home`,
which remains the source of truth for the complete Home contract. Future clients
can poll `GET /api/me/home/status` and call the full Home endpoint only when the
persisted version changes.

Bruno debug requests for this `local-firebase` flow live under
`bruno/reals-backend-happy-path/11 - Home Polling Debug`; they use Firebase
`Authorization: Bearer ...` tokens, not `X-Dev-User-Id`.

`nextSteps[]` includes `SCHEDULING`, `SECOND_CHAT_SCHEDULED`,
`SECOND_CHAT_AVAILABLE` and `SECOND_CHAT_READ_ONLY` items. `SCHEDULING_PENDING`
is not actionable and is surfaced through
`activeInteractionsSummary.pendingSchedulingConnectionCount` plus a
`passiveNotices[]` item with `type = SCHEDULING_PREPARING` until the activation
job moves the connection to `SCHEDULING_PHASE`. It still occupies internal
capacity through connection locks, but it is surfaced only as this passive
preparation notice until scheduling is activated.

`SCHEDULING_PENDING` is advanced by `SchedulingActivationJob`, not by user
actions and not by `SchedulingNegotiationTimeoutJob`. The scheduling timeout is
actionable only after the connection reaches `SCHEDULING_PHASE`; activation
recalculates `schedulingExpiresAt` from that moment.

Second-chat next steps include `secondChat.availableAt` when a confirmed
negotiation exists. This value is the agreed second-chat start time in ISO-8601
format with offset, for example `2026-06-19T21:00:00Z`. Clients may enable
entry at `availableAt`; before that time, render the agreed
  time. `secondChat.expiresAt` is the end of the writable second-chat window, and
  `secondChat.durationMinutes` exposes the configured maximum writable duration so
  clients do not hardcode it. In `SECOND_CHAT_SCHEDULED`, `secondChat.chatId` is
  absent until `GET /api/connections/{connectionId}/chat` materializes the second
  chat row. If that GET is called before `availableAt`, it returns conflict with
  `SECOND_CHAT_NOT_AVAILABLE_YET`. If it is called after `expiresAt` and no chat
  exists, it returns conflict with `SECOND_CHAT_EXPIRED` and does not create a
  chat. Expired scheduled second-chat windows without a chat are omitted from Home
  and cleaned up by `SecondChatLifecycleJob`. After
  expiration, Home may return `SECOND_CHAT_READ_ONLY` with
  `secondChat.chatStatus = EXPIRED` and `secondChat.readOnlyUntil`; clients can
  show prior messages but must not allow sending new messages. Once
`SecondChatLifecycleJob` closes the read-only chat, it disappears from Home.
Users may also hide a finished or otherwise non-actionable second chat from
their own Home with
`POST /api/connections/{connectionId}/second-chat-dismissal`. This dismissal is
persisted per authenticated user and connection. It does not delete messages,
close the chat globally or affect the other participant's Home.

Most current-user flows should prefer `@CurrentUserId` instead of accepting arbitrary user ids.

User-generated text fields are plain text. The backend rejects control
characters and markup delimiters `<` / `>` in profile text, chat messages,
visual personal messages and cancellation/report details. Frontends must render
these values as text, not HTML.

## Profiles

- `POST /api/me/profile`: create the authenticated user's profile.
- `GET /api/me/profile`: get authenticated user's profile.
- `PATCH /api/me/profile`: update authenticated user's editable profile fields.
- `POST /api/me/profile/activation`: activate authenticated user's profile. Requires the current Firebase ID token to have `emailVerified=true`; otherwise returns `409 EMAIL_NOT_VERIFIED` with message `Verificá tu email antes de activar el perfil.` Email verification is not required for profile creation, editing, photo upload/replacement/deletion or match-filter configuration.
- `PUT /api/me/profile/match-filters`: replace dynamic matchmaking filters. Body: `preferredMinAge`, `preferredMaxAge`, `maxDistanceKm`.
- `POST /api/me/profile/identity-verification`: optionally run identity verification for the authenticated user's profile. Current provider `none` marks the profile `VERIFIED` for MVP/local compatibility, but does not represent real external identity or age verification.
- `POST /api/me/profile/photos`: add a profile photo using multipart file upload with `file` and `position`.
- `GET /api/me/profile/photos`: list profile photos.
- `DELETE /api/me/profile/photos/{photoId}`: delete photo by id.
- `PUT /api/me/profile/photos/{photoId}/file`: replace an existing photo file by id.

Photo response `url` values are renderable read URLs generated by the backend
from each photo's stored object key. Storage keys and bucket names are never
returned to clients. For private S3/R2/MinIO storage these URLs may be presigned
and time-limited, so clients should use them for display and refetch photo
responses when needed instead of persisting URLs permanently.

Photo upload validation has two separate fields. `validationStatus` is the
blocking technical upload result for file type, size, decoding and dimensions.
`moderationStatus` is the content-moderation result. The current default
provider is `none`, which permissively returns `APPROVED` and does not represent
an external safety, person or full-body review. Production can later require
`moderationStatus = APPROVED` for activation with
`PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION=true`.

## Matchmaking

- `POST /api/matchmaking/queue`: enqueue authenticated user. Body requires current search location: `latitude`, `longitude`, optional `accuracyMeters`. This operation is idempotent: if the user is already queued, it keeps a single queue entry and refreshes `latitude`, `longitude` and `accuracyMeters`.
- `DELETE /api/matchmaking/queue`: remove authenticated user from queue.
- `GET /api/matchmaking/queue`: check queue status for authenticated user.

After enqueueing, clients should poll `GET /api/me/home` to discover whether the
queue entry has become an active match. Do not infer match/chat ids locally.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present. Includes `visualExpiresAt` when a visual review deadline exists for the match.
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match. Includes `partner`, `myDecision`, `partnerDecision`, `expiresAt` and `inactivityExpiresAt`.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile for visual phase or later. Includes partner photos with freshly generated read URLs, `visualExpiresAt` and `myPersonalMessageSubmitted`, which tells whether the authenticated user has already sent their visual personal message.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision. `APPROVED` is individual and requires both users to move the match to `VISUAL_PHASE`. `REJECTED` is unilateral cancellation: it closes the first chat, moves the match to `CHAT_REJECTED`, releases locks and applies cancellation penalty policy. If the first-chat deadline already passed, the backend rejects with `CHAT_EXPIRED`; if the inactivity deadline already passed, it rejects with `CHAT_ABANDONED`.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision. The current user's visual review disappears after deciding and that user's match lock is released. A repeated identical decision is idempotent; a contradictory decision is rejected. A rejection is not immediately surfaced to the other participant through Home while their own visual decision is still pending. New decisions after the visual deadline are rejected with `VISUAL_REVIEW_EXPIRED`.
- `PUT /api/matches/{matchId}/personal-messages/me`: store the authenticated user's personal visual-review message. Personal messages are write-once; a second submission returns `409 Conflict` and does not overwrite the first message.
- `GET /api/matches/{matchId}/personal-messages/partner`: get the partner's personal message from `VISUAL_PHASE` onwards. If present, it must be read before visual approval.

## Chats

- `GET /api/chats/{chatId}`: fetch chat. First-chat responses include `inactivityExpiresAt`; second-chat responses return `inactivityExpiresAt = null`.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user. First-chat sends after absolute timeout are rejected with `CHAT_EXPIRED`; sends after inactivity timeout are rejected with `CHAT_ABANDONED`.
- `GET /api/chats/{chatId}/messages`: list messages as an authenticated chat participant. With no cursor, returns the legacy array. With `after={messageId}` or `afterMessageId={messageId}`, returns `{ "messages": [...], "hasMore": false, "serverTime": "..." }`.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation. Returns `201 Created` when a new pending request is created. If the same requester repeats the call while their pending mutual request still exists, returns `200 OK` with the existing request and does not overwrite `reason` or `details`. If the partner already has a pending mutual request for the chat, returns `409 Conflict`.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close the chat without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation and close the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/timeout`: resolve an unanswered mutual cancellation after the configured timeout and close the chat. If the requester calls this because the responder did not answer in time, the requester must not be penalized; future scoring is pending.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Requires non-blank `details`, closes the chat, creates an internal `SafetyReport` in `PENDING` status, creates a directional user block from reporter to reported and returns `penaltyApplied=false`. Matchmaking treats that block as a bidirectional exclusion. Reporting does not automatically penalize the reported participant; penalties are applied only after admin/backoffice review.

## Safety Reports

- `POST /api/safety/reports`: create a user safety report without necessarily closing an active chat. Supported contexts are `CHAT`, `VISUAL_PROFILE`, `PERSONAL_MESSAGE` and `PROFILE_PHOTO`. The backend validates that the authenticated reporter and reported user are the two participants in the referenced chat or visual-phase match; `PROFILE_PHOTO` also requires the reported photo to belong to the matched partner. Duplicate reports for the same reporter, reported user, context type and context id return `200 OK` with the existing report; new reports return `201 Created`. Every report creates or reuses a directional `UserBlock` from reporter to reported, and matchmaking treats any block between two users as a bidirectional exclusion.
- User-facing report creation uses the safety-report-specific rate-limit rule under `security.rate-limit.safety-report-*`.

## Admin Safety Reports

All endpoints under `/api/admin/**` require `ROLE_ADMIN`. Firebase-authenticated users receive this role only when they exist locally as active users and their email is listed in `backoffice.admin-emails`.

- `GET /api/admin/safety-reports?status=PENDING&source=USER&reportedUserId=...&reporterUserId=...`: list safety reports. `status` defaults to `PENDING`; other filters are optional. Summary DTOs omit report details, verdict notes, raw email and Firebase UID.
- `POST /api/admin/safety-reports`: create an admin safety report. `contextType=USER` creates a general report about the reported user with `contextId = reportedUserId` and no match/chat context. Contextual admin reports can reference chat, visual profile, personal message or profile photo contexts. Admin-created reports do not auto-block, auto-close chats or auto-apply penalties.
- `GET /api/admin/safety-reports/{reportId}`: fetch report detail, reduced reporter/reported user summaries, evidence snapshot, chat messages for review and associated penalty if one exists.
- `POST /api/admin/safety-reports/{reportId}/dismissal`: dismiss a pending report. Body: `{ "notes": "optional notes" }`. Does not create a penalty.
- `POST /api/admin/safety-reports/{reportId}/penalty`: confirm a pending report and apply a penalty to the reported user. Temporary body: `{ "type": "TEMPORARY_BAN", "durationHours": 24, "reason": "Harassment confirmed", "notes": "optional notes" }`. Permanent body: `{ "type": "PERMANENT_BAN", "reason": "Severe safety violation", "notes": "optional notes" }`.

Temporary penalties require positive `durationHours`; permanent penalties reject `durationHours` and have `expiresAt = null`. Active penalties block matchmaking and remove the user from the queue if already queued. The penalty expiration job deactivates only expired temporary penalties.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/chat`: fetch the second chat for a connection. If no chat exists and the confirmed second-chat window is open (`now >= availableAt && now < expiresAt`), this idempotently creates and activates the `SECOND_CHAT`. If called before `availableAt`, returns conflict with `SECOND_CHAT_NOT_AVAILABLE_YET`; if called after `expiresAt` with no chat, returns conflict with `SECOND_CHAT_EXPIRED`.
- `POST /api/connections/{connectionId}/second-chat-dismissal`: hide a finished or non-actionable second-chat next step from the authenticated user's Home. The action is idempotent and returns `{ "dismissed": true }`. It is allowed for read-only/expired/closed second chats and for second-chat windows that already expired without an actionable chat. It returns conflict while the second chat is still actionable.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation. Includes `schedulingExpiresAt` from the parent connection so clients can show a countdown without an extra request.
- `POST /api/connections/{connectionId}/proposals`: submit the authenticated user's ordered scheduling proposal list for the current round. Body: `{ "proposedDateTimes": ["..."] }`, 1 to `scheduling.max-proposals-per-round` future half-hour slots.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept partner proposal and schedule second chat at the accepted time.
- `POST /api/connections/{connectionId}/negotiation/rejections`: user explicitly rejects the current scheduling round after reviewing partner proposals. This opens the next round, or fails/closes if max rounds are exceeded.

Scheduling mutations after `schedulingExpiresAt` are rejected with
`SCHEDULING_EXPIRED`. The timeout job owns the persistent transition to
`FAILED`/closed when it runs.

After mutual visual approval the backend creates a connection in
`SCHEDULING_PENDING`. This connection already counts against each participant's
active connection limit, but scheduling endpoints are not actionable until
`SchedulingActivationJob` moves it to `SCHEDULING_PHASE` and initializes the
negotiation. At that point the backend sends one privacy-safe
`SCHEDULING_AVAILABLE` push per participant and connection. Notification taps
should refresh/open Home for MVP; the payload contains only `type`,
`connectionId` and `matchId`. In local profiles, if Home shows only
`SCHEDULING_PREPARING`/`pendingSchedulingConnectionCount`, run the local
scheduling activation job before testing scheduling proposal or timeout flows.

## Local Dev Tooling Endpoints

These endpoints are profile-gated for local manual testing:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`: manually process queued candidate pairs and start first chats.
- `POST /api/local-dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/local-dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.

These local-dev endpoints execute system tooling and do not require a user
bearer token. They are not available in `dev` or `prod`.

Supported local job triggers:

- `POST /api/local-dev/jobs/scheduling-activation/run`
- `POST /api/local-dev/jobs/second-chat-reminder/run`
- `POST /api/local-dev/jobs/second-chat-lifecycle/run`
- `POST /api/local-dev/jobs/chat-timeout/run`
- `POST /api/local-dev/jobs/visual-phase-expiration/run`
- `POST /api/local-dev/jobs/match-expiration/run`
- `POST /api/local-dev/jobs/scheduling-timeout/run`
- `POST /api/local-dev/jobs/inactivity-check/run`
- `POST /api/local-dev/jobs/penalty-expiration/run`
- `POST /api/local-dev/jobs/account-deletion-finalization/run`

The second-chat confirmed time can be moved into the past for local testing with:

- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now`

Second-chat read-only lifecycle can be tested locally with:

- `POST /api/local-dev/jobs/second-chat-lifecycle/run`
- `POST /api/local-dev/timeouts/chats/{chatId}/expire-now` to end the writable window.
- `POST /api/local-dev/timeouts/chats/{chatId}/read-only-expire-now` to end read-only retention.

Second-chat reminder push notifications can be tested locally with:

- `POST /api/local-dev/jobs/second-chat-reminder/run`

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
- `EMAIL_NOT_VERIFIED`: profile activation requires a verified email in the current Firebase ID token.
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
- `INVALID_PROFILE_PHOTO`: uploaded profile photo file is invalid.
- `PROFILE_PHOTO_NOT_FOUND`: requested profile photo does not belong to the current profile.
- `USER_NOT_FOUND`: authenticated user id could not be locked for a state-changing operation.
- `CHAT_EXPIRED`: chat action was attempted after the absolute chat deadline.
- `CHAT_ABANDONED`: first-chat action was attempted after the inactivity deadline.
- `SCHEDULING_EXPIRED`: scheduling action was attempted after the negotiation deadline.
- `VISUAL_REVIEW_EXPIRED`: visual-review action was attempted after the visual deadline.
