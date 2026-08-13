# API

This file summarizes the current controller surface for human readers.
The formal OpenAPI contract lives in `openapi.yaml`.

## Security Headers

Most Android-facing `/api/**` calls use two independent security headers:

```http
X-Firebase-AppCheck: <token>
Authorization: Bearer <Firebase ID token>
```

Firebase App Check attests the calling app and Firebase Authentication
identifies the user. App Check does not replace user authentication,
authorization, rate limiting, legal gates, TLS or domain validation. Stable App
Check failures are `401 MISSING_APP_CHECK_TOKEN`, `401 INVALID_APP_CHECK_TOKEN`
and `503 APP_CHECK_VERIFICATION_UNAVAILABLE`, using the normal JSON error
shape.

The App Check token must not be sent through query parameters, URLs, cookies or
request bodies. The header may also be present on `/api/ping`; the backend may
ignore it there. `APP_CHECK_VERIFICATION_UNAVAILABLE` is recoverable and clients
should not retry it in a tight loop. Replay protection and limited-use tokens
are intentionally deferred.

## Health

- `GET /api/ping`: returns `{ "status": "ok" }`.

## Users


- `POST /api/me/provision`: create or link the authenticated Firebase identity to a local backend user. This is the only Firebase flow endpoint that provisions a missing local user. The first successful Reals provisioning flow sets the immutable backend-owned `authOrigin`; later Firebase provider metadata or provider ordering does not rewrite it.
- `POST /api/auth/password-reset`: public Firebase-bearer-optional password reset request. Body: `{ "email": "user@example.com" }`. Syntactically valid requests return `202 Accepted` with no account-existence, auth-origin, deletion-state or Firebase-delivery disclosure. App Check still applies when enforced.
- `GET /api/me`: fetch the authenticated user.
- `GET /api/me/home`: fetch the authenticated user's current app state for home/navigation. Includes profile status, matchmaking availability, active interaction counts, pending actions, next steps and passive notices. Home is an explicit navigation contract; clients should not infer actions from raw match or connection states.
- `GET /api/me/home/status`: fetch the authenticated user's persisted Home `version`, `dirty` flag, nullable `nextRefreshAt` wake-up marker and `serverTime`. This is cheap and does not aggregate full Home state.
- `GET /api/me/home/pending`: fetch lightweight pending/actionable Home navigation state with the current Home `version`. It returns pending actions, next steps and passive notices without partner summaries, matchmaking availability or active interaction counts.
- `PUT /api/me/push-tokens`: register or refresh the authenticated user's Android FCM device token. Body: `{ "token": "...", "platform": "ANDROID" }`. Returns `{ "registered": true }`.
- `DELETE /api/me`: schedule soft deletion for the authenticated user account. The account remains recoverable during `account.deletion.recovery-window-days`.
- `POST /api/me/reactivation`: reactivate an account that is still inside the deletion recovery window.
- `POST /api/me/deletion/finalization`: for an already deleted account, irreversibly abandon recovery immediately by using the same permanent-finalization behavior as the scheduled finalizer.
- `POST /api/me/local-dev/email-verification`: local-only `local-firebase` helper gated by `local-dev.firebase.email-auto-verification-enabled=true`. Requires an authenticated provisioned Firebase-backed `ROLE_USER`, marks only the caller's Firebase Auth account `emailVerified=true` through Firebase Admin, returns `204`, and does not mutate PostgreSQL or profile state. The client must reload Firebase user state and force-refresh the ID token before using normal photo upload/replacement and profile activation.
- `GET /api/legal/documents/current`: unauthenticated endpoint that returns the current configured legal document catalog. When App Check is enabled, it still requires `X-Firebase-AppCheck`. It may return an empty `documents` array.
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
authenticity verification/private affinity-answer writes/photo upload/photo
reorder/photo replacement, entering matchmaking, sending chat messages,
first-chat guidance next requests, visual personal-message writes, positive
first-chat and visual decisions, and scheduling proposal
submission/acceptance/partner proposal rejection.

Reads remain available. Account deletion/reactivation, legal endpoints, chat
exit/cancellation/safety operations, safety reports, queue inspection/leaving,
push-token registration, admin endpoints, actuator endpoints and local-dev
tooling are not gated. `REJECTED` first-chat and visual decisions remain
available; only `APPROVED` requires current legal compliance.

Reference-data reads such as `GET /api/reference/countries`, `GET
/api/reference/affinity-questions` and `GET /api/reference/profile-questions`
are authenticated under the normal `/api/**` -> `ROLE_USER` rule, but are not
legal-compliance gated and do not require an existing profile.


## Public Profile Questions

Public profile questions are separate from private affinity questions. Profile
questions are free-text, single-line, optional profile-owned answers. Users may
save answers for any active catalog question, edit them, delete them and replace
the ordered public selection without deleting other saved answers. At most three
answered current questions may be selected for public display; zero selections is
valid.

- `GET /api/reference/profile-questions`: authenticated reference catalog read.
  Returns active questions only, in catalog order, without the internal `active`
  flag.
- `GET /api/me/profile/question-answers`: authenticated private read for the
  current user. Returns every saved answer, including unselected and stale
  semantic-version answers, with `current`, semantic version and timestamps.
- `PUT /api/me/profile/question-answers/{questionId}`: legal-gated create or
  update. The answer is trimmed, must be nonblank single-line plain text and may
  contain at most 160 characters after normalization. The current question
  semantic version is stored. Existing selection position is preserved.
- `DELETE /api/me/profile/question-answers/{questionId}`: legal-gated idempotent
  delete. Deleting a selected answer compacts remaining selected positions.
