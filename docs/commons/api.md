# API

This file summarizes the current controller surface for human readers.
The formal OpenAPI contract lives in `openapi.yaml`.

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
- `GET /api/legal/documents/current`: public endpoint that returns the current configured legal document catalog. It may return an empty `documents` array.
- `GET /api/me/legal-status`: authenticated authoritative status for current configured legal document versions only.
- `POST /api/me/legal-document-actions`: authenticated factual record that the current user performed `ACCEPTED` or `ACKNOWLEDGED` for a configured legal document type/version. Returns `201 Created` for a new row and `200 OK` for an identical replay.

Legal document support records factual user actions:
`User X performed action Y for legal document type Z, version V, content
SHA-256 H, at time T`.
The source of truth is `user_legal_document_actions`. `AuditEvent` is secondary
operational evidence and stores only document type, version, content SHA-256 and
action metadata.
The compliance source of truth is the current `legal.documents` catalog plus
`user_legal_document_actions`.

Clients do not submit or choose legal content hashes. `POST
/api/me/legal-document-actions` keeps the request body as document type,
document version and action only. When recording a new action, the backend
resolves the current configured legal document and copies its canonical
`content-sha256` server-side. Current user-facing legal responses do not expose
the persisted content hash.

Protected participation/content writes are backend-gated by current legal
status and may return `409 LEGAL_ACTION_REQUIRED`. The generic error does not
list missing documents; clients should call `GET /api/me/legal-status` for the
authoritative detailed status and `GET /api/legal/documents/current` for URL
metadata. An empty legal catalog means requirements are satisfied. Historical
actions stay persisted but do not satisfy a newer configured version. Legacy
actions without a stored content SHA-256 and historical actions with a different
content SHA-256 also do not satisfy the current configured document.

The gate applies to profile creation/editing/activation/match filters/profile
authenticity verification/photo upload/photo reorder/photo replacement, entering
matchmaking, sending chat messages, first-chat guidance next requests, visual
personal-message writes, positive first-chat and visual decisions, and
scheduling proposal submission/acceptance/partner proposal rejection.

Reads remain available. Account deletion/reactivation, legal endpoints, chat
exit/cancellation/safety operations, safety reports, queue inspection/leaving,
push-token registration, admin endpoints, actuator endpoints and local-dev
tooling are not gated. `REJECTED` first-chat and visual decisions remain
available; only `APPROVED` requires current legal compliance.

Reference-data reads such as `GET /api/reference/countries` are authenticated
under the normal `/api/** -> ROLE_USER` rule, but are not legal-compliance
gated and do not require an existing profile.

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
actions. For a currently actionable visual review, both `GET /api/me/home` and
`GET /api/me/home/pending` include `visualStartedAt`, the authoritative start of
that visual-review phase, and `visualExpiresAt`, the authoritative expiration of
that phase, on the pending action. Both fields are `null` for pending actions
that are not `VISUAL_REVIEW`.

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

- `activeInitialCount`: visible pending initial-stage actions for the current
  user. It does not count an interaction while the current user is only waiting
  for the other person's hidden decision.
- `activeConnectionCount`: visible connection next steps returned in
  `nextSteps[]`. It is currently the same projection as
  `actionableConnectionCount` and is preserved for client compatibility.
- `hasPendingSchedulingConnection`: boolean that indicates at least one
  non-dismissed, non-blocked connection is internally waiting for scheduling
  preparation. It intentionally avoids exposing an exact internal count.
- `actionableConnectionCount`: visible connection next steps returned in
  `nextSteps[]`. It is currently the same projection as
  `activeConnectionCount`.

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
`activeInteractionsSummary.hasPendingSchedulingConnection` plus one generic
`passiveNotices[]` item with `type = SCHEDULING_PREPARING` until the activation
job moves the connection to `SCHEDULING_PHASE`. The passive notice has no
`count` field and does not reveal whether one or multiple connections are being
prepared. Pending scheduling still occupies internal capacity through
connection locks, but Home surfaces only this generic preparation state until
scheduling is activated.

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
- `PUT /api/me/profile/match-filters`: replace matchmaking preferences. Body: `intention`, `lookingForGenders`, `preferredMinAge`, `preferredMaxAge`, `maxDistanceKm`.
- `POST /api/me/profile/authenticity-verification`: optionally run profile authenticity verification for the authenticated user's profile. Profile Authenticity Verification is not legal identity verification. With provider `none` outside `prod`, the MVP compatibility path may mark the profile `VERIFIED`; this does not represent liveness, face comparison, legal identity, document verification or age assurance. With provider `none` in `prod`, verification is unavailable and returns `409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED`; no `VERIFIED` state is persisted.
- `POST /api/me/profile/photos`: add a profile photo using multipart file upload with `file` and `position`.
- `GET /api/me/profile/photos`: list profile photos.
- `PUT /api/me/profile/photos/reorder`: reorder authenticated user's existing profile photos. The JSON body must include every current photo exactly once with final positions from 1 to 9; holes are allowed. This only changes `position`, does not reupload files, does not re-run validation or moderation, and does not move an active profile back to draft.
- `DELETE /api/me/profile/photos/{photoId}`: delete photo by id.
- `PUT /api/me/profile/photos/{photoId}/file`: replace an existing photo file by id.

