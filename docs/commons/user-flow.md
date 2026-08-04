# User Flow

This document describes the current backend flow. It separates implemented behavior from future product ideas.

## 1. User And Profile

Local no-auth development can inject a fixed authenticated user through `DevAutoAuthFilter`. The default local Firebase profile and shared environments use Firebase-backed current-user resolution.

A user creates one profile. The profile starts as `DRAFT`; only `ACTIVE` profiles can enter matchmaking. Activation validates configured photo requirements.

Before profile creation, authenticated clients can fetch
`GET /api/reference/countries` to populate a country selector. The response is a
complete list of `{ "code", "displayName" }` entries built by the backend from
the Java runtime ISO country list with Spanish display names and immutable
in-memory state. Clients submit the selected `code` as profile `countryCode`.
The backend trims and uppercases valid alpha-2 codes before persistence and
rejects display names, alpha-3 codes, unknown codes and blank values. `city`
remains free text.

Profile trust-provider shortcuts are execution-profile aware. Outside `prod`,
provider `none` preserves MVP compatibility for local/dev/test flows: profile
authenticity verification may return `VERIFIED`, photo moderation may return
`APPROVED`, and
technical photo validation may produce `isPersonPhoto=true`,
`isFullBody=true` and `validationStatus=VALIDATED`. In `prod`, provider `none`
does not create positive trust facts: profile authenticity verification returns
`409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED`, photo moderation persists
`NEEDS_REVIEW`, and technical photo validation alone persists
`false`/`false`/`PENDING`. Successful image decoding is not semantic
person/full-body validation. Production activation defaults to requiring
moderation approval in addition to the existing validated/person/full-body photo
counts.

When `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` in `prod`, a technically
valid profile-photo upload or replacement runs one Sightengine multipart
request with the fixed models `face-analysis`, `nudity-2.1`, `violence`,
`gore-2.0` and `offensive-2.0`. In non-`prod`, Sightengine is disabled even if
configured and the backend uses the provider `none` compatibility path. Real
face presence is used only for the MVP `isPersonPhoto` field: at least one
`faces` entry counts as a person photo, `artificial_faces` do not count, and
`isFullBody` remains `false` because this provider path is not a full-body
detector. This does not perform profile authenticity verification, facial
recognition, face matching, liveness, legal identity verification, age
estimation or minor detection.
Mapped moderation signals can become `NEEDS_REVIEW` and are handled by the
admin review queue.

Profile Authenticity Verification is not legal identity verification. It is a
separate profile trust state whose future target is:

```text
liveness-derived live reference
+
provider-neutral facial comparison signals for current candidate person photos
```

The comparison candidate set is `validationStatus=VALIDATED` and
`isPersonPhoto=true`, sorted by profile-photo position. `isPersonPhoto` selects
comparison candidates; it does not prove that the detected person is the
verified user. Reals policy uses configurable positive and contradictory facial
evidence thresholds. The default MVP policy requires an accepted live reference,
at least 3 `MATCHED` candidate person photos and at most 0 `CONTRADICTORY`
candidate person photos. `MATCHED` is positive evidence, `UNRESOLVED` is
neutral and `CONTRADICTORY` is comparable facial evidence inconsistent with the
accepted live reference. Group photos can be `MATCHED` when at least one
comparable face matches the live reference, while non-person photos are excluded
from face comparison. Old, distant, side-profile, obscured or otherwise poor
comparisons may be `UNRESOLVED` and do not automatically invalidate the
profile. Strong contradictory evidence prevents automatic verification under
the default zero-contradiction policy, but it does not prove fraud and currently
produces `NEEDS_REVIEW`, not automatic `REJECTED`.

The current MVP only has a provider-neutral synchronous skeleton. The
Sightengine path's `isPersonPhoto` means at least one real face was detected; it
does not prove person consistency, facial authenticity or ownership. A body-only
image without a comparable visible face is not solved by this skeleton.
Uploading, replacing or deleting a profile photo invalidates previous
authenticity verification to `STALE` and sets `authenticityVerified=false`.
Reordering photos does not invalidate authenticity.