- `PUT /api/me/profile/question-selections`: legal-gated full replacement. The
  request order defines contiguous public positions 1..3. Every selected id must
  have a saved current answer for an active catalog question. Failed validation
  leaves previous positions unchanged.

Profile-question mutations do not activate a draft profile and do not move an
active profile back to draft. Selected current answers are exposed only through
`GET /api/matches/{matchId}/visual-profile` after the normal visual-content
access guard succeeds. Unselected answers, stale semantic answers, timestamps
and semantic/content versions are never returned to counterparts.


For first-chat navigation, `GET /api/me/home` exposes a
`pendingActions[]` item with `type = FIRST_CHAT` only while the match remains in
`CHAT_ACTIVE`, the first chat exists, the chat is active, the chat has not
expired and the current user has not decided. Once the current user decides, the
action disappears from Home.

First-chat countdowns in the client are advisory UX. The backend remains the
source of truth: absolute timeout uses `expiresAt`/`timeoutAt`, and inactivity
timeout uses `inactivityExpiresAt`. In first-chat decision-only state,
`inactivityExpiresAt = null` and inactivity timeout is disabled; the original
absolute timeout still applies. Mutating endpoints reject stale first-chat
actions with `CHAT_EXPIRED` for absolute timeout or `CHAT_ABANDONED` for
inactivity timeout.

For visual review navigation, Home exposes a `pendingActions[]` item with
`type = VISUAL_REVIEW` only while the match remains in `VISUAL_PHASE`, the
visual review exists, server time is greater than or equal to the review
availability timestamp, the visual phase has not expired and the current user
has not decided. Expired, hidden-not-yet-available or already-decided visual
reviews are not returned as actions. For a currently actionable visual review,
both `GET /api/me/home` and `GET /api/me/home/pending` include
`visualStartedAt`, the authoritative availability/start of the usable
visual-review window, and `visualExpiresAt`, the authoritative expiration of
that window, on the pending action. Both fields are `null` for pending actions
that are not `VISUAL_REVIEW`.

When a delayed visual review is hidden, `/api/me/home/status` may expose
`nextRefreshAt`, the earliest unconsumed server-side Home wake-up marker for the
user. Clients should compare it to the response `serverTime`; when
`serverTime >= nextRefreshAt`, a full `GET /api/me/home` refresh reconciles the
marker. The marker may remain in the past until that full Home refresh succeeds.

When a visual review first becomes available, the backend no longer sends an
immediate availability push. At `VisualReview` creation time it persists
`reminderEligibleAt`, calculated from the configured visual-review duration so
that the reminder becomes eligible when 40% of the phase remains. The
`VisualReviewReminderNotificationJob` runs approximately every 30 minutes and
attempts a privacy-safe external push with type `VISUAL_REVIEW_REMINDER` only
for each participant whose own visual decision is still pending. The provider
payload includes a display title/body plus data fields; the data contract
contains only `type` and `matchId`. Tap behavior remains a client concern and
Home remains the source of actionable state. Delivery is
deduplicated per user, notification type and match id. Legacy visual reviews
whose `reminderEligibleAt` is `null` are ignored unless manually backfilled
outside Flyway. There is no internal notification inbox, notification bell or
unread count.

When a second chat has a confirmed scheduled time and the connection is still
`SECOND_CHAT_SCHEDULED`, `SecondChatReminderNotificationJob`
attempts privacy-safe external push reminders per participant before
`confirmedDateTime` using the list `notifications.second-chat-reminder.minutes-before`
(default `[10]`). Overdue reminder targets may be recovered while still useful:
an older, larger lead time stops being eligible once the next closer configured
reminder range applies, and no reminder is sent after the confirmed start time.
The notification type is `SECOND_CHAT_REMINDER`; the provider payload includes
a display title/body plus data fields. The data contract contains only `type`,
`connectionId` and `availableAt`. Delivery is deduplicated per user,
notification type, connection id and lead time. Android FCM uses
`second-chat-<connectionId>` as the notification tag and caps transport TTL at
the confirmed start time.

After a confirmed second-chat start, `SecondChatStartNotificationJob` attempts
a privacy-safe external push with type `SECOND_CHAT_STARTED` only for
participants who have not joined. The default window is
`confirmedDateTime <= now <= confirmedDateTime + 5 minutes`; later scheduler
runs skip stale starts. The job runs every 4 minutes by default so normal
fixed-delay execution has slack inside the five-minute window. The provider
payload includes `type`, `connectionId`, `matchId` and `availableAt`. The
backend title is `Tu segunda charla ya empez?` and the body is `Entr? ahora a
Reals para sumarte.` Android FCM uses `second-chat-<connectionId>` as the same
replacement tag as the second-chat reminder, and transport TTL is capped at the
configured second-chat on-time cutoff. Delivery is deduplicated per user,
notification type and `secondChatStartedAggregateId(connectionId)`.

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
- `activeConnectionCount`: visible active/actionable connection next steps.
  Recent `SECOND_CHAT_EXPIRED` history is returned in `nextSteps[]` but is not
  counted here.
- `hasPendingSchedulingConnection`: boolean that indicates at least one
  non-dismissed, non-blocked connection is internally waiting for scheduling
  preparation. It intentionally avoids exposing an exact internal count.
- `actionableConnectionCount`: visible active/actionable connection next steps.
  Recent `SECOND_CHAT_EXPIRED` history is returned in `nextSteps[]` but is not
  counted here.

The lightweight pending endpoint intentionally omits partner summaries. Clients
that need full profile/matchmaking context should call `GET /api/me/home`,
which remains the source of truth for the complete Home contract. Future clients
can poll `GET /api/me/home/status` and call the full Home endpoint only when the
persisted version changes.