Profile location fields are:

- `city`: free text.
- `countryCode`: canonical uppercase ISO 3166-1 alpha-2 country code, for example `AR`, `UY`, `BR`, `CL`, `ES` or `US`.

Profile create/update requests accept alpha-2 country codes case-insensitively
and trim surrounding whitespace before persistence. Display names such as
`Argentina`, alpha-3 codes such as `ARG`, unknown codes such as `ZZ`, malformed
codes and blank values are rejected. Invalid profile country values return
`400 INVALID_PROFILE_COUNTRY`.

Clients should fetch allowed country values from:

- `GET /api/reference/countries`

The response is a complete authenticated reference list:

```json
[
  {
    "code": "AR",
    "displayName": "Argentina"
  }
]
```

`code` is the value to submit as `countryCode`; `displayName` is Spanish UI
text. The backend builds this country catalog once from the Java runtime ISO
country list, uses `Locale.forLanguageTag("es")` for display names, sorts by
Spanish display name with country code as tie-breaker, and keeps immutable
in-memory list/set state. It does not call an external country API, GeoNames,
Redis or Caffeine for this data.

Photo response `url` values are renderable read URLs generated by the backend
from each photo's stored object key. Storage keys and bucket names are never
returned to clients. For private S3/R2/MinIO storage these URLs may be presigned
and time-limited, so clients should use them for display and refetch photo
responses when needed instead of persisting URLs permanently.

Photo upload validation has two separate fields. `validationStatus` is the
blocking technical upload result for file type, size, decoding and dimensions.
Successful technical image validation is not semantic person/full-body
validation. Outside `prod`, the temporary MVP shortcut still returns
`isPersonPhoto=true`, `isFullBody=true` and `validationStatus=VALIDATED`. In
`prod`, technical validation alone returns `isPersonPhoto=false`,
`isFullBody=false` and `validationStatus=PENDING` when provider `none` is used.
With `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` in `prod`, the backend
makes one Sightengine multipart request after technical validation and uses real
face presence only as an MVP person-photo signal. In non-production execution
profiles, Sightengine is disabled even if the variable is set and the backend
uses the provider `none` compatibility path. At least one `faces` entry sets
`isPersonPhoto=true`; zero real faces sets `isPersonPhoto=false`.
`artificial_faces` do not count. Successful Sightengine analysis always persists
`validationStatus=VALIDATED` and `isFullBody=false`. This is not facial
recognition, profile authenticity verification, legal identity verification,
face matching, liveness, age estimation, minor detection or full-body detection.

Profile authenticity verification is a separate profile trust state. The future
target is a liveness-derived live reference plus provider-neutral facial
comparison signals for current candidate person photos
(`validationStatus=VALIDATED && isPersonPhoto=true`). `isPersonPhoto` selects
comparison candidates; it does not prove that the detected person is the
verified user. Reals policy uses configurable thresholds: by default, an
accepted live reference, at least 3 `MATCHED` candidate person photos and at
most 0 `CONTRADICTORY` candidate person photos are required for automatic
`VERIFIED`. `MATCHED` is positive evidence, `UNRESOLVED` is neutral and
`CONTRADICTORY` is comparable facial evidence inconsistent with the accepted
live reference. Group photos can be `MATCHED` when at least one comparable face
matches the live reference, and non-person photos are excluded from face
comparison. Old, distant, side-profile, obscured or otherwise poor comparisons
may be `UNRESOLVED` and do not automatically invalidate the profile. Strong
contradictory evidence prevents automatic verification under the default
zero-contradiction policy, but it does not prove fraud and currently produces
`NEEDS_REVIEW`, not automatic `REJECTED`. Uploading, replacing or deleting a
profile photo invalidates a previous authenticity result to `STALE` and sets
`authenticityVerified=false`; reordering photos does not. The Sightengine path
currently sets `isPersonPhoto` from detected real-face presence only, so this
skeleton does not solve body-only person consistency without a comparable
visible face.