Photo moderation has a small admin human-review loop. Automated/provider
moderation can produce `APPROVED`, `REJECTED` or `NEEDS_REVIEW`.
`NEEDS_REVIEW` photos appear in `/api/admin/profile-photos/review` for admins
with `ROLE_ADMIN`; an admin can resolve them through
`POST /api/admin/profile-photos/{photoId}/moderation` as `APPROVED` or
`REJECTED` by submitting the `expectedPhotoVersion` returned by the queue item
they reviewed. If the photo changed after the queue was loaded, the resolution
returns `409 PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE`; the admin should
refresh and review the current photo again. This is a content-moderation
decision, not user-visible moderation scoring. It does not change semantic
validation fields such as `validationStatus`, `isPersonPhoto` or `isFullBody`.
Automatic provider moderation does not create child-safety reports, safety
reports, blocks, penalties, bans or account deletions.

Legal compliance is backend-authoritative for selected protected
participation/progression writes. After provisioning, clients can call
`GET /api/me/legal-status`; when `requirementsSatisfied=false`, they should
show the current legal requirements, use `GET /api/legal/documents/current` for
URL metadata as needed, submit the required factual actions with
`POST /api/me/legal-document-actions`, and refresh legal status. The Android
client may route based on this status, but backend guarded operations are the
enforcement boundary.

Guarded operations may return `409 LEGAL_ACTION_REQUIRED`. For future Android
clients, that stable code means refresh legal status and route to the legal
requirements UI. Legal state is not part of Home and is not modeled as a Home
pending action.

Reads, legal endpoints, individual text/audio chat message sends, account
deletion/reactivation, chat exit/cancellation and safety/reporting flows remain
available without current legal compliance. `APPROVED` first-chat and visual
decisions require compliance; `REJECTED` decisions remain available. Scheduling
proposal submission, proposal acceptance and partner scheduling-proposal
rejection require compliance.

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
- below active connection limit

Candidate pairs are processed by `MatchmakingProcessorService`, normally through `MatchmakingJob` in dev/prod or through the dev-only manual endpoint in local/Bruno flows. Candidate selection is delegated to `MatchmakingService.findNextCandidatePair`. The queue repository first returns up to `matchmaking.candidate-pair-limit` hard-filtered candidate pairs using active profiles, mutual gender preference, intention, mutual preferred age range, permanent user-block exclusion, active-pair exclusion and the configured previous-pairing cooldown. These SQL exclusions run before `LIMIT` so an ineligible historical pair cannot hide an eligible later pair. `MatchmakingService` then enforces mutual maximum distance from the search location captured when each user entered the queue, and `CompatibilityScorer` chooses the best remaining pair. Scores below `matchmaking.min-compatibility-score` are ignored; a score at or above `matchmaking.early-accept-compatibility-score` is accepted immediately; otherwise the highest score wins with FIFO order as the tie-breaker. Match creation is delegated to `MatchService.createMatch`, which pessimistically locks both active users in deterministic order, rechecks user blocks, active-pair uniqueness and historical cooldowns, then creates the match, creates locks and removes both users from the queue. `ChatService.startFirstChat` then creates the anonymous first chat.

Pair exclusion has three separate meanings:

- Active-pair uniqueness is always on, including local profiles. A pair cannot be matched again while they have `CHAT_ACTIVE`, `VISUAL_PHASE`, a `VISUAL_APPROVED` match without a connection, or any non-`CLOSED` connection.
- Previous-pairing cooldown is temporary and controlled by `matchmaking.exclude-previous-pairing`. It is enabled in dev/prod and disabled in local repeatable profiles. General terminal outcomes use `matchmaking.previous-pairing-cooldown-days` (30 by default); first-chat automatic timeout or inactivity abandonment uses `matchmaking.first-chat-expiration-cooldown-days` (7 by default).
- User blocks are permanent exclusions in either direction. Normal chat rejection, visual rejection, expiration, scheduling failure and connection closure do not create blocks.