Bruno debug requests for this `local-firebase` flow live under
`bruno/reals-backend-happy-path/11 - Home Polling Debug`; they use Firebase
`Authorization: Bearer ...` tokens, not `X-Dev-User-Id`.

`nextSteps[]` includes `SCHEDULING`, `SECOND_CHAT_SCHEDULED`,
`SECOND_CHAT_AVAILABLE`, `SECOND_CHAT_EXPIRED` and
`SECOND_CHAT_READ_ONLY` items. `SCHEDULING_PENDING`
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
entry at `availableAt`; before that time, render the agreed time.
`secondChat.entryClosesAt` is the backend-calculated entry cutoff for the
current user, derived from the configured
`chat.second-chat.entry-window-minutes`, and participants who have not joined
may enter only while `serverTime < entryClosesAt`. `secondChat.myAttendanceStatus`
is the current user's attendance status. `secondChat.expiresAt` is the end of
the absolute writable second-chat window, and `secondChat.durationMinutes`
exposes the configured maximum writable duration so clients do not hardcode it.
In `SECOND_CHAT_SCHEDULED`, `secondChat.chatId` is absent until
`POST /api/connections/{connectionId}/second-chat/join` materializes the second
chat row. If join is called before `availableAt`, it returns conflict with
`SECOND_CHAT_NOT_AVAILABLE_YET`. If it is called at or after `entryClosesAt`
for a user who has not joined, it returns conflict with
`SECOND_CHAT_ENTRY_CLOSED` and does not create a chat for that user.
When the current user has not joined and `serverTime >= entryClosesAt`, Home
returns `SECOND_CHAT_EXPIRED` as a recent, dismissible interaction rather than
an active/actionable second chat. This effective read-side state is derived
without mutating the connection and does not require `SecondChatLifecycleJob` to
have run. If the lifecycle job later closes a zero-attendance connection without
materializing a second-chat row, Home continues to return
`SECOND_CHAT_EXPIRED` until the user dismisses it or until
`entryClosesAt + chat.second-chat.read-only-retention-minutes`; historical
expired items do not increment active/actionable connection counts. A user who
already joined with `ON_TIME` or `LATE` is not converted to
`SECOND_CHAT_EXPIRED` at the entry cutoff. After terminal chat expiration, Home
may return `SECOND_CHAT_READ_ONLY` with `secondChat.chatStatus = EXPIRED` and
`secondChat.readOnlyUntil`; clients can show prior messages but must not allow
sending new messages. Once
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
- `POST /api/me/profile/activation`: activate authenticated user's profile. Requires the current Firebase ID token to have `emailVerified=true`; otherwise returns `409 EMAIL_NOT_VERIFIED` with message `Verificá tu email antes de activar el perfil.` Email verification is not required for profile creation, editing, photo deletion, photo reorder or match-filter configuration.
- `PUT /api/me/profile/match-filters`: replace matchmaking preferences. Body: `intention`, `lookingForGenders`, `preferredMinAge`, `preferredMaxAge`, `maxDistanceKm`.
- `POST /api/me/profile/authenticity-verification`: optionally run profile authenticity verification for the authenticated user's profile. Profile Authenticity Verification is not legal identity verification. With provider `none` outside `prod`, the MVP compatibility path may mark the profile `VERIFIED`; this does not represent liveness, face comparison, legal identity, document verification or age assurance. With provider `none` in `prod`, verification is unavailable and returns `409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED`; no `VERIFIED` state is persisted.
- `GET /api/reference/affinity-questions`: list the server-authoritative Spanish affinity-question catalog, including catalog version, visible categories, active questions, prompt text, answer type and ordered answer options. The response intentionally omits ranking policies, conversation policies, matrices, weights and hidden scoring configuration.
- `GET /api/me/profile/affinity-answers`: list only the authenticated user's private affinity answers.
- `PATCH /api/me/profile/affinity-answers`: partial idempotent upsert for private affinity answers. Only included question ids are modified; omitted answers remain unchanged. Duplicate question ids return `400 DUPLICATE_AFFINITY_QUESTION`; missing, inactive, deprecated or unsupported questions return `400 INVALID_AFFINITY_QUESTION`; invalid options return `400 INVALID_AFFINITY_ANSWER`. The current catalog semantic version is stored with each answer. Requires current legal requirements and an existing `DRAFT` or `ACTIVE` profile; verified email is not required.
- `DELETE /api/me/profile/affinity-answers/{questionId}`: delete only the authenticated profile's answer for one normalized question id. Unlike `PATCH`, deletion does not require the question to be active or present in the current catalog, so stale, deprecated or removed private answers can be cleared. Deleting a nonexistent owned answer is an idempotent no-op. Requires current legal requirements and returns the complete remaining answer list.
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

Affinity answers are always private. They are returned only through the
authenticated current-profile endpoints and are not exposed through another
user's profile, match responses, visual review, first-chat responses, Home,
partner summaries or counterpart-facing endpoints. New first chats may derive
immutable prompt-text snapshots, and visual review may expose positive shared
category indicators. Neither output includes answer codes, answer labels,
question ids in visual review, semantic versions, conversation kinds,
conversation potential, scores, percentages, shared-question counts, confidence
or affinity factors. Matchmaking ranking and eligibility behavior are unchanged.

Private affinity write operations serialize on the authenticated user's profile
row. `PATCH` and `DELETE` use the same lock order: resolve current profile,
acquire a pessimistic write lock on that profile, then read and mutate
`affinity_question_answers`. Reads do not acquire this lock.