`moderationStatus` is the content-moderation result. With provider `none`
outside `prod`, the MVP compatibility path returns `APPROVED` without external
review. With provider `none` in `prod`, uploads may proceed but persist
`NEEDS_REVIEW`. With provider `sightengine` in `prod`, the same single provider
response also feeds Reals moderation policy for sexual explicit, sexual
suggestive, violence/threat, gore and hate/extremism signals. Reject thresholds
produce `REJECTED`, review thresholds produce `NEEDS_REVIEW`, and otherwise
moderation is `APPROVED`. `NEEDS_REVIEW` enters the existing admin review queue.
Automatic provider moderation does not create safety reports, child-safety
reports, blocks, penalties, bans or account deletions. Production defaults to requiring
`moderationStatus=APPROVED` for activation through
`PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION=true`.

`PhotoValidationStatus.PENDING` and `PhotoModerationStatus.NEEDS_REVIEW` are
separate states. Validation `PENDING` means semantic person/full-body analysis
has not produced a result. Moderation `NEEDS_REVIEW` means content moderation
requires a human admin decision.

## Matchmaking

- `POST /api/matchmaking/queue`: enqueue authenticated user. Body requires current search location: `latitude`, `longitude`, optional `accuracyMeters`. This operation is idempotent: if the user is already queued, it keeps a single queue entry and refreshes `latitude`, `longitude` and `accuracyMeters`.
- `DELETE /api/matchmaking/queue`: remove authenticated user from queue.
- `GET /api/matchmaking/queue`: check queue status for authenticated user.

After enqueueing, clients should poll `GET /api/me/home` to discover whether the
queue entry has become an active match. Do not infer match/chat ids locally.

## Matches

- `GET /api/matches/{matchId}`: fetch match details and linked connection id if present. Includes `visualExpiresAt` when a visual review deadline exists for the match.
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match. Includes `partner`, `myDecision`, `partnerDecision`, `expiresAt`, `inactivityExpiresAt` and nullable `guidance` metadata. New first chats initialize guidance; legacy rows may return `guidance = null`.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile for visual phase or later. Includes partner photos with freshly generated read URLs, `visualExpiresAt` and `myPersonalMessageSubmitted`, which tells whether the authenticated user has already sent their visual personal message.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision. `APPROVED` is individual and requires both users to move the match to `VISUAL_PHASE`. `REJECTED` is unilateral cancellation: it closes the first chat, moves the match to `CHAT_REJECTED`, releases locks and applies cancellation penalty policy. If the first-chat deadline already passed, the backend rejects with `CHAT_EXPIRED`; if the inactivity deadline already passed, it rejects with `CHAT_ABANDONED`.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision. The current user's visual review disappears after deciding and that user's match lock is released. A repeated identical decision is idempotent; a contradictory decision is rejected. A rejection is not immediately surfaced to the other participant through Home while their own visual decision is still pending. New decisions after the visual deadline are rejected with `VISUAL_REVIEW_EXPIRED`.
- `PUT /api/matches/{matchId}/personal-messages/me`: store the authenticated user's personal visual-review message. Personal messages are write-once; a second submission returns `409 Conflict` and does not overwrite the first message.
- `GET /api/matches/{matchId}/personal-messages/partner`: get the partner's personal message from `VISUAL_PHASE` onwards. If present, it must be read before any visual decision.

## Chats