Expired matches are classified by persisted phase evidence. `EXPIRED` with no `VisualReview` is first-chat expiration and uses the 7-day policy; `EXPIRED` with a `VisualReview` is visual-review expiration and uses the 30-day policy. First-chat automatic terminal metadata (`ChatStatus.EXPIRED`/`ABSOLUTE_TIMEOUT` or `ChatStatus.ABANDONED`/`INACTIVITY_TIMEOUT`) supplies `Chat.endedAt` when present, with `Match.updatedAt` as fallback for legacy or safety-net rows. Cooldowns are calculated from existing rows; there is no cleanup job or derived exclusion table.

## 3. First Chat

A new match starts in `CHAT_ACTIVE`. The first chat is created separately:

```text
ChatService.startFirstChat(matchId)
```

Messages can be sent only when the chat is active, not timed out, not abandoned
by inactivity and the sender belongs to the match. Sending a message updates
`Chat.lastMessageAt`.

Clients discover active first chats through `GET /api/me/home`. While a match is
in `CHAT_ACTIVE`, Home includes a `pendingActions[]` item with
`type = FIRST_CHAT`, the first-chat id and a partner summary (`userId`,
`profileId`, `displayName`) only if the chat is active, not expired and the
current user has not decided. When the match moves to `VISUAL_PHASE`, the first
chat action disappears. Expired or already-decided actions are not returned by
Home.

Home also returns `matchmaking`, `activeInteractionsSummary`, `nextSteps` and
`passiveNotices` so clients can render navigation without deriving actions from
raw `MatchState`, `ConnectionState` or expiration timestamps.

For `VISUAL_REVIEW` pending actions, both full Home and lightweight Home
pending responses include `visualStartedAt` and `visualExpiresAt` from the
persisted visual-review record. These fields are `null` for pending actions that
are not `VISUAL_REVIEW`.

`GET /api/matches/{matchId}/chat` returns the active first chat plus `partner`,
`myDecision`, `partnerDecision`, `expiresAt`, `inactivityExpiresAt`, `serverTime`
and nullable
`guidance` metadata. New first chats initialize guidance; legacy chats may have
`guidance = null`. The decision fields are API-facing statuses from the current
user's perspective: `PENDING`, `APPROVED`, `REJECTED` or `ABANDONED`.
Clients may use `serverTime` as an advisory backend clock snapshot for first-chat
countdown and suggestion UX. The backend remains authoritative for all mutations
and expiration decisions, and this field does not make the backend responsible
for suggestion visibility or local dismissal.

First-chat guidance is an MVP conversation prompt mechanic owned by the backend.
When a new first chat is created, the backend persists the complete prompt
sequence as immutable snapshots. Affinity-derived prompts use the active Spanish
affinity catalog and the pair's valid answers visible at that moment; remaining
slots use the deterministic generic Spanish catalog sequence from the chat id.
One active question is shared by both participants and copied into
`first_chat_guidance`.
Users can chat freely; the backend does not semantically evaluate answers. A
participant can request another question only after sending at least the
configured `chat.first-chat.guidance.required-characters` threshold during the
current question interval. One long message can satisfy this threshold. The
question advances only after both
participants independently request it, and partner readiness/request state is not
exposed. A first chat has at most 3 questions. When both users request
continuation from the penultimate question and the final configured question
becomes active, guidance completes immediately, no question 4 is selected, and
the final question remains available as the final prompt. Clients observe
question changes through the existing first-chat polling response. Later
affinity-answer edits/deletions or catalog wording changes do not alter active
or future prompts in that first chat. Legacy chats without prompt snapshots keep
their persisted active guidance question and advance with generic deterministic
fallback. No analytics events are implemented for guidance.

For compatibility with rows created before this completion semantics change, a
guidance row already at the final configured ordinal with `completedAt = null`
is treated as completed. The backend normalizes `completedAt` and clears
next-request timestamps when that row is read or mutated.

Client countdowns are advisory. The backend remains the source of truth and
rejects first-chat mutations with `CHAT_EXPIRED` after the absolute deadline or
`CHAT_ABANDONED` after the inactivity deadline.

Message polling can use `GET /api/chats/{chatId}/messages` for the initial legacy full list, then `GET /api/chats/{chatId}/messages?after={messageId}` or `afterMessageId={messageId}` for incremental responses shaped as `{ "messages": [...], "hasMore": false, "serverTime": "..." }`.