Affinity question `semanticVersion` changes represent answer meaning or
comparison-semantics changes. A stored answer whose semantic version no longer
matches the current catalog is excluded from pairwise affinity evaluation until
the user answers the current semantic version. `contentVersion` changes are
wording-only and do not invalidate stored answers. Missing answers are neutral
and never count as incompatibility.

Photo response `url` values are renderable read URLs generated by the backend
from each photo's stored object key. Storage keys and bucket names are never
returned to clients. For private S3/R2/MinIO storage these URLs may be presigned
and time-limited, so clients should use them for display and refetch photo
responses when needed instead of persisting URLs permanently.

Adding a profile photo and replacing an existing photo file require the current
Firebase email to be verified. List, read, delete, reorder and non-photo profile
edits do not gain this verified-email gate from photo upload hardening.

Photo upload validation has two separate fields. `validationStatus` is the
blocking technical upload result for file type, size, decoding and dimensions.
The server accepts only actual JPEG and PNG content whose declared multipart
content type matches the detected format. WebP and other formats are rejected.
Accepted images are normalized server-side to metadata-stripped `image/jpeg`;
EXIF orientation is applied, transparent PNG pixels use a fixed neutral
background, and neither object storage nor moderation receives original source
bytes when normalization changes them. Defaults are 5 MiB compressed size, 6000
input width, 6000 input height, 20,000,000 input pixels, 2048 maximum output
dimension and JPEG quality `0.88`.
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

Upload and replacement share a single-instance global concurrency guard. With
the default limit of two pipelines, an additional concurrent request fails
immediately with `503 PROFILE_PHOTO_UPLOAD_BUSY` and `Retry-After: 1`. Upload
and replacement also share the authenticated post-auth rate-limit group
`profile-photo-uploads`, keyed by backend user id, defaulting to 12 requests per
60 seconds. Delete and reorder do not consume that bucket. The concurrency guard
and rate-limit buckets are single-instance/in-memory controls. Servlet
multipart parsing happens before the controller acquires the semaphore, so
deployment gateways should enforce an equivalent request-body limit before the
request reaches the application.

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
- `GET /api/matches/{matchId}/chat`: fetch active first chat for match. Includes `partner`, `myDecision`, `partnerDecision`, `expiresAt`, `inactivityExpiresAt`, `serverTime` and nullable `guidance` metadata. New first chats initialize guidance; legacy rows may return `guidance = null`. `inactivityExpiresAt = null` while exactly one participant has approved and the other remains pending. Clients may use `serverTime` as an advisory backend clock snapshot for first-chat countdown and suggestion UX; the backend remains authoritative for mutations and expiration decisions, and does not own suggestion visibility or local dismissal.
- `GET /api/matches/{matchId}/visual-profile`: fetch partner profile only while visual content is available. During `VISUAL_PHASE`, the visual review must exist, server time must be greater than or equal to its availability timestamp, and its `visualExpiresAt` deadline must still be in the future by server time. After `VISUAL_APPROVED`, a non-closed connection for the same match must exist and include the requester. Blocked pairs, unrelated users, `CHAT_ACTIVE`, `CHAT_REJECTED`, `VISUAL_REJECTED` and `EXPIRED` matches are denied. The response includes partner photos with freshly generated read URLs, `visualExpiresAt`, `myPersonalMessageSubmitted`, partner personal-message submitted/read flags for client emphasis, `decisionRequiresPartnerPersonalMessageRead` retained temporarily as always `false`, and required `affinityIndicators: [] | [{ categoryId, title }]` containing at most three positive shared category snapshots.
- `POST /api/matches/{matchId}/chat-decision`: submit first-chat continuation decision. `APPROVED` is individual and requires both users to move the match to `VISUAL_PHASE`. `REJECTED` while the counterpart is still pending is unilateral cancellation: it closes the first chat, moves the match to `CHAT_REJECTED`, releases locks and applies cancellation penalty policy. `REJECTED` after the counterpart already approved persists the final decision pair, finishes the chat as `FIRST_CHAT_DECISION_MISMATCH`, moves the match to `CHAT_REJECTED`, releases locks and does not create cancellation artifacts or penalties. Once one participant has approved and the other remains pending, only the pending participant may submit the remaining final decision. If the first-chat deadline already passed, the backend rejects with `CHAT_EXPIRED`; if the inactivity deadline already passed outside decision-only state, it rejects with `CHAT_ABANDONED`.
- `POST /api/matches/{matchId}/visual-decision`: submit visual decision. The current user's visual review disappears after deciding and that user's match lock is released. A repeated identical decision is idempotent; a contradictory decision is rejected. Reading an optional partner personal message is encouraged but is not required before `APPROVED` or `REJECTED`. A rejection is not immediately surfaced to the other participant through Home while their own visual decision is still pending. New decisions after the visual deadline are rejected with `VISUAL_REVIEW_EXPIRED`.
- `PUT /api/matches/{matchId}/personal-messages/me`: store the authenticated user's personal visual-review message while visual content is available. Personal messages are write-once; a second submission returns `409 Conflict` and does not overwrite the first message.
- `GET /api/matches/{matchId}/personal-messages/partner`: get the partner's optional personal message while visual content is available. Opening an existing message persists the requester-specific read timestamp and does not create a reliability event. Denied reads do not mark the message as read. Unread messages remain visible to clients through status fields for visual emphasis, but they do not block visual decisions.

## Chats