- `GET /api/chats/{chatId}`: fetch chat. First-chat responses include `inactivityExpiresAt`; second-chat responses return `inactivityExpiresAt = null`.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user. First-chat sends after absolute timeout are rejected with `CHAT_EXPIRED`; sends after inactivity timeout are rejected with `CHAT_ABANDONED`.
- `GET /api/chats/{chatId}/messages`: list messages as an authenticated chat participant. With no cursor, returns the legacy array. With `after={messageId}` or `afterMessageId={messageId}`, returns `{ "messages": [...], "hasMore": false, "serverTime": "..." }`.
- `POST /api/chats/{chatId}/guidance/next-request`: request the next first-chat guided question for the authenticated participant. No body is required. It returns the current user-scoped guidance state after the request.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation. Returns `201 Created` when a new pending request is created. If the same requester repeats the call while their pending mutual request still exists, returns `200 OK` with the existing request and does not overwrite `reason` or `details`. If the partner already has a pending mutual request for the chat, returns `409 Conflict`.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close the chat without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation and close the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/timeout`: resolve an unanswered mutual cancellation after the configured timeout and close the chat. If the requester calls this because the responder did not answer in time, the requester must not be penalized; future scoring is pending.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Requires non-blank `details`, closes the chat, creates an internal `SafetyReport` in `PENDING` status, creates a directional user block from reporter to reported and returns `penaltyApplied=false`. `ChatExitReason.CHILD_SAFETY_CONCERN` maps explicitly to `SafetyReportReason.CHILD_SAFETY_CONCERN`. Matchmaking treats that block as a bidirectional exclusion. Reporting does not automatically penalize or ban the reported participant; penalties are applied only after admin/backoffice review.

First-chat guidance is backend-owned for MVP:

- The question catalog is a static Spanish resource loaded from `first-chat-guided-questions.es.json`.
- Each first chat has one active question shared by both participants.
- The sequence is deterministic from the chat id and catalog order; changing or reordering the catalog may affect not-yet-selected future questions for an already-active first chat, but the active question id/text are persisted as a snapshot.
- Chat remains free-form. The backend does not semantically evaluate whether a user answered the prompt.
- A participant must have sent at least `chat.first-chat.guidance.required-characters` persisted characters since the current question was activated before requesting another question. One long message can satisfy the threshold; multiple messages accumulate.
- Advancement requires both participants to independently request the next question. The API exposes only `myNextRequested`, `canRequestNext`, `completed`, current question, ordinal, `maxQuestions` and `requiredCharacters`; it does not expose partner readiness, partner request timestamp or partner character count.
- Maximum questions per first chat is `chat.first-chat.guidance.max-questions`, default `3`. When both participants request continuation from the penultimate question and the final configured question becomes active, guidance completes immediately. No question 4 is selected, and the final question remains available as the final prompt with `completed = true`, `canRequestNext = false` and `myNextRequested = false`.
- Compatibility behavior: if an existing guidance row is already at the final configured ordinal with `completedAt = null`, the backend treats it as completed and normalizes `completedAt` plus clears request timestamps when the row is read or mutated.
- Question changes are observed through the existing first-chat polling response. There are no guidance chat messages, push notifications, Home actions, reliability events or analytics events.

## Safety Reports

- `POST /api/safety/reports`: create a user safety report without necessarily closing an active chat. Supported contexts are `CHAT`, `VISUAL_PROFILE`, `PERSONAL_MESSAGE` and `PROFILE_PHOTO`. The backend validates that the authenticated reporter and reported user are the two participants in the referenced chat or visual-phase match; `PROFILE_PHOTO` also requires the reported photo to belong to the matched partner. Duplicate reports for the same reporter, reported user, context type and context id return `200 OK` with the existing report; new reports return `201 Created`. Every report creates or reuses a directional `UserBlock` from reporter to reported, and matchmaking treats any block between two users as a bidirectional exclusion.
- `CHILD_SAFETY_CONCERN` is accepted by direct user reports, chat safety cancellations and admin-created reports. It records a broad concern as a normal `PENDING` report; it does not establish a violation and does not automatically create a penalty or ban. Existing user-created block and active-interaction containment behavior is unchanged.
- User-facing report creation uses the safety-report-specific rate-limit rule under `security.rate-limit.safety-report-*`.

## Admin Safety Reports

All endpoints under `/api/admin/**` require `ROLE_ADMIN`. Firebase-authenticated users receive this role only when they exist locally as active users and their email is listed in `backoffice.admin-emails`.

- `GET /api/admin/safety-reports?status=PENDING&source=USER&reportedUserId=...&reporterUserId=...`: list safety reports. `status` defaults to `PENDING`; other filters are optional. Summary DTOs omit report details, verdict notes, raw email and Firebase UID. They include non-null, read-only `priorityReview`, derived as `status == PENDING && reason == CHILD_SAFETY_CONCERN` and never persisted. After filtering, results are ordered by `priorityReview DESC`, then `createdAt DESC`, before the 100-result limit.
- `POST /api/admin/safety-reports`: create an admin safety report. `contextType=USER` creates a general report about the reported user with `contextId = reportedUserId` and no match/chat context. Contextual admin reports can reference chat, visual profile, personal message or profile photo contexts. Admin-created reports do not auto-block, auto-close chats or auto-apply penalties.
- `GET /api/admin/safety-reports/{reportId}`: fetch report detail, reduced reporter/reported user summaries, evidence snapshot, chat messages for review and associated penalty if one exists.
- `POST /api/admin/safety-reports/{reportId}/dismissal`: dismiss a pending report. Body: `{ "notes": "optional notes" }`. Does not create a penalty.
- `POST /api/admin/safety-reports/{reportId}/abusive-dismissal`: dismiss a pending report as abusive or unjustified. Body: `{ "notes": "optional notes" }`. Does not create a safety penalty; when user reliability is enabled, it records an internal reliability event against the reporter.
- `POST /api/admin/safety-reports/{reportId}/penalty`: confirm a pending report and apply a penalty to the reported user. Temporary body: `{ "type": "TEMPORARY_BAN", "durationHours": 24, "reason": "Harassment confirmed", "notes": "optional notes" }`. Permanent body: `{ "type": "PERMANENT_BAN", "reason": "Severe safety violation", "notes": "optional notes" }`.
- `GET /api/admin/profile-photos/review`: list up to 100 current profile photos where `moderationStatus=NEEDS_REVIEW`, ordered by `createdAt ASC`. The response includes `photoId`, `profileId`, `userId`, `displayName`, `position`, `readUrl`, `photoVersion`, `validationStatus`, `moderationStatus`, `isPersonPhoto`, `isFullBody` and `createdAt`. It does not expose storage keys, buckets, email or Firebase UID.
- `POST /api/admin/profile-photos/{photoId}/moderation`: resolve one photo moderation review. Body: `{ "expectedPhotoVersion": 3, "decision": "APPROVED", "notes": "optional notes" }` or `{ "expectedPhotoVersion": 3, "decision": "REJECTED", "notes": "optional notes" }`. `expectedPhotoVersion` must be the `photoVersion` returned by the queue item the admin reviewed. The only supported transitions are `NEEDS_REVIEW -> APPROVED` and `NEEDS_REVIEW -> REJECTED`; stale review snapshots and photos currently in `PENDING`, `APPROVED` or `REJECTED` return `409 PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE`. On that conflict, the admin should refresh the queue and review the current photo again. Manual moderation changes only `moderationStatus`; it does not alter `validationStatus`, `isPersonPhoto` or `isFullBody`.

Temporary penalties require positive `durationHours`; permanent penalties reject `durationHours` and have `expiresAt = null`. Active penalties block matchmaking and remove the user from the queue if already queued. The penalty expiration job deactivates only expired temporary penalties.

## Connections And Scheduling

- `GET /api/connections/{connectionId}`: fetch connection.
- `GET /api/connections/{connectionId}/chat`: fetch the second chat for a connection. If no chat exists and the confirmed second-chat window is open (`now >= availableAt && now < expiresAt`), this idempotently creates and activates the `SECOND_CHAT`. If called before `availableAt`, returns conflict with `SECOND_CHAT_NOT_AVAILABLE_YET`; if called after `expiresAt` with no chat, returns conflict with `SECOND_CHAT_EXPIRED`.
- `POST /api/connections/{connectionId}/second-chat-dismissal`: hide a finished or non-actionable second-chat next step from the authenticated user's Home. The action is idempotent and returns `{ "dismissed": true }`. It is allowed for read-only/expired/closed second chats and for second-chat windows that already expired without an actionable chat. It returns conflict while the second chat is still actionable.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation. Includes `schedulingExpiresAt` from the parent connection so clients can show a countdown without an extra request.
- `POST /api/connections/{connectionId}/proposals`: submit the authenticated user's ordered scheduling proposal list for the expected current round. Body: `{ "expectedRoundNumber": 1, "proposedDateTimes": ["..."] }`, 1 to `scheduling.max-proposals-per-round` future half-hour slots. Each participant may submit at most one ordered list per round. Existing partner proposals in the same round do not make backend submission invalid.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept a pending partner proposal and schedule second chat at the accepted time. The proposal instant must still be strictly in the future at backend acceptance time; otherwise the endpoint returns `409 SCHEDULING_PROPOSAL_NOT_AVAILABLE`. Expired proposals remain `PENDING`, visible and rejectable.
- `POST /api/connections/{connectionId}/negotiation/rejections`: user explicitly rejects only the partner's pending scheduling proposals for the expected current round. Body: `{ "expectedRoundNumber": 1 }`. A single rejection does not end the round; the round advances only after both users submitted in that round and both lists have been resolved as rejected. On the final permitted round, that second rejection marks the negotiation `FAILED` and closes the connection.

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
`SCHEDULING_PREPARING`/`hasPendingSchedulingConnection`, run the local
scheduling activation job before testing scheduling proposal or timeout flows.

## Local Dev Tooling Endpoints

These endpoints are profile-gated for local manual testing:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`: manually process queued candidate pairs and start first chats.
- `POST /api/local-dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/local-dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.
- `GET /api/local-dev/user-reliability/{userId}`: inspect the internal user reliability score breakdown and active contributing events without mutating state.

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
- `POST /api/local-dev/jobs/user-reliability-cleanup/run`
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
- `INVALID_PROFILE_COUNTRY`: profile `countryCode` is not a known ISO 3166-1 alpha-2 country code from the backend country reference catalog.
- `INVALID_MATCH_FILTERS`: dynamic match filters are internally inconsistent or out of range.
- `PHOTO_POSITION_INVALID`: requested photo position is outside the configured range.
- `PHOTO_POSITION_OCCUPIED`: requested photo position is already used.
- `INVALID_PROFILE_PHOTO`: uploaded profile photo file is invalid.
- `PROFILE_PHOTO_NOT_FOUND`: requested profile photo does not belong to the current profile.
- `USER_NOT_FOUND`: authenticated user id could not be locked for a state-changing operation.
- `CHAT_EXPIRED`: chat action was attempted after the absolute chat deadline.
- `CHAT_ABANDONED`: first-chat action was attempted after the inactivity deadline.
- `FIRST_CHAT_GUIDANCE_PARTICIPATION_REQUIRED`: the requester has not yet sent enough persisted characters during the current first-chat guidance question interval.
- `FIRST_CHAT_GUIDANCE_NEXT_ALREADY_REQUESTED`: the requester already requested continuation for the current first-chat guidance question.
- `FIRST_CHAT_GUIDANCE_COMPLETED`: first-chat guidance already reached the active final configured question.
- `SCHEDULING_EXPIRED`: scheduling action was attempted after the negotiation deadline.
- `SCHEDULING_PROPOSAL_NOT_AVAILABLE`: scheduling proposal cannot be accepted because it is missing, not pending, from another connection, from an old round or its proposed instant is no longer strictly in the future.
- `SCHEDULING_ROUND_CHANGED`: `expectedRoundNumber` does not match the current negotiation round; refresh negotiation and proposals before retrying.
- `SCHEDULING_PARTNER_PROPOSALS_NOT_AVAILABLE`: the expected current round has no pending partner proposal to reject, including retries after proposals were already resolved.
- `VISUAL_REVIEW_EXPIRED`: visual-review action was attempted after the visual deadline.
- `LEGAL_DOCUMENT_NOT_FOUND`: requested legal document type has no current configured document.
- `LEGAL_DOCUMENT_VERSION_NOT_CURRENT`: requested legal document version is not the current configured version.
- `LEGAL_DOCUMENT_ACTION_INVALID`: requested action does not match the configured required action.
- `LEGAL_ACTION_REQUIRED`: protected participation/content write requires current legal document actions before continuing.
- `AUTHENTICITY_VERIFICATION_NOT_CONFIGURED`: profile authenticity verification provider is not configured for this environment.
- `AUTHENTICITY_VERIFICATION_PROVIDER_ERROR`: profile authenticity verification provider failed and fail-on-error is enabled.
- `PROFILE_AUTHENTICITY_VERIFICATION_REQUIRED`: activation requires `authenticityVerificationStatus=VERIFIED`.

## Manual blocking

`POST /api/matches/{matchId}/block` requires no body and returns block `id`, `source`, and `createdAt`: `201 Created` for a new directional block and `200 OK` for an idempotent replay. Manual blocking creates no report, penalty, or reliability event. A block in either direction excludes the pair and causes positive progression to fail with `409 USER_PAIR_BLOCKED`. Reads, rejection, exit, cancellation, and safety paths remain available. There is no unblock endpoint in the current MVP.