Audio messages use the same polling stream. First-chat audio unlocks from shared guidance progress, not per-user counters: `answeredGuidanceQuestions = max(currentQuestionOrdinal - 1, 0)`. Local and test unlock when question 2 becomes active (`requiredAnsweredGuidanceQuestions = 1`); dev/prod unlock when question 3 becomes active (`requiredAnsweredGuidanceQuestions = 2`). With the current three-question flow, dev/prod unlock after both users answered the penultimate question. Legacy first chats without guidance keep audio unavailable. Each participant may create one first-chat audio message, and idempotent replay of the already-created message does not consume another slot.

## 4. Chat Decision

Each user can approve continuation, request mutual cancellation or cancel explicitly.

- Mutual `APPROVED`: first chat becomes `FINISHED`, match moves to `VISUAL_PHASE`, visual review is initialized.
- `REJECTED` is treated as unilateral cancellation: first chat becomes `CANCELLED`, match moves to `CHAT_REJECTED`, locks are released and penalty policy is evaluated.
- Mutual cancellation request accepted by the other participant cancels the chat without penalty.
- Mutual cancellation request rejected by the other participant also cancels the chat. Future scoring may apply a lower penalty to the requester, but no penalty is applied today.
- Mutual cancellation request timeout is resolved by a client call after `chat.exit-request.mutual-timeout-seconds`; it cancels the chat without penalty. This is not a unilateral cancellation, and the requester must not be penalized for resolving an unanswered request.
- Safety cancellation cancels the chat, records `ChatEndReason.SAFETY_REPORT`, exempts the reporter, creates a pending `SafetyReport` and creates a directional block from reporter to reported. `CHILD_SAFETY_CONCERN` is preserved explicitly from the accepted exit request to the report reason. It is a reported concern, not a confirmed violation, and does not penalize the reported participant until admin/backoffice review confirms the report.

Approval still requires both users. Cancellation can end the chat earlier through mutual acceptance, mutual rejection, mutual timeout, unilateral cancellation or safety cancellation.

## 5. Visual Review

Each user submits one `VisualDecision`.

- The deciding user's visual review disappears from Home and that user's match lock is released immediately.
- Repeating the same decision is idempotent. Trying to change a recorded decision is rejected.
- If one user rejects while the other has not decided, the match remains in `VISUAL_PHASE` for the pending participant. This avoids an immediate rejection signal through Home.
- When both users have decided, mutual `APPROVED` moves the match to `VISUAL_APPROVED` and creates a pending connection.
- When both users have decided and at least one rejected, the match moves to `VISUAL_REJECTED` and remaining match locks are released.

Personal messages are optional and stored on `VisualReview`. Current behavior
allows reading the partner message during visual review once it exists. Reading
is encouraged and unread messages are exposed to clients for visual emphasis,
but reading is not required before submitting `APPROVED` or `REJECTED`. Opening
an existing partner message persists the requester-specific read timestamp. A
successful optional personal-message submission also records a small
backend-internal reliability participation event when user reliability is
enabled. Reading the partner message does not create a reliability event.
`decisionRequiresPartnerPersonalMessageRead` is retained temporarily for
client-compatible response shape and is always `false`.

Match and visual-profile responses expose `visualExpiresAt` so clients can warn
before the visual phase expires. Home `VISUAL_REVIEW` pending actions expose
`visualStartedAt` and `visualExpiresAt` for the currently actionable phase. New
visual decisions after that deadline are rejected by the backend.

The visual-profile response also includes required `affinityIndicators`. It is
empty when the first-chat initialization evidence had no eligible positive
shared `STANDARD` category. Otherwise it contains at most three snapshotted
positive category `{categoryId, title}` values. Constructive contrast can drive
a first-chat prompt but never creates a visual indicator. Exact answers, answer
labels, question ids, scores, percentages and compatibility judgments are not
returned.

Visual-profile and visual personal-message content is also request-time guarded.
During `VISUAL_PHASE`, the visual review must exist and the server clock must
still be before `visualExpiresAt`; the scheduler does not have to run first for
expired content to be denied. During `VISUAL_APPROVED`, the backend requires an
existing non-closed connection for the match and requester. `CHAT_ACTIVE`,
`CHAT_REJECTED`, `VISUAL_REJECTED` and `EXPIRED` matches do not expose visual
content. Blocked pairs are denied, and denied partner-message reads do not set
read timestamps.