- `GET /api/chats/{chatId}`: fetch chat. First-chat responses include `inactivityExpiresAt`; second-chat responses return `inactivityExpiresAt = null`.
- `POST /api/chats/{chatId}/messages`: send message as authenticated user. First-chat sends after absolute timeout are rejected with `CHAT_EXPIRED`; sends after inactivity timeout are rejected with `CHAT_ABANDONED`. While a `MUTUAL_CANCEL` exit request is `PENDING`, message sends are rejected with `CHAT_MUTUAL_CANCELLATION_PENDING`. In first-chat decision-only state, ordinary sends are rejected with `FIRST_CHAT_DECISION_ONLY`.
- `POST /api/chats/{chatId}/audio-messages`: send an audio message with multipart `file` and required `clientMessageId` UUID. New logical messages return `201 Created`; an idempotent replay with the same `chatId + senderId + clientMessageId` and the same SHA-256 returns `200 OK`; the same id with different bytes returns `409 CHAT_MESSAGE_IDEMPOTENCY_CONFLICT`. In first-chat decision-only state, new audio sends are rejected with `FIRST_CHAT_DECISION_ONLY`.
- `GET /api/chats/{chatId}/messages`: list messages as an authenticated chat participant. Optional `limit` controls the page size; the default is 200 and the maximum is 500. Without `after`/`afterMessageId`, the endpoint preserves the legacy JSON array response, but it now returns only the most recent bounded page in chronological order. With `after={messageId}` or `afterMessageId={messageId}`, it preserves the incremental wrapper `{ "messages": [...], "hasMore": boolean, "serverTime": "..." }`, returns messages strictly after the cursor ordered by `sentAt ASC, id ASC`, and sets `hasMore=true` when more rows exist beyond the returned page.
- `POST /api/chats/{chatId}/guidance/next-request`: request the next first-chat guided question for the authenticated participant. No body is required. It returns the current user-scoped guidance state after the request. While a `MUTUAL_CANCEL` exit request is `PENDING`, guidance next requests are rejected with `CHAT_MUTUAL_CANCELLATION_PENDING`. In first-chat decision-only state, next requests are rejected with `FIRST_CHAT_DECISION_ONLY`.
- `POST /api/chats/{chatId}/exit-requests`: request mutual cancellation. Returns `201 Created` when a new pending request is created. If the same requester repeats the call while their pending mutual request still exists, returns `200 OK` with the existing request and does not overwrite `reason` or `details`. If the partner already has a pending mutual request for the chat, returns `409 Conflict`. A pending mutual cancellation pauses new messages and first-chat guidance advancement, but message reads, exit-request polling, acceptance, rejection, timeout, unilateral cancellation and safety cancellation remain available. In first-chat decision-only state, new mutual-cancellation requests are rejected with `FIRST_CHAT_DECISION_ONLY`.
- `GET /api/chats/{chatId}/exit-requests`: list exit requests visible to a participant.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/acceptance`: accept mutual cancellation and close the chat without penalty.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/rejection`: reject mutual cancellation and close the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- `POST /api/chats/{chatId}/exit-requests/{exitRequestId}/timeout`: resolve an unanswered mutual cancellation after the configured timeout and close the chat. If the requester calls this because the responder did not answer in time, the requester must not be penalized; future scoring is pending.
- `POST /api/chats/{chatId}/cancellations`: unilateral cancellation. Applies penalty policy. In first-chat decision-only state, direct ordinary cancellation is rejected with `FIRST_CHAT_DECISION_ONLY`; submit final `REJECTED`, safety report or manual block instead.
- `POST /api/chats/{chatId}/safety-cancellations`: safety/report cancellation. Requires non-blank `details`, closes the chat, creates an internal `SafetyReport` in `PENDING` status, creates a directional user block from reporter to reported and returns `penaltyApplied=false`. `ChatExitReason.CHILD_SAFETY_CONCERN` maps explicitly to `SafetyReportReason.CHILD_SAFETY_CONCERN`. Matchmaking treats that block as a bidirectional exclusion. Reporting does not automatically penalize or ban the reported participant; penalties are applied only after admin/backoffice review.

Audio messages are part of the same ordered chat-message stream as text, not a separate feed. Message DTOs add nullable `clientMessageId`, `messageType` (`TEXT` or `AUDIO`) and `audio`. Text messages keep non-null `content` and `audio = null`; audio messages have `content = null` and `audio = { url, durationMillis, contentType, sizeBytes }`. Bucket and object key are never returned. The URL is generated fresh from private S3-compatible storage when the response is serialized and must not be persisted by clients.

The audio MVP accepts actual MPEG-4/M4A audio-only AAC (`mp4a`) uploaded as `audio/mp4`, at most 2 MiB and at most 60,000 ms. Equality belongs to the accepted side: `duration <= 60s` passes and `duration > 60s` fails. Persisted `durationMillis` is rounded up from the MP4 timescale rational value. The backend validates the binary container server-side and rejects empty, malformed/truncated, MIME-spoofed, metadata-only, video-containing, no-audio, missing AAC codec configuration, no-media-payload and unsupported-codec MP4 files. There is no transcription, waveform generation, speech recognition, moderation, voice analysis or transcoding.

Audio creation is feature-flagged. Local profiles and tests enable it. Shared `dev` enables it by default. `prod` enables it by default. An environment can set `CHAT_AUDIO_ENABLED=false` to disable creation of new audio messages. The flag blocks new audio sends only; existing persisted audio remains readable and serialized. `audioPolicy` is advisory UX state exposed on chat-loading/status responses. Stable reasons include `FEATURE_DISABLED`, `CHAT_NOT_WRITABLE`, `GUIDANCE_NOT_AVAILABLE`, `GUIDANCE_REQUIRED`, `LIMIT_REACHED`, `WAITING_FOR_BOTH` and `WAITING_DELAY`; the send transaction remains authoritative.