Visual-review reminder eligibility is persisted as `VisualReview.reminderEligibleAt`
when the visual review is created. The default configuration makes the reminder
eligible when 40% of the visual-review duration remains. The backend no longer
sends an immediate visual-review availability push; the reminder job sends only
to users whose own visual decision remains pending and deduplicates delivery per
user and match.

## 6. Connection Creation

`ConnectionService.createFromMatch(match)` creates a connection after visual approval.

It validates active connection limits and creates `CONNECTION` locks immediately. A connection starts in `SCHEDULING_PENDING` with `schedulingAvailableAt`; it occupies connection capacity but is not yet actionable in Home. `SCHEDULING_PENDING` is a deferred activation state, not a user-driven coordination state.

## 7. Scheduling

Scheduling is activated later by `SchedulingActivationJob` when
`schedulingAvailableAt <= now`. The job moves the connection to
`SCHEDULING_PHASE` and initializes negotiation idempotently. Until then, Home
does not include the connection in `nextSteps`; clients can see it only through
`activeInteractionsSummary.hasPendingSchedulingConnection` and one generic
count-free passive notice `SCHEDULING_PREPARING`. This intentionally does not
expose the exact number of internal pending scheduling connections.
Production runs the activation job on a six-hour fixed-delay cadence by default;
base/dev profiles keep a one-minute cadence, and local profiles keep scheduler
execution manual.

`SchedulingNegotiationTimeoutJob` applies only after activation, while the
connection is in `SCHEDULING_PHASE`. The `schedulingExpiresAt` value created
with `SCHEDULING_PENDING` is provisional; activation recalculates the actionable
deadline from the actual activation time. In local profiles, where
schedulers are disabled, run `SchedulingActivationJob` manually before testing
scheduling proposals or scheduling timeout.

Scheduling proposal submissions and confirmations emit after-commit push events.
`SCHEDULING_PROPOSALS_RECEIVED` goes only to the partner for one connection and
round, and is skipped when the submission immediately confirms an overlap.
`SCHEDULING_CONFIRMED` goes only to the non-triggering participant. Both visible
messages are privacy-safe and omit identities and times.

Once active, users submit ordered lists of future date/time proposals for the second chat inside the app. This is not the same as scheduling an in-person meeting; any real-world meeting is outside the backend's current scope.

Rules:

- each user submits one proposal list per round
- each list must contain 1 to `scheduling.max-proposals-per-round` unique future slots
- slots must be aligned to half-hour boundaries
- proposal submission includes `expectedRoundNumber`; stale round requests return `SCHEDULING_ROUND_CHANGED`
- user must belong to the connection
- user cannot accept their own proposal
- a participant can accept a partner proposal without first submitting their own list
- receiving partner proposals does not make backend submission invalid; clients should preferably review known received proposals before showing their own selector, but that review-first rule is UX rather than a backend precondition
- explicit acceptance requires the partner proposal instant to remain strictly in the future; expired pending proposals return `SCHEDULING_PROPOSAL_NOT_AVAILABLE`
- overlapping currently `PENDING` proposed instants auto-confirm only when the overlapping instant is still in the future; rejected lists and expired overlaps never participate in confirmation
- proposals and confirmations must not conflict with another confirmed second-chat start for the same user while that other connection is `SECOND_CHAT_SCHEDULED` or `SECOND_CHAT_AVAILABLE`; the default conflict window is 60 minutes before through 60 minutes after the confirmed start, inclusive

If more than one pending future slot overlaps, the backend checks overlaps in lowest combined preference order, then earliest agreed instant, and confirms the first overlap available to both users. If overlaps exist but every overlap conflicts with another confirmed second chat, the submission returns `SCHEDULING_SLOT_CONFLICT` and the triggering submission is rolled back. Accepting any pending future partner proposal confirms immediately only after the same conflict check. If there is no usable overlap after both users submit, the backend does not immediately open the next round. Proposals remain visible so either participant can accept one future partner slot or explicitly reject the partner's pending proposal list.