First-chat guidance is backend-owned for MVP:

- The generic fallback catalog is a static Spanish resource loaded from `first-chat-guided-questions.es.json`; affinity-derived prompts use the active Spanish prompt text from `affinity-questions.es-AR.json` at first-chat initialization time.
- Each first chat has one active question shared by both participants.
- New first chats persist the complete prompt sequence in `conversation_prompt_snapshots` before guidance is initialized. Advancement reads the next persisted `(chatId, ordinal)` row and never rereads affinity answers, affinity prompt text or category titles.
- Affinity prompt selection uses positive `STANDARD` shared-affinity or constructive-contrast conversation signals, sorted by potential, category display order, catalog question order and question id. It takes one per category first, fills from remaining eligible signals second, then fills from the deterministic generic catalog. Affinity prompts appear before generic fallback prompts and source questions are not duplicated.
- Later affinity-answer edits/deletions and later catalog wording changes do not alter active or future prompts in that first chat. Legacy first chats without prompt snapshots keep their persisted active guidance question authoritative and advance with generic deterministic fallback.
- Chat remains free-form. The backend does not semantically evaluate whether a user answered the prompt.
- A participant must have sent at least `chat.first-chat.guidance.required-characters` persisted characters since the current question was activated before requesting another question. One long message can satisfy the threshold; multiple messages accumulate.
- Advancement requires both participants to independently request the next question. The API exposes only `myNextRequested`, `canRequestNext`, `completed`, current question, ordinal, `maxQuestions` and `requiredCharacters`; it does not expose partner readiness, partner request timestamp or partner character count.
- Maximum questions per first chat is `chat.first-chat.guidance.max-questions`, default `3`. When both participants request continuation from the penultimate question and the final configured question becomes active, guidance completes immediately. No question 4 is selected, and the final question remains available as the final prompt with `completed = true`, `canRequestNext = false` and `myNextRequested = false`.
- First-chat audio uses shared guidance progress: `answeredGuidanceQuestions = max(currentQuestionOrdinal - 1, 0)`. Local/test require `1`; dev/prod require `2`; `maxQuestions` remains `3`. Legacy first chats without guidance return `GUIDANCE_NOT_AVAILABLE`. Each participant may create at most one first-chat audio message; idempotent replay of that same message does not consume another slot.
- Compatibility behavior: if an existing guidance row is already at the final configured ordinal with `completedAt = null`, the backend treats it as completed and normalizes `completedAt` plus clears request timestamps when the row is read or mutated.
- Question changes are observed through the existing first-chat polling response. There are no guidance chat messages, push notifications, Home actions, reliability events or analytics events.

## Safety Reports

- `POST /api/safety/reports`: create a user safety report without necessarily closing an active chat. Supported contexts are `CHAT`, `VISUAL_PROFILE`, `PERSONAL_MESSAGE` and `PROFILE_PHOTO`. The backend validates that the authenticated reporter and reported user are the two participants in the referenced chat or visual-phase match; `PROFILE_PHOTO` also requires the reported photo to belong to the matched partner. Duplicate reports for the same reporter, reported user, context type and context id return `200 OK` with the existing report; new reports return `201 Created`. Every report creates or reuses a directional `UserBlock` from reporter to reported, and matchmaking treats any block between two users as a bidirectional exclusion.
- `CHILD_SAFETY_CONCERN` is accepted by direct user reports, chat safety cancellations and admin-created reports. It records a broad concern as a normal `PENDING` report; it does not establish a violation and does not automatically create a penalty or ban. Existing user-created block and active-interaction containment behavior is unchanged.
- User-facing report creation uses the safety-report-specific rate-limit rule under `security.rate-limit.safety-report-*`.
- Rate limiting has two single-instance Caffeine stages. Pre-authentication buckets use `pre-auth:{endpoint-group}:ip:{request.remoteAddr}` before Firebase verification with broad dedicated `security.rate-limit.pre-auth-*` quotas; they do not reuse per-user message, photo, provisioning or safety-report quotas. Post-authentication buckets use `post-auth:{endpoint-group}:user:{backend-user-id}`, `post-auth:{endpoint-group}:firebase:{firebase-uid}` or `post-auth:{endpoint-group}:local-dev:{dev-user-id}` after authentication and preserve endpoint-specific user quotas. Production reverse proxies must be configured so the servlet container resolves the real client IP from trusted proxy infrastructure; the application does not trust arbitrary forwarded-IP headers.

## Admin Safety Reports