Clients may visually identify proposal options whose proposed instants have passed and should not offer acceptance for them. Backend validation remains authoritative because a proposal can expire between rendering and the acceptance request. Expired pending proposals are not automatically converted to `REJECTED` and do not automatically advance a round.

Partner proposal rejection resolves only the partner's pending proposals. A single rejection does not end the round. The round advances only when both participants have submitted at least one proposal in that round and no proposal in the round remains `PENDING`; at that point the backend opens the next round or fails/closes on the final permitted round. Scheduling mutations are serialized per negotiation through a database write lock on the negotiation row.

Confirmation marks the negotiation as `CONFIRMED`, stores `confirmedDateTime` as the agreed second-chat start time, moves the connection to `SECOND_CHAT_SCHEDULED` and initializes `PENDING` participation rows for both users.

If max rounds are exceeded or scheduling expires, the negotiation becomes `FAILED` and the connection closes.

Negotiation responses expose the parent connection's `schedulingExpiresAt` so
clients can warn before the scheduling phase expires. Scheduling mutations after
that deadline are rejected by the backend; the timeout job performs the
persistent failed/closed transition when it runs.

## 8. Second Chat

Second chat entry is explicit. `POST /api/connections/{connectionId}/second-chat/join`
validates the authenticated participant, confirmed negotiation and server time,
creates or activates the `SECOND_CHAT` if needed, and moves the connection to
`SECOND_CHAT`:

```text
SecondChatLifecycleService.joinSecondChat(connectionId, userId)
```

Home exposes the agreed start time as `nextSteps[].secondChat.availableAt` for
`SECOND_CHAT_SCHEDULED`. The status endpoint returns `scheduledAt`,
`onTimeUntil`, `entryClosesAt`, `absoluteExpiresAt`, `serverTime`, both
attendance statuses and any active no-show claim. `GET
/api/connections/{connectionId}/chat`, Home loads, polling and message fetches
are side-effect free and do not count as attendance.

After the scheduled start, the backend may send one `SECOND_CHAT_STARTED` push
per participant who has not joined yet. The default delivery window is
`confirmedDateTime <= now <= confirmedDateTime + 5 minutes`; stale missed runs
after that window do not initiate new start notifications. The default job
cadence is 4 minutes, leaving slack inside the five-minute send window. Users
who already joined are recorded as handled and are not sent to. Android FCM uses
the same `second-chat-<connectionId>` tag for the reminder and start push, so
the start push can replace the reminder in background notification display.
Opening the notification lands on Home, whose second-chat `nextSteps` are
ordered by current/available chats first, then nearest scheduled future starts,
then scheduling work, then read-only prior chats.

Arrival windows are exact: `confirmedDateTime <= now < confirmedDateTime + 10
minutes` is `ON_TIME`; `confirmedDateTime + 10 minutes <= now <
confirmedDateTime + 20 minutes` is `LATE`; `now >= confirmedDateTime + 20
minutes` closes entry and unresolved absences become `NO_SHOW`. Join retries
preserve the original `joinedAt` and classification. When both participants
have joined, `conversationStartedAt` is set once to the later joined time.
`timeoutAt` remains `confirmedDateTime + chat.second-chat.duration-minutes`
(currently 120 minutes).

Second-chat audio remains unavailable until both participants have joined and
`conversationStartedAt` exists. The unlock instant is
`conversationStartedAt + chat.second-chat.mutual-completion.minimum-conversation-minutes`.
Boundary semantics are exact: `now < audioEnabledAt` is unavailable with
`WAITING_DELAY`; `now >= audioEnabledAt` is available, subject to the global
feature flag, file validation, rate limits and normal writable lifecycle. Once
unlocked, second-chat audio messages are unlimited in quantity.

After `confirmedDateTime + 10 minutes` and before `confirmedDateTime + 20
minutes`, a joined participant may create one pending partner no-show claim per
connection. The countdown is 60 seconds, capped at the hard cutoff. If the
partner joins before expiry, the claim is cancelled and the partner is
classified as `LATE`. If it expires first, the partner is marked `NO_SHOW`, the
no-show reliability event is recorded once and the interaction is closed to
read-only when a chat exists.