All endpoints under `/api/admin/**` require `ROLE_ADMIN`. Firebase-authenticated users receive this role only when the verified Firebase UID resolves to an active backend user, the backend user's persisted `firebaseUid` matches the token UID, the Firebase token email is verified, and the normalized Firebase token email is listed in `backoffice.admin-emails`/`BACKOFFICE_ADMIN_EMAILS`. The backend user's persisted local email is not used as an allowlist fallback, and unprovisioned Firebase principals are never administrators.

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
- `POST /api/connections/{connectionId}/second-chat/join`: explicit authenticated second-chat join. It creates or activates the chat, classifies the caller as `ON_TIME` for `scheduledAt <= now < onTimeUntil` or `LATE` for `onTimeUntil <= now < entryClosesAt`, and returns authoritative attendance and conversation lifecycle state. Repeated joins preserve the original `joinedAt` and classification.
- `GET /api/connections/{connectionId}/second-chat/status`: side-effect-free authoritative second-chat status with `serverTime`, join windows, attendance statuses, no-show compatibility fields, the active generic resolution request, conversation deadlines, cooldowns and read-only terminal metadata. Expired pending requests suppress invalid actions; polling does not mutate lifecycle state.
- `POST /api/connections/{connectionId}/second-chat/no-show-claims`: create an authenticated partner no-show claim after the requester has joined and `onTimeUntil <= now < entryClosesAt`. The persisted countdown is 60 seconds capped at `entryClosesAt`.
- `POST /api/connections/{connectionId}/second-chat/completion-requests`: create a second-chat mutual-completion request after both users joined, `conversationStartedAt + 10 minutes` has arrived and both users have sent at least one message. New requests return `201`; active same-requester replays return `200`; countdown is 60 seconds capped by `timeoutAt`; requester cooldown is 60 seconds after rejection, timeout or message cancellation.
- `POST /api/connections/{connectionId}/second-chat/completion-requests/{requestId}/decision`: responder accepts or rejects a pending mutual-completion request. Acceptance before `expiresAt` finishes the chat as `FINISHED / SECOND_CHAT_MUTUAL_COMPLETION`; exact expiry times out the request and does not finish the chat.
- `POST /api/connections/{connectionId}/second-chat/inactivity-claims`: latest-message author may claim partner inactivity from `max(lastMessageAt, conversationStartedAt) + 5 minutes` until before `max(lastMessageAt, conversationStartedAt) + 10 minutes`. Countdown is 60 seconds capped by automatic inactivity and absolute timeout; messages before expiry cancel the claim; messages at or after expiry are rejected after terminal inactivity resolution commits.
- `GET /api/connections/{connectionId}/chat`: fetch an already materialized visible second chat for a connection. This GET no longer creates a chat or records attendance. During an open entry window with no chat it returns `SECOND_CHAT_JOIN_REQUIRED`; clients must call the join endpoint.
- `POST /api/connections/{connectionId}/second-chat-dismissal`: hide a finished or non-actionable second-chat next step from the authenticated user's Home. The action is idempotent and returns `{ "dismissed": true }`. It is allowed for read-only/expired/closed second chats and for second-chat windows that already expired without an actionable chat. It returns conflict while the second chat is still actionable.
- `GET /api/connections/{connectionId}/negotiation`: fetch scheduling negotiation. Includes `schedulingExpiresAt` from the parent connection so clients can show a countdown without an extra request.
- `GET /api/connections/{connectionId}/scheduling-availability`: side-effect-free read of the authenticated user's unavailable second-chat windows, excluding the path connection. The response includes `conflictWindowMinutes`, sorted `unavailableWindows` and `serverTime`.
- `POST /api/connections/{connectionId}/proposals`: submit the authenticated user's ordered scheduling proposal list for the expected current round. Body: `{ "expectedRoundNumber": 1, "proposedDateTimes": ["..."] }`, 1 to `scheduling.max-proposals-per-round` future half-hour slots. Each participant may submit at most one ordered list per round. Existing partner proposals in the same round do not make backend submission invalid. Any proposed instant in the configured inclusive conflict window around another confirmed second chat for the submitting user returns `409 SCHEDULING_SLOT_CONFLICT`.
- `GET /api/connections/{connectionId}/proposals`: list scheduling proposals.
- `POST /api/connections/{connectionId}/proposals/{proposalId}/acceptance`: accept a pending partner proposal and schedule second chat at the accepted time. The proposal instant must still be strictly in the future at backend acceptance time and outside the configured inclusive conflict window for both participants. Expired proposals return `409 SCHEDULING_PROPOSAL_NOT_AVAILABLE`; slot conflicts return `409 SCHEDULING_SLOT_CONFLICT`. Expired proposals remain `PENDING`, visible and rejectable.
- `POST /api/connections/{connectionId}/negotiation/rejections`: user explicitly rejects only the partner's pending scheduling proposals for the expected current round. Body: `{ "expectedRoundNumber": 1 }`. A single rejection does not end the round; the round advances only after both users submitted in that round and both lists have been resolved as rejected. On the final permitted round, that second rejection marks the negotiation `FAILED` and closes the connection.

Scheduling mutations after `schedulingExpiresAt` are rejected with
`SCHEDULING_EXPIRED`. The timeout job owns the persistent transition to
`FAILED`/closed when it runs.

Second-chat slot conflicts are compared as instants. Only confirmed negotiations
whose connections are still `SECOND_CHAT_SCHEDULED` or
`SECOND_CHAT_AVAILABLE` reserve a slot; the current connection, `SECOND_CHAT`,
`CLOSED` and pending/unconfirmed negotiations do not. With the default
60-minute window, a confirmed start at `20:00` blocks candidate starts from
`19:00` through `21:00`, inclusive, while `18:30` and `21:30` remain valid.

After mutual visual approval the backend creates a connection in
`SCHEDULING_PENDING`. This connection already counts against each participant's
active connection limit, but scheduling endpoints are not actionable until
`SchedulingActivationJob` moves it to `SCHEDULING_PHASE` and initializes the
negotiation. At that point the backend sends one privacy-safe
`SCHEDULING_AVAILABLE` push per participant group per activation job execution.
Notification taps should refresh/open Home for MVP; the payload is generic and
contains only `type=SCHEDULING_AVAILABLE`. Android may need a small follow-up if
it currently assumes `connectionId` or `matchId` are always present on this
notification type. In local profiles, if Home shows only
`SCHEDULING_PREPARING`/`hasPendingSchedulingConnection`, run the local
scheduling activation job before testing scheduling proposal or timeout flows.

Submitting one ordered scheduling proposal list sends
`SCHEDULING_PROPOSALS_RECEIVED` to the partner only, at most once per connection
and round. The visible text does not include participant identity or proposed
times; payload data includes `type`, `connectionId`, `matchId` and
`roundNumber`. If the submission immediately auto-confirms an overlap, the
proposal-received push is skipped.

When scheduling becomes confirmed, either by automatic overlap or explicit
acceptance, the backend sends `SCHEDULING_CONFIRMED` only to the participant who
did not perform the confirming action. The visible text does not include
participant identity or the confirmed time; payload data includes `type`,
`connectionId`, `matchId` and `availableAt`.

## Local Dev Tooling Endpoints

These endpoints are profile-gated tooling for controlled Bruno/manual testing:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`: manually process queued candidate pairs and start first chats.
- `POST /api/local-dev/jobs/{job}/run`: trigger supported background jobs.
- `POST /api/local-dev/timeouts/...`: move selected deadlines into the past for deterministic timeout testing.
- `POST /api/local-dev/matches/{matchId}/visual-review/make-available-now`: local-dev-only helper that moves an existing pending visual review's availability to server now and rebases its expiration from now. It does not create a review, change match state, decisions or reliability.
- `GET /api/local-dev/user-reliability/{userId}`: inspect the internal user reliability score breakdown and active contributing events without mutating state.

These endpoints execute system-level mutations and jobs. The `/api/local-dev/**`
path name is retained for compatibility and must never be called by the Android
application. In `local-nodb`, `local-postgres` and `local-firebase`, they are
available without authentication. In hosted `dev`, they are registered but
require `ROLE_ADMIN`; Firebase-authenticated users receive that role only under
the Firebase-token-email allowlist rules documented for admin endpoints. In
`prod`, the controllers are not registered and the route prefix is explicitly
denied.

Supported local job triggers:

- `POST /api/local-dev/jobs/scheduling-activation/run`
- `POST /api/local-dev/jobs/second-chat-reminder/run`
- `POST /api/local-dev/jobs/visual-review-reminder/run`
- `POST /api/local-dev/jobs/second-chat-lifecycle/run`
- `POST /api/local-dev/jobs/chat-timeout/run`
- `POST /api/local-dev/jobs/visual-phase-expiration/run`
- `POST /api/local-dev/jobs/match-expiration/run`
- `POST /api/local-dev/jobs/scheduling-timeout/run`
- `POST /api/local-dev/jobs/inactivity-check/run`
- `POST /api/local-dev/jobs/penalty-expiration/run`
- `POST /api/local-dev/jobs/user-reliability-cleanup/run`
- `POST /api/local-dev/jobs/account-deletion-finalization/run`
- `POST /api/local-dev/jobs/media-cleanup/run`

The second-chat confirmed time can be moved into the past for local testing with:

- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-late-window-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-before-hard-cutoff`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-past-hard-cutoff`

Second-chat read-only lifecycle can be tested locally with:

- `POST /api/local-dev/jobs/second-chat-lifecycle/run`
- `POST /api/local-dev/timeouts/chats/{chatId}/expire-now` to end the writable window.
- `POST /api/local-dev/timeouts/chats/{chatId}/read-only-expire-now` to end read-only retention.

Second-chat reminder push notifications can be tested locally with:

- `POST /api/local-dev/jobs/second-chat-reminder/run`

Second-chat start push notifications can be tested locally with:

- `POST /api/local-dev/jobs/second-chat-start-notification/run`

Visual-review reminder push notifications can be tested locally with:

- `POST /api/local-dev/jobs/visual-review-reminder/run`

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
- `EMAIL_NOT_VERIFIED`: profile activation, profile-photo upload/replacement, or legacy backend-account linking requires a verified email in the current Firebase ID token.
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
- `PROFILE_PHOTO_UPLOAD_BUSY`: profile-photo upload/replacement capacity is temporarily exhausted; retry after the response `Retry-After` value.
- `PROFILE_PHOTO_NOT_FOUND`: requested profile photo does not belong to the current profile.
- `USER_NOT_FOUND`: authenticated user id could not be locked for a state-changing operation.
- `CHAT_EXPIRED`: chat action was attempted after the absolute chat deadline.
- `VISUAL_CONTENT_NOT_AVAILABLE`: visual profile or personal-message content is not available for the current match/connection state.
- `CHAT_ABANDONED`: first-chat action was attempted after the inactivity deadline.
- `CHAT_MUTUAL_CANCELLATION_PENDING`: message sending, first-chat guidance advancement or first-chat continuation decision was attempted while a mutual cancellation request is pending.
- `FIRST_CHAT_DECISION_ONLY`: ordinary first-chat conversation or exit mutation was attempted while exactly one first-chat participant has approved and the other participant remains pending. Reads, polling, safety/report, manual block and the pending participant's final decision remain available.
- `FIRST_CHAT_GUIDANCE_PARTICIPATION_REQUIRED`: the requester has not yet sent enough persisted characters during the current first-chat guidance question interval.
- `FIRST_CHAT_GUIDANCE_NEXT_ALREADY_REQUESTED`: the requester already requested continuation for the current first-chat guidance question.
- `FIRST_CHAT_GUIDANCE_COMPLETED`: first-chat guidance already reached the active final configured question.
- `SCHEDULING_EXPIRED`: scheduling action was attempted after the negotiation deadline.
- `SCHEDULING_PROPOSAL_NOT_AVAILABLE`: scheduling proposal cannot be accepted because it is missing, not pending, from another connection, from an old round or its proposed instant is no longer strictly in the future.
- `SCHEDULING_SLOT_CONFLICT`: a proposed or selected second-chat start falls inside the configured inclusive conflict window around another confirmed second chat for the same user.
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