At the hard cutoff, if one participant joined, only the absent participant is
marked `NO_SHOW`. If neither joined, both are marked `NO_SHOW`, the connection
closes directly and no empty chat is created. If both joined, no no-show action
is taken and the chat remains active.

`SecondChatLifecycleJob` owns the lifecycle after scheduling confirmation. A
no-show with an existing chat sets `ABANDONED / SECOND_CHAT_NO_SHOW`, `endedAt`
and `readOnlyUntil`; messages remain readable during retention and new messages
are rejected. Once both participants have joined, mutual completion can be
requested after 10 minutes only if each participant has sent a message. Accepted
completion sets `FINISHED / SECOND_CHAT_MUTUAL_COMPLETION`; rejection, timeout
and new-message cancellation keep the chat active and start a one-minute
cooldown for the requester.

Partner inactivity is based on `lastMessageAt`, `lastMessageSenderId` and
`conversationStartedAt`. A participant may send a waiting message before the
partner joins; that message remains the latest reference message, but the
response clock starts at `max(lastMessageAt, conversationStartedAt)`, not before
both users joined. The latest-message author may claim after five minutes and
before the ten-minute automatic closure deadline; the countdown is 60 seconds
and cannot extend past automatic inactivity or absolute timeout. Any new conversational message before
expiry cancels the claim and becomes the new inactivity clock. At exact expiry,
the silent participant is penalized and the chat becomes `ABANDONED /
SECOND_CHAT_PARTNER_INACTIVITY`. If both users joined but neither sent any
message by `conversationStartedAt + 10 minutes`, both receive the initial-silence
penalty and the chat becomes `ABANDONED /
SECOND_CHAT_NO_CONVERSATION_STARTED`.

Status polling uses the same effective response clock to suppress invalid
conversation actions when initial silence or partner inactivity is already due,
but it does not persist terminal state. Active second-chat absolute timeout still
sets `EXPIRED / ABSOLUTE_TIMEOUT` and is reliability-neutral. `FINISHED`, `ABANDONED` and `EXPIRED` are read-only until
retention cleanup; when `readOnlyUntil` is reached, the same job marks the chat
`CLOSED`, closes the connection and releases locks. Ordinary second-chat mutual
and unilateral cancellation are unavailable; safety report and manual block
remain available. First-chat timeout and cancellation semantics are unchanged.

## 9. Safety Report Review

Safety-report chat closure creates:

- an accepted `ChatExitRequest` with `type = SAFETY_REPORT`, used as operational chat-closure history;
- a `SafetyReport` with `status = PENDING`, used as the moderation source of truth.
- a directional `UserBlock` from reporter to reported; matchmaking treats any block between two users as a bidirectional rematch exclusion.

Admins access `/api/admin/safety-reports` with `ROLE_ADMIN`. Dismissing a pending report stores review metadata and creates no penalty. Confirming a pending report creates either a temporary or permanent penalty for the reported user, links it through `sourceReportId`/`penaltyId`, removes the reported user from the matchmaking queue if present and blocks future enqueue while the penalty remains active.

Pending reports with reason `CHILD_SAFETY_CONCERN` receive derived priority review: admin lists order them before other reports, then order by creation time descending. Priority is not persisted and becomes false after review. Direct user reports retain the existing directional block and active-interaction containment behavior; no penalty or ban is automatic from this reason.

Admins can also dismiss a report as abusive or unjustified. That resolution creates no safety penalty; when user reliability is enabled, it records an internal reliability event against the reporter. Pending reports, ordinary insufficient-evidence dismissals and confirmed reports against the reported user do not create reliability events.

## 10. Completion

A connection eventually reaches `CLOSED`. Closure releases active connection locks, so users are no longer counted against the connection limit for that interaction.

## 11. Manual block

A participant may submit `POST /api/matches/{matchId}/block`; the backend resolves the counterpart without exposing their user id. The block immediately excludes the pair and contains every active match or connection. Positive progression then returns `USER_PAIR_BLOCKED`, while reads, rejection, exit, cancellation, and safety remain available. Android must present definitive-action confirmation before submission; Android UI and unblock are not part of this backend MVP.
