# Domain Model

The domain is state-driven and anonymous-first. Business transitions are validated in services, not encoded in controllers or repositories.

## Entities

- `User`
- `Profile`
- `ProfilePhoto`
- `AffinityQuestionAnswer`
- `ProfileQuestionAnswer`
- `MatchmakingQueueEntry`
- `Match`
- `Chat`
- `ChatMessage`
- `FirstChatGuidance`
- `ChatDecision`
- `ChatExitRequest`
- `SafetyReport`
- `SafetyReportEvidenceSnapshot`
- `AuditEvent`
- `VisualReview`
- `Connection`
- `ScheduleNegotiation`
- `ScheduleProposal`
- `Penalty`
- `ActiveEngagementLock`
- `PushDeviceToken`
- `PushNotificationDelivery`
- `UserLegalDocumentAction`

## Main Enums

Profile:

- `Gender`: `MALE`, `FEMALE`, `NON_BINARY`, `OTHER`
- `lookingForGenders`: non-empty set of `Gender` values representing which genders the user wants to meet.
- `Intention`: `DATE`, `FRIENDSHIP`, `CASUAL`
- `ProfileStatus`: `DRAFT`, `ACTIVE`, `INACTIVE`
- `ProfileAuthenticityVerificationStatus`: `NOT_STARTED`, `PENDING`, `VERIFIED`, `REJECTED`, `NEEDS_REVIEW`, `STALE`

Affinity questions:

- private closed-choice answers used for compatibility, matchmaking and first-chat context. Exact affinity answers are not exposed to counterparts.
- `AffinityAnswerType`: `SINGLE_CHOICE`, `ORDINAL_SCALE`
- `AffinityQuestionStatus`: `ACTIVE`, `DEPRECATED`
- `AffinityConstruct`: `DOMAIN_ENGAGEMENT`, `TASTE_PREFERENCE`, `SHARED_ACTIVITY_ORIENTATION`, `VALUES_ALIGNMENT`, `LIFESTYLE_ALIGNMENT`, `RELATIONAL_EXPECTATION`, `DIFFERENCE_TOLERANCE`, `SALIENCE_ALIGNMENT`, `CONVERSATION_ONLY`
- `AffinitySensitivity`: `STANDARD`, `SENSITIVE_LOW_RANKING`
- `ConversationKind`: `SHARED_AFFINITY`, `CONSTRUCTIVE_CONTRAST`, `NEUTRAL`, `NOT_ELIGIBLE`


Profile questions:

- free-text optional answers owned by the profile and backed by a dedicated
  versioned catalog, not by affinity entities or answer tables.
- saved answer count is limited only by active catalog questions; public display
  is limited to three selected current answers.
- `questionSemanticVersion` is stored on create/update. A semantic-version
  mismatch makes an answer stale: it remains visible to the current user as
  private data, cannot be selected and is omitted from visual-profile output.
  Content-version-only catalog changes do not invalidate answers.
- selected positions are explicit and contiguous in public responses. Selection
  replacement is a full ordered operation protected by the profile row lock.

Matching and chat:

- `MatchState`: `CHAT_ACTIVE`, `VISUAL_PHASE`, `VISUAL_APPROVED`, `CHAT_REJECTED`, `VISUAL_REJECTED`, `EXPIRED`
- `ChatType`: `FIRST_CHAT`, `SECOND_CHAT`
- `ChatStatus`: `AVAILABLE`, `ACTIVE`, `FINISHED`, `CANCELLED`, `EXPIRED`, `ABANDONED`, `CLOSED`
- `ChatEndReason`: `MUTUAL_CANCEL`, `UNILATERAL_CANCEL`, `SAFETY_REPORT`, `USER_BLOCK`, `FIRST_CHAT_DECISION_MISMATCH`, `ABSOLUTE_TIMEOUT`, `INACTIVITY_TIMEOUT`, `SECOND_CHAT_NO_SHOW`, `SECOND_CHAT_MUTUAL_COMPLETION`, `SECOND_CHAT_PARTNER_INACTIVITY`, `SECOND_CHAT_NO_CONVERSATION_STARTED`, `SECOND_CHAT_READ_ONLY_EXPIRED`, `USER_DELETED`, `SYSTEM_CLOSED`
- `ChatContinueDecision`: `APPROVED`, `REJECTED`
- `ChatParticipantDecisionStatus`: `PENDING`, `APPROVED`, `REJECTED`, `ABANDONED` (API-facing status derived from chat decisions and terminal chat outcomes)
- `ChatReplyTargetType`: `MESSAGE`, `GUIDANCE_QUESTION`
- `ChatExitRequestType`: `MUTUAL_CANCEL`, `UNILATERAL_CANCEL`, `SAFETY_REPORT`
- `ChatExitRequestStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `TIMED_OUT`
- `ChatExitReason`: `NO_LONGER_INTERESTED`, `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `CHILD_SAFETY_CONCERN`, `OTHER`
- `SafetyReportStatus`: `PENDING`, `DISMISSED`, `DISMISSED_ABUSIVE_OR_UNJUSTIFIED`, `CONFIRMED`
- `SafetyReportReason`: `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `CHILD_SAFETY_CONCERN`, `OTHER`
- `SafetyReportContextType`: `CHAT`, `VISUAL_PROFILE`, `PERSONAL_MESSAGE`, `PROFILE_PHOTO`, `USER`
- `SafetyReportSource`: `USER`, `ADMIN`, `SYSTEM`
- `AuditEventType`: `SAFETY_REPORT_CREATED`, `SAFETY_REPORT_DISMISSED`, `SAFETY_REPORT_CONFIRMED`, `USER_BLOCK_CREATED`, `CHAT_ENDED`, `PROFILE_PHOTO_UPLOADED`, `PROFILE_PHOTO_REPLACED`, `PROFILE_PHOTO_DELETED`, `PROFILE_PHOTOS_REORDERED`, `PROFILE_ACTIVATED`, `PHOTO_MODERATION_UPDATED`, `PROFILE_AUTHENTICITY_VERIFICATION_UPDATED`, `ACCOUNT_DELETION_REQUESTED`, `ACCOUNT_DELETION_FINALIZED`, `ACCOUNT_REACTIVATED`, `PENALTY_APPLIED`, `LEGAL_DOCUMENT_ACTION_RECORDED`
- `AuditAggregateType`: `USER`, `PROFILE`, `PROFILE_PHOTO`, `CHAT`, `MATCH`, `CONNECTION`, `SAFETY_REPORT`, `USER_BLOCK`, `PENALTY`
- `LegalDocumentType`: `TERMS_OF_USE`, `PRIVACY_NOTICE`, `COMMUNITY_GUIDELINES`
- `LegalDocumentAction`: `ACCEPTED`, `ACKNOWLEDGED`
- `UserReliabilityEventType`: `FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION`, `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE`, `FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION`, `FIRST_CHAT_EARLY_UNILATERAL_CLOSE`, `FIRST_CHAT_PARTIAL_UNILATERAL_CLOSE`, `FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE`, `FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED`, `FIRST_CHAT_RESPONSIBLE_CLOSE_REQUEST_RESOLVED`, `FIRST_CHAT_EXPIRED_NO_DECISION`, `VISUAL_REVIEW_EXPIRED_NO_DECISION`, `VISUAL_PERSONAL_MESSAGE_SUBMITTED`, `SCHEDULING_SLOTS_PROPOSED_ON_TIME`, `SCHEDULING_EXPIRED_NO_PROPOSAL`, `SCHEDULING_EXPIRED_ACTION_PENDING`, `SECOND_CHAT_CONFIRMED_ATTENDED`, `SECOND_CHAT_LATE_ARRIVAL`, `SECOND_CHAT_NO_SHOW`, `SECOND_CHAT_MUTUAL_COMPLETION`, `SECOND_CHAT_ABANDONED_AFTER_JOIN`, `SECOND_CHAT_NO_CONVERSATION_STARTED`, `SAFETY_REPORT_DETERMINED_ABUSIVE`
- `PhotoValidationStatus`: `PENDING`, `VALIDATED`, `FAILED`
- `PhotoModerationStatus`: `PENDING`, `APPROVED`, `REJECTED`, `NEEDS_REVIEW`
- `PenaltyType`: `TEMPORARY_BAN`, `PERMANENT_BAN`
- `VisualDecision`: `APPROVED`, `REJECTED`

Connection and scheduling:

- `ConnectionState`: `SCHEDULING_PENDING`, `SCHEDULING_PHASE`, `SECOND_CHAT_SCHEDULED`, `SECOND_CHAT_AVAILABLE`, `SECOND_CHAT`, `CLOSED`
- `NegotiationStatus`: `PENDING`, `CONFIRMED`, `FAILED`
- `ProposalStatus`: `PENDING`, `ACCEPTED`, `REJECTED`

`SCHEDULING_PENDING` is created after mutual visual approval. It counts as an
internal active connection for capacity/locks and has `schedulingAvailableAt`,
but it is not visible as a revealed Home connection or actionable until a
scheduling activation job moves it to `SCHEDULING_PHASE` and initializes
negotiation. Home surfaces this state through the boolean
`activeInteractionsSummary.hasPendingSchedulingConnection` and one generic
count-free passive notice `SCHEDULING_PREPARING`, not through `nextSteps`. The
boolean is intentionally not an exact count of internal pending scheduling
connections.

Scheduling proposals represent second-chat slots inside the app. They do not represent in-person meeting times. A proposal row stores one possible slot, its `roundNumber` and its `preferenceOrder` within the user's submitted list. Proposal submission requires future slots, but a persisted `PENDING` proposal may later become temporally unavailable when its proposed time passes. Expired pending proposals remain visible and rejectable; they are not automatically changed to `REJECTED` and do not automatically advance the round. Explicit acceptance and automatic overlap confirmation consider only proposal instants that are still strictly in the future. Each participant can submit at most one ordered list per round. Rejection is represented by proposal `status = REJECTED`: rejecting partner proposals changes only the partner's pending rows, does not delete historical rows and does not reject the caller's own list. Rejected proposals never participate in overlap auto-confirmation. At scheduling expiry, reliability responsibility is derived only from the current negotiation round: a user is pending if they have not submitted in the current round, or if their partner has current-round `PENDING` proposals still available for that user to accept or reject. Historical proposals from older rounds do not satisfy current-round responsibility. If both users resolved all required current-round actions and no agreement was found, expiry does not add an abandonment event. A confirmed negotiation schedules the second chat for `confirmedDateTime`; while the connection is `SECOND_CHAT_SCHEDULED` or `SECOND_CHAT_AVAILABLE`, that confirmed instant reserves a symmetric inclusive scheduling conflict window for both users. The current connection, `SECOND_CHAT`, `CLOSED` and pending/unconfirmed negotiations do not reserve slots. With the default 60-minute window, a confirmed start at `20:00` blocks starts from `19:00` through `21:00`, inclusive. Explicit join materializes and activates the `SECOND_CHAT` idempotently when `confirmedDateTime <= now < confirmedDateTime + 20 minutes`. Participation rows are `PENDING`, `ON_TIME`, `LATE` or `NO_SHOW`. Resolution requests share one table for `PARTNER_NO_SHOW`, `MUTUAL_COMPLETION` and `PARTNER_INACTIVITY`, with at most one `PENDING` request per connection. Conversation lifecycle starts only after both users joined and `conversationStartedAt` is set to the later join time. `lastMessageAt` plus `lastMessageSenderId` is the conversational inactivity clock. `FINISHED`, `ABANDONED` and `EXPIRED` second chats remain read-only until retention cleanup. Ordinary unilateral and mutual cancellation are unavailable for second chats; use mutual completion, inactivity/no-show lifecycle, safety report or user block instead.

Chat messages are one ordered stream. `ChatMessage.messageType` is `TEXT` for legacy and text rows, and `AUDIO` for audio rows. Text rows require non-null `content` and have no audio metadata; `clientMessageId` is nullable for legacy compatibility and non-null TEXT sends are idempotent. Audio rows require `clientMessageId`, private storage bucket/key, content type, positive size, positive duration and SHA-256; they require `content = null`. The uniqueness scope for TEXT and AUDIO idempotency is the shared `(chatSessionId, senderId, clientMessageId)` partial unique index when `clientMessageId` is non-null. Exact replay equality includes the direct reply target and message type: TEXT compares normalized content plus direct target, while AUDIO compares accepted audio SHA-256 plus direct target. Reusing the same key with different semantic payload returns `CHAT_MESSAGE_IDEMPOTENCY_CONFLICT`. `replyToMessageId` and `replyToPromptSnapshotId` are nullable normalized direct reply targets with a single-target check; the row never stores duplicated quoted text, display names, JSON previews or nested reply chains. First-chat audio quota is enforced under the chat row lock by counting `AUDIO` rows for that sender, so second-chat audio remains unlimited.

Message reactions are metadata on `chat_messages`. The MVP stores one nullable `reactionType`, currently `null` or `HEART`; there is no separate reaction row, reactor id, timestamp, count, removal or toggle. Only the other participant's `TEXT` or `AUDIO` messages can receive a new `HEART`, and only while the target is in the authenticated user's latest incoming block ordered by `(sentAt, id)`: after the user's latest own message strictly before the latest incoming message, through that latest incoming message. Own messages after that latest incoming block do not invalidate it; a newer incoming block supersedes previous unreacted incoming messages. An existing `HEART` remains visible and idempotently returnable after its block expires or the chat becomes read-only.

A quoted TEXT or AUDIO message is still exactly one conversational message: it updates `lastMessageAt`, updates `lastMessageSenderId` for second chats, counts in generic message counts and participant-message checks, and cancels pending second-chat mutual-completion or partner-inactivity requests through the same pre-message lifecycle path. The referenced target itself creates no activity. Quoted TEXT may contribute authored content points to first-chat guidance according to the direct-target scoring rules; AUDIO contributes zero, and guidance score aggregation counts only authored `TEXT` rows with non-null content.

A message reaction is not conversational: it does not update message ordering, `sentAt`, `lastMessageAt` or `lastMessageSenderId`; does not reset inactivity windows or extend deadlines; does not affect first-chat or second-chat state-machine progression; and does not create reliability, penalty, scheduling, notification, Home invalidation or chat-message side effects.

Audio storage reuses the private S3-compatible media bucket. Object keys are `chats/{chatId}/messages/{messageId}.m4a` and contain no names, emails or profile data. Persisted rows store bucket/key and immutable metadata, never presigned URLs. Read serialization signs the persisted bucket/key, not whatever bucket is currently configured. Orphan protection uses the durable media-cleanup guard task before upload and deletes that guard in the same transaction that persists the message. Feature-flag disablement, read-only chat state, soft deletion and terminal interaction states do not delete existing audio; age-based audio deletion remains deferred product/technical work.

Safety evidence counts audio messages in transcript totals. Existing text hash input remains exactly `messageId|senderId|sentAt|content`. Audio hash input is distinct and includes type, id, sender, sentAt, duration and audio SHA-256. Evidence never hashes or exposes presigned URLs, bucket, object key, raw audio bytes, transcript text, waveform data or mutable display data.

Engagement:

- `EngagementType`: `MATCH`, `CONNECTION`

User:

- `UserStatus`: `ACTIVE`, `DELETED`
- `UserAuthOrigin`: `EMAIL_PASSWORD`, `GOOGLE`. Once non-null, this records the
  first successful Reals provisioning flow for that backend user row and is
  immutable; it is not recomputed from Firebase provider ordering or later
  provider-link metadata.
  `EMAIL_PASSWORD` accounts may authenticate with Firebase `password` or
  `google.com` tokens for the same UID. `GOOGLE` accounts may authenticate only
  with Firebase `google.com` tokens. A finalized deleted backend row preserves
  the historical origin; a later start-over flow creates a new backend user.

Push notifications:

- `PushPlatform`: `ANDROID`
- `PushNotificationType`: `MATCH_FOUND`, `MATCH_FOUND_INVALIDATED`, `VISUAL_REVIEW_AVAILABLE` (historical only), `VISUAL_REVIEW_REMINDER`, `SCHEDULING_AVAILABLE`, `SCHEDULING_PROPOSALS_RECEIVED`, `SCHEDULING_CONFIRMED`, `SECOND_CHAT_REMINDER`, `SECOND_CHAT_STARTED`
- `PushDeliveryStatus`: `SENT`, `SKIPPED_NO_ACTIVE_TOKEN`, `SKIPPED_ALREADY_JOINED`, `FAILED`

## Relationships

- A `User` may have one `Profile`.
- A `Profile` has many `ProfilePhoto` records.
- A `Profile` has many private `AffinityQuestionAnswer` records, unique by `(profileId, questionId)`. Answers are owned by the current user's profile and are never exposed through counterpart-facing profile, match, visual-review, first-chat, Home or partner-summary responses.
- A `Match` has `userAId` and `userBId`.
- A `Chat` belongs to a `Match`; `SECOND_CHAT` also has `connectionId`.
- `FirstChatGuidance` belongs to one `FIRST_CHAT` through a unique `chatId`. It stores the active question id/text snapshot, ordinal, activation timestamp, per-participant next-question request timestamps and optional completion timestamp. It does not store message counters.
- `ConversationPromptSnapshot` belongs to one first chat and stores the complete immutable prompt sequence by `(chatId, ordinal)`. Affinity rows snapshot source question id, semantic version, prompt text, category and conversation kind; generic rows snapshot only generic source id/text. No answer code, answer label, ranking contribution, score, percentage, confidence or affinity factor is stored.
- `ChatDecision` belongs to a chat and match.
- `ChatExitRequest` records mutual cancellation requests, unilateral cancellations and safety-report chat closures.
- `SafetyReport` is the moderation source of truth for reported safety incidents. It stores an explicit source, context type and context id; chat safety cancellation uses `CHAT` with the chat id, visual profile and personal message reports use the match id, profile photo reports use the photo id, and admin-only general user reports use `USER` with the reported user id.
- `SafetyReport.reporterUserId` is nullable because admin/system reports may not have a user reporter. `source = USER` means user-submitted, `source = ADMIN` means backoffice-created, and `SYSTEM` is reserved for future flows.
- `SafetyReportEvidenceSnapshot` stores one auxiliary evidence snapshot per safety report. It stores chat/message counts and a deterministic transcript SHA-256 hash, but does not store the transcript or duplicate message contents.
- `AuditEvent` records safety-relevant operational events with minimal metadata. It must not store raw IP addresses, raw user agents, chat message contents, report details, emails, Firebase UIDs, photo URLs, storage keys or other sensitive payloads.
- `UserBlock` records directional blocks. Matchmaking treats a block in either direction between two users as a bidirectional exclusion.
- `VisualReview` belongs to a match.
- `VisualReviewAffinityIndicator` belongs to a match and stores at most three positive shared category id/title snapshots for visual review. Indicator rows may exist before `VisualReview`, are exposed only through visual-profile access, and never store question ids, answers, scores, kinds or evidence counts.
- `Connection` belongs to a match.
- `ScheduleNegotiation` belongs to a connection.
- `ScheduleProposal` belongs to a connection and user.
- `ActiveEngagementLock` logically belongs to a user and either a match or connection.
- `PushDeviceToken` belongs to a user and stores an enabled FCM device token.
- `PushNotificationDelivery` deduplicates external push attempts per user, notification type and aggregate id. For `MATCH_FOUND` and `MATCH_FOUND_INVALIDATED`, the aggregate id is the match id. For `VISUAL_REVIEW_REMINDER`, the aggregate id is the match id; historical `VISUAL_REVIEW_AVAILABLE` rows also use match id and remain readable. For grouped `SCHEDULING_AVAILABLE`, the aggregate id is deterministic from notification type, user id and the sorted activated connection ids from one activation job execution. For `SCHEDULING_PROPOSALS_RECEIVED`, the aggregate id is deterministic from notification type, connection id and round number, allowing one partner notification per round. For `SCHEDULING_CONFIRMED`, the aggregate id is the connection id. For `SECOND_CHAT_REMINDER`, the aggregate id is a deterministic reminder key derived from connection id and `minutesBefore`, so multiple configured reminder lead times can be sent once each. For `SECOND_CHAT_STARTED`, the aggregate id is deterministic from `second-chat-started:<connectionId>`.
- `UserLegalDocumentAction` is an append-oriented factual record that a user performed a configured action for a legal document type/version/content SHA-256 at a backend-generated timestamp. It stores `userId`, `documentType`, `documentVersion`, nullable `documentContentSha256`, `action` and `actedAt`; it does not store document text or document URLs.

Matchmaking pair eligibility distinguishes active interactions, temporary history cooldowns and permanent blocks:

- Active-pair uniqueness is invariant. Users are not eligible as a pair while they have an active `CHAT_ACTIVE` or `VISUAL_PHASE` match, a `VISUAL_APPROVED` match whose connection was not created yet, or any non-`CLOSED` connection.
- `active_engagement_locks` continue to model per-user active match/connection capacity. They are not an unordered pair-exclusion table and are not duplicated for cooldowns.
- When `matchmaking.exclude-previous-pairing` is enabled, previous terminal outcomes create temporary exclusions calculated from existing history: 30 days for ordinary explicit first-chat rejection, visual rejection, visual-review expiration and closed connections; 7 days for first-chat absolute timeout or inactivity abandonment; 7 independently configurable days for `FIRST_CHAT_DECISION_MISMATCH`.
- `MatchState.EXPIRED` is classified from persisted phase evidence. An expired match with no `VisualReview` is treated as first-chat expiration; an expired match with a `VisualReview` is treated as visual-review expiration. First-chat `Chat.endedAt` is preferred for timeout/abandonment timestamps, with `Match.updatedAt` as legacy fallback.
- `MatchState.CHAT_REJECTED` is classified from persisted first-chat `ChatEndReason` when present. Rows with `FIRST_CHAT_DECISION_MISMATCH` use the decision-mismatch cutoff; legacy rows without that end reason keep the ordinary previous-pairing cutoff.
- A cooldown expires naturally when its cutoff elapses. No cleanup job, derived cooldown table, new match state or automatic `UserBlock` is created for normal product outcomes.

## Affinity Questions

Affinity questions are closed, private profile-owned answers used as
compatibility evidence and as input to answer-free first-chat/visual snapshots.
They are separate from free-text profile prompts. Raw affinity answers remain
private: counterpart-facing responses receive only snapshotted prompt text in
first chat and positive shared category labels during visual review.

The static catalog lives in `src/main/resources/affinity-questions.es-AR.json`
and is loaded once at application startup. The top-level catalog contains a
catalog version, ordered visible categories and ordered question definitions.
Categories are catalog-driven UI reference data; labels are not hardcoded in
controllers.

Each question, including retained deprecated definitions, has one category, one
primary topic, one primary construct, one answer type, ordered options, explicit
ranking and conversation comparison policies, sensitivity classification, and
independent `rankingEnabled` / `conversationEnabled` flags. Enabled flags must
exactly match non-`NONE` comparison policies. Supported answer types are
`SINGLE_CHOICE` and `ORDINAL_SCALE`; both accept exactly one stored option code.

Catalog validation fails startup for empty catalogs, duplicate category ids or
display orders, duplicate question ids, invalid category references, blank
Spanish text, unsupported answer types, duplicate option codes or display
orders, invalid versions, invalid sensitivity values, incomplete/range-unsafe
policies, enabled-flag/policy contradictions, asymmetric custom matrices,
custom matrices with missing or extra option rows/columns, and questions that
reference missing categories or options.

Stored answers persist only `profileId`, `questionId`, the current
`questionSemanticVersion`, `answerCode` and timestamps. Catalog questions are
not stored in the database. `semanticVersion` changes represent answer meaning
or comparison-semantics changes; stale stored answers are excluded from pair
evaluation until the user answers the current semantic version. `contentVersion`
changes are wording-only and do not invalidate stored answers.

Answer writes serialize on the owning profile row. `PATCH` accepts only active
current-catalog questions and current option codes. `DELETE` normalizes the path
question id, deletes only the authenticated profile's matching answer, and does
not require the question to remain active or present in the current catalog.
Deleting a nonexistent owned answer is an idempotent no-op.

`AffinityQuestionPairEvaluator` is pure and performs no repository access. It
compares only questions answered by both users and valid under the current
semantic catalog definition. Missing or unshared answers are neutral and never
count as incompatibility. The evaluator returns `PairAffinityEvidence` with
question-level signals and category evidence only; it does not perform final
multidimensional aggregation or repository reads. Probabilistic matchmaking may
aggregate ranking-enabled evidence internally under
`matchmaking.ranking.affinity`, but affinity is never a hard eligibility filter
and is not exposed through public APIs.

When `ChatService.startFirstChat` creates a first chat, the backend loads both
profiles' affinity answers in one bounded batch, evaluates them with the current
catalog and persists immutable output snapshots. Conversation prompt selection
uses only `STANDARD` sensitivity signals with `conversationPotential > 0` and
kind `SHARED_AFFINITY` or `CONSTRUCTIVE_CONTRAST`. Eligible signals sort by
conversation potential descending, category display order ascending, catalog
question order ascending and question id ascending. The first pass takes at most
one question per category; the second pass fills remaining slots with the best
unselected eligible signals; any remaining slots use the deterministic generic
first-chat catalog sequence. Affinity prompts precede generic fallback prompts,
sources are not duplicated, and the persisted sequence has exactly the
configured `maxQuestions` when the generic catalog is valid. Later answer edits,
answer deletions or catalog wording changes do not alter that first chat.

Visual-review affinity indicators are generated from the same initialization
evidence. Only `STANDARD` shared-affinity signals with positive conversation
potential are eligible; constructive contrast, neutral/not-eligible, sensitive,
negative and one-sided evidence never create indicators. Eligible signals are
aggregated by category, ordered by maximum internal conversation potential
descending, category display order ascending and category id ascending, then
capped at three. Only catalog category id/title are snapshotted and exposed.

Current policy families are explicit and test-covered:

- no ranking contribution;
- shared engagement;
- ordinal alignment;
- same answer or neutral difference;
- custom symmetric ranking matrix;
- shared-affinity conversation potential;
- constructive-contrast conversation potential;
- custom symmetric conversation matrix.

Sensitive public-life and belief/spirituality questions ask only about salience,
discussion comfort or respectful differences. They do not ask for orientation,
affiliation, denomination or doctrine, and their ranking contribution is capped
low and explicit.

## Legal Documents

Legal document configuration defines the current document catalog under
`legal.documents`. Each configured current document has a `type`, `version`,
`url`, `content-sha256` and `required-action`. Runtime configuration may use an
empty catalog.

Canonical legal HTML source files live in this repository under
`legal-documents/` using `terms/<version>/document.html`,
`privacy/<version>/document.html` and
`community-guidelines/<version>/document.html`. The configured
`content-sha256` is SHA-256 of the exact raw `document.html` bytes. Startup
verifies that each configured current document has a bundled canonical file and
that its byte-exact hash matches configuration. The public URL is publication
metadata and is not fetched by the backend.

`user_legal_document_actions` is the source of truth for factual user actions.
Rows are idempotent per `user_id + document_type + document_version`. Historical
actions remain persisted but satisfy status only for the same current configured
version, required action and document content SHA-256. Pre-BACK-7 rows may have
`documentContentSha256 = null`; such rows are legacy unanchored actions and do
not establish exact historical content identity.

Audit events with `LEGAL_DOCUMENT_ACTION_RECORDED` are secondary operational
evidence for newly-created rows only. They use `USER` aggregate and factual
metadata: document type, document version, document content SHA-256 and action.

Current legal status is authoritative for selected protected
participation/progression writes. `LegalComplianceService` delegates to
`LegalDocumentService.getStatus` on each guarded operation; it does not cache
state or duplicate status calculation. Empty configured catalogs are naturally
satisfied. Historical actions remain persisted but do not satisfy a newer
configured version.

Unsatisfied protected participation/content writes fail with
`409 LEGAL_ACTION_REQUIRED`. The detailed status contract remains
`GET /api/me/legal-status`; generic conflict responses do not enumerate missing
documents. Reads, individual text/audio chat message sends, account
deletion/reactivation, legal action recording, chat exit/cancellation/safety
operations and safety/reporting flows remain outside the legal gate.

The legal model does not add legal fields to `User`, does not add a user status,
and does not treat every action as legal consent.

## Active Engagement Locks

Users can have multiple active matches and connections. Capacity limits are
admission controls for new matchmaking opportunities, not required targets and
not hard lifecycle ceilings for engagements that already exist:

- `engagement.max-active-matches: 5`
- `engagement.max-active-connections: 4`

The lock table is the source of truth for active engagement counting.

- Match creation creates one `MATCH` lock per user.
- Chat rejection or match expiration deletes match locks for both users.
- A visual decision releases the deciding user's match lock immediately.
- Mutual visual approval creates a `SCHEDULING_PENDING` connection and creates `CONNECTION` locks immediately, so the pending connection occupies connection capacity before scheduling is actionable.
- Visual rejection closes the match and releases remaining match locks only after both users have decided or the visual phase expires.
- Connection closure deletes connection locks.

Match capacity gates new Match creation. Connection capacity gates future
matchmaking from active `CONNECTION` locks. `UserReliabilityScore`, when
enabled, derives per-user effective Match and Connection admission caps from the
current decayed score; the effective score, reliability tier and effective caps
are not persisted. When reliability is disabled, effective caps are exactly the
configured neutral baselines. The configured neutral baseline must be inside
the corresponding reliability-capacity min/max range so the base-score capacity
equals the configured baseline. The Visual Advancement Cap remains separate and
static: it gates future matchmaking from recent `VisualReview.createdAt`
throughput and is not reliability-dependent.

The dynamic capacity curve uses one explicit backend `now` for each decision:

```text
delta = effectiveScore - reliabilityBaseScore
saturating(x, scale) = x² / (x² + scale²)

delta >= 0:
  cap = round(base + (max - base) * saturating(delta, rewardScale))

delta < 0:
  cap = round(base - (base - min) * saturating(abs(delta), penaltyScale))

cap is coerced to [min, max]
```

Default Match curve: base `engagement.max-active-matches` (`5`), min `3`, max
`9`, reward scale `20`, penalty scale `10`. Default Connection curve: base
`engagement.max-active-connections` (`4`), min `2`, max `6`, reward scale `30`,
penalty scale `10`. The asymmetry is intentional: poor reliability reduces
capacity faster than positive reliability increases it, and Connection capacity
has the more conservative positive curve.

If a user's reliability falls and the new effective cap is below their current
active count, existing engagements survive. The backend does not close, cancel,
reorder or invalidate active Matches, Visual Reviews or Connections because a
derived cap changed. Natural overshoot is valid. The user simply remains
unavailable for new Match admission until active lock counts fall below the
effective Match and Connection caps again. A Match admitted earlier may still
progress through Visual Review into a Connection even if that creates
`activeConnections > effectiveConnectionCap`.

Availability and final Match admission keep stable blocker codes
`ACTIVE_MATCH_LIMIT_REACHED` and `ACTIVE_CONNECTION_LIMIT_REACHED`, but user
responses do not expose numeric effective caps.

Do not infer active engagement counts from match or connection state alone.

## Chat Exit Rules

Chats can end through approval/normal completion, timeout, inactivity abandonment or explicit cancellation.

- Mutual cancellation creates a pending `ChatExitRequest`. Acceptance, rejection and client-triggered timeout all close the chat as `CANCELLED`.
- Accepted mutual cancellation has no penalty.
- Rejected mutual cancellation currently has no penalty.
- Timed-out mutual cancellation currently has no penalty. It is not a unilateral cancellation; if the requester resolves the timeout because the responder did not answer in time, the requester must not be penalized.
- When a first-chat mutual cancellation request is accepted, rejected or timed out, a requester who has at least one confirmed first-chat message receives `FIRST_CHAT_RESPONSIBLE_CLOSE_REQUEST_RESOLVED` once for that chat when user reliability is enabled. Creating the request alone is not rewarded.
- Unilateral cancellation closes the chat as `CANCELLED` and applies a penalty when the cancelling user has not reached the configured minimum messages for penalty-free cancellation.
- First-chat unilateral-cancellation reliability uses three bands when user reliability is enabled: below the lower threshold (`user-reliability.first-chat.early-engagement-minutes`, default 2, and `early-engagement-messages-per-user`, default 1 from each participant) records `FIRST_CHAT_EARLY_UNILATERAL_CLOSE` with delta `-3`; lower threshold met but sufficient interaction not met records `FIRST_CHAT_PARTIAL_UNILATERAL_CLOSE` with delta `-1`; sufficient interaction (`min-participation-minutes`, default 5, and `min-participation-messages-per-user`, default 2 from each participant) records no unilateral-close reliability event. Equality belongs to the met side: `now >= startedAt + threshold`.
- Safety cancellation closes the chat as `CANCELLED`, records `ChatEndReason.SAFETY_REPORT`, exempts the reporting user and creates a `SafetyReport` in `PENDING` status. It also records an accepted `SAFETY_REPORT` exit request as operational chat-closure history. The reported participant is penalized only if an admin confirms the report.
- Chat safety cancellation maps `ChatExitReason.CHILD_SAFETY_CONCERN` to `SafetyReportReason.CHILD_SAFETY_CONCERN`. The reason does not itself apply an automatic penalty or ban.
- Safety cancellation automatically creates a directional `UserBlock` from reporter to reported. Matchmaking excludes the pair in both directions even though only one block row is stored.
- `CHILD_SAFETY_CONCERN` is a broad reported concern, not a confirmed finding. Direct reports and chat safety cancellations using it create a normal `PENDING` report and retain the same block and containment behavior as other user-created reports.
- `SafetyReport.priorityReview` is derived as `true` only while the report is `PENDING` and its reason is `CHILD_SAFETY_CONCERN`; it is not persisted. Admin lists sort this priority first and then `createdAt` descending before applying the 100-result limit.
- No penalty or ban follows from `CHILD_SAFETY_CONCERN` alone. A safety penalty still requires explicit admin confirmation through the existing report penalty path.
- General user safety reports outside chat cancellation validate a real chat or visual-review interaction and create the same directional block without closing any chat. Duplicate reports for the same reporter, reported user, context type and context id return the existing report.
- User-created reports always have `source = USER`, a non-null reporter and create or reuse a directional block from reporter to reported.
- Admin-created reports have `source = ADMIN`, `createdByAdminUserId`, and may have no user reporter. They do not auto-block, auto-close chats or auto-apply penalties in the creation path.
- `SafetyReportContextType.USER` is admin-only for now. User-facing `POST /api/safety/reports` rejects it.
- Creating a safety report also captures a `SafetyReportEvidenceSnapshot` and records a `SAFETY_REPORT_CREATED` audit event. Evidence capture uses message content only as hash input and does not persist a second copy of message text.
- Dismissing or confirming a safety report records `SAFETY_REPORT_DISMISSED` or `SAFETY_REPORT_CONFIRMED`; audit metadata excludes report details, verdict notes and penalty reasons.
- Dismissing a report as `DISMISSED_ABUSIVE_OR_UNJUSTIFIED` creates no safety penalty. If user reliability is enabled and the report has a user reporter, it records the internal `SAFETY_REPORT_DETERMINED_ABUSIVE` reliability event against the reporter.
- A successful optional visual-review personal-message submission records the internal `VISUAL_PERSONAL_MESSAGE_SUBMITTED` reliability participation event once per user per match when user reliability is enabled. Visual approval/rejection and partner-message reading remain reliability-neutral.
- `ChatStatus` remains the operational state; `ChatEndReason` records why a chat ended.
- Temporary penalties have `PenaltyType.TEMPORARY_BAN` and a non-null `expiresAt`; the penalty expiration job deactivates them after expiry.
- Permanent penalties have `PenaltyType.PERMANENT_BAN`, `expiresAt = null` and are never expired by the job.
- Active penalties block matchmaking. Creating a penalty removes the penalized user from the matchmaking queue if present.

## Audit And Evidence

- `audit_events` stores operational metadata only. Metadata should stay flat and small: enum values, booleans, counts and related IDs where useful.
- Request id, IP hash and user-agent hash columns exist for future enrichment, but raw IP/user-agent values are not stored by the current backend flow.
- Safety report evidence snapshots store message counts, first/last message timestamps and `transcriptSha256`.
- Transcript hashing orders messages by `sentAt ASC, id ASC` and hashes stable fields including message content. The content is used only as hash input.
- Audit and evidence records are internal persistence only in the current backend. There are no admin/backoffice APIs for reading or mutating them yet.
- Admin safety DTOs intentionally avoid raw email, Firebase UID and full `User` exposure. Report counters are computed dynamically for the reported user, not denormalized into user/report rows.

## Profile Rules

- A profile starts as `DRAFT`.
- Only `ACTIVE` profiles can enter matchmaking.
- Activation validates photo requirements from `profile.photos.*`.
- `authenticityVerificationStatus` is the richer persisted profile authenticity verification state and is the source of truth. `authenticityVerified` remains a compatibility boolean projected from that status with the invariant `authenticityVerified == (authenticityVerificationStatus == VERIFIED)`.
- Default shared photo requirements are 9 photos, 3 person photos and 1 full-body photo. Production temporarily defaults minimum full-body photos to 0 until a real full-body detector exists.
- Local and test profiles override required photos to 4, min person to 1 and min full-body to 1.
- Birth date and gender are immutable after creation.
- Editable fields include display name, bio, city, country code, intention and looking-for gender.
- `Profile.city` remains free text.
- `Profile.countryCode` is a canonical uppercase ISO 3166-1 alpha-2 code stored in `profiles.country_code`.
- Allowed profile country codes come from the backend country reference catalog. The catalog is built once from the Java runtime ISO country list with Spanish display names from `Locale.forLanguageTag("es")`, ordered by Spanish display name and country code, and retained as immutable in-memory reference data. Clients should fetch it through `GET /api/reference/countries` and submit the selected `code` as `countryCode`.
- Profile country input is trimmed, uppercased and validated against that catalog. Display names and guessed mappings are not accepted.
- Dynamic matchmaking filters include preferred minimum age, preferred maximum age and maximum distance in kilometers. They are required profile values. Preferred ages are enforced in the basic matchmaking query. Maximum distance is enforced from the current search location sent when a user enters the matchmaking queue.
- Photo positions are unique per profile.
- Removing a required photo can revert an active profile to `DRAFT`.
- File-backed profile photos store provider, bucket and object key. The database does not store renderable photo URLs; `storageKey` is the source of truth for retrieval. API responses still expose `PhotoResponse.url`, but that URL is generated at response time from the stored object key. Private storage may use presigned, time-limited URLs, so clients should refresh photo responses instead of treating URLs as permanent identifiers.
- Profile photo technical validation is immediate and blocking. Invalid file type, size, decode or dimensions reject the upload before storage or persistence. Successful technical validation is not semantic person/full-body validation.
- Outside `prod`, the temporary MVP photo shortcut returns `isPersonPhoto=true`, `isFullBody=true` and `validationStatus=VALIDATED` when provider `none` is used. In `prod`, provider `none` leaves technically valid photos as `validationStatus=PENDING`, `isPersonPhoto=false` and `isFullBody=false`.
- Profile photo provider `sightengine` is enabled only in `prod`. In non-production execution profiles, configuring `sightengine` still uses the provider `none` compatibility path and does not call Sightengine. In `prod`, provider `sightengine` performs one synchronous Sightengine multipart request per upload/replacement after technical validation and server normalization. That single request uploads the same normalized JPEG bytes that object storage receives and asks for `face-analysis`, `nudity-2.1`, `violence`, `gore-2.0` and `offensive-2.0`; provider output is mapped to internal Reals signal models and is not persisted.
- Sightengine real face presence is used only as an MVP `isPersonPhoto` signal. At least one `faces` entry sets `isPersonPhoto=true`; no real faces sets `isPersonPhoto=false`; `artificial_faces` do not count. Group/other-person false positives are accepted MVP limitations. This is not profile authenticity verification, legal identity verification, facial recognition, face matching, liveness, age estimation, minor detection or full-body detection.
- Sightengine does not establish `isFullBody`; successful Sightengine analyses persist `isFullBody=false`. Production temporarily keeps `profile.photos.min-full-body-photos=0` because there is still no real full-body detector.
- Profile photo content moderation is separate and persisted as `moderationStatus`. With provider `none` outside `prod`, the MVP compatibility path returns `APPROVED` without external review. With provider `none` in `prod`, uploads may proceed but moderation persists as `NEEDS_REVIEW`. With provider `sightengine` in `prod`, Reals policy evaluates provider-neutral sexual explicit, sexual suggestive, violence/threat, gore and hate/extremism scores. Reject thresholds win over review thresholds; otherwise the photo is `APPROVED`.
- Automatic provider moderation does not create child-safety reports, `SafetyReport` rows, blocks, penalties, bans or account lifecycle changes. Child-safety reporting remains the existing human/reporting workflow.
- `PhotoValidationStatus.PENDING` is not the admin moderation queue. It means semantic photo analysis is unresolved. `PhotoModerationStatus.NEEDS_REVIEW` means content moderation requires a human decision.
- Admin photo moderation review supports only `NEEDS_REVIEW -> APPROVED` and `NEEDS_REVIEW -> REJECTED`. The review queue includes a `photoVersion`, and resolution requires the same `expectedPhotoVersion` so an admin cannot accidentally resolve a stale photo snapshot after the photo row changed. Stale review snapshots return `PROFILE_PHOTO_MODERATION_REVIEW_NOT_AVAILABLE`; the admin must refresh and review the current photo again. A successful manual moderation decision records `PHOTO_MODERATION_UPDATED` on `PROFILE_PHOTO` and does not change `validationStatus`, `isPersonPhoto` or `isFullBody`.
- By default shared/local profile activation does not require moderation approval. In `prod`, activation defaults to requiring `moderationStatus = APPROVED`; when `profile.photos.require-moderation-approval-for-activation=true`, every technically valid required photo must have `moderationStatus = APPROVED`.
- Profile Authenticity Verification is not legal identity verification. It does not prove legal name, DNI, passport identity, KYC identity or age. Age assurance and legal/document verification are separate future concerns, and profile photo moderation remains separate from facial authenticity.
- The future target is a liveness-derived live reference plus provider-neutral facial comparison signals for current candidate person photos. Candidate photos are exactly `validationStatus == VALIDATED AND isPersonPhoto == true`, ordered by current profile-photo position. `isPersonPhoto` selects comparison candidates; it does not establish that the detected person is the verified user. A group photo can be `MATCHED` when at least one comparable face matches the live reference; non-person photos are excluded from face comparison.
- Provider-neutral profile-authenticity comparison outcomes are `MATCHED`, `UNRESOLVED` and `CONTRADICTORY`. `MATCHED` is positive authenticity evidence. `UNRESOLVED` is neutral: old, distant, side-profile, obscured or otherwise poor comparisons do not count positively and do not count as contradictions. `CONTRADICTORY` means comparable facial evidence is inconsistent with the accepted live reference; it currently prevents automatic verification under the default policy but does not prove fraud.
- The default MVP Reals authenticity policy is `liveReferenceAccepted=true`, at least 3 matched current candidate person photos and at most 0 contradictory current candidate person photos. Policy configuration is independent from `profile.photos.min-person-photos`: both default to `3` today, but one counts person-photo classification and the other counts positive facial authenticity evidence. Contradictions currently produce `NEEDS_REVIEW`, not automatic `REJECTED`; the policy does not return `REJECTED` in this step.
- The current provider-neutral request contains only `userId`, `profileId` and internal `ProfileAuthenticityPhotoCandidate(photoId, photoVersion, storageKey)` values. It does not include display name, birth date, gender, location, intention, matchmaking preferences or profile text, and storage keys are not exposed through HTTP contracts.
- With provider `none` outside `prod`, the MVP compatibility path sets `authenticityVerificationStatus = VERIFIED` and `authenticityVerified = true` without liveness or face comparison. With provider `none` in `prod`, `POST /api/me/profile/authenticity-verification` returns `409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED` and does not persist `VERIFIED`.
- Authenticity verification is valid only for the evaluated photo content/set. Successful profile-photo upload, file replacement and deletion apply this invalidation rule: `NOT_STARTED -> NOT_STARTED`, `STALE -> STALE`, and `PENDING`, `VERIFIED`, `REJECTED` or `NEEDS_REVIEW -> STALE`. After every mutation, `authenticityVerified` is re-derived from the resulting status. Photo reorder does not invalidate authenticity because the content and set are unchanged. Profile text, location and matchmaking-filter edits do not invalidate authenticity.
- When a photo mutation changes authenticity to `STALE`, the audit trail records `PROFILE_AUTHENTICITY_VERIFICATION_UPDATED` with `oldStatus`, `newStatus=STALE`, `authenticityVerified=false` and `reason=PROFILE_PHOTO_MUTATED`, in addition to the photo mutation event. Verification-status audit metadata must not include raw image bytes, storage keys, face embeddings, biometric templates or provider secrets.
- The current MVP limitation is that `isPersonPhoto` in the Sightengine path means at least one real face was detected. This skeleton does not yet prove person consistency for a body-only image without a comparable visible face; that requires a broader future person-consistency strategy.
- Future real-provider work still needs decisions or implementation for liveness capture/session lifecycle, live reference artifact handling, facial comparison provider, score thresholds, retry policy, `NEEDS_REVIEW` workflow, provider metadata, biometric/privacy/retention policy, reference-image retention or immediate deletion, asynchronous callback/webhook handling if required, and separate age assurance.
- By default profile activation does not require verified authenticity. When `profile.authenticity-verification.require-for-activation=true`, profile activation requires `authenticityVerificationStatus = VERIFIED`; `STALE` fails with `PROFILE_AUTHENTICITY_VERIFICATION_REQUIRED`.

## Account Deletion And Recovery

- `DELETE /api/me` moves the user to `DELETED` and sets `deletedAt` plus `deletionFinalizesAt`.
- The account remains recoverable until `deletionFinalizesAt`.
- During the recovery window, the email and Firebase UID remain reserved and cannot provision a new account.
- Deletion immediately removes ephemeral operational state: matchmaking queue rows, engagement locks, push device tokens, push-delivery records, connection Home dismissals and the deleted user's Home-status projection.
- Deletion contains active Matches, Chats, Connections and scheduling negotiations but retains their historical lifecycle and content rows. Counterpart Home invalidations remain. Reactivation does not reopen previous engagements.
- Deletion moves the profile back to `DRAFT` while preserving profile data, photo metadata and profile-photo storage objects during recovery.
- `POST /api/me/reactivation` restores the user to `ACTIVE` only while the recovery window is still open. The profile remains `DRAFT` and must be activated again before matchmaking.
- Reactivation does not restore deleted queue, lock, push, Home-dismissal or Home-status state. Home status is recreated through its normal missing-row behavior when needed, and a current FCM token can be registered again.
- The Firebase Auth identity and local Firebase UID remain linked throughout recovery so ownership authentication and reactivation continue to work.
- After recovery expires, finalization locks and revalidates the user, then deletes or confirms absence of the Firebase Auth identity before replacing the local email and releasing the local Firebase UID.
- Finalization coordinates external identity deletion and local identifier release; it remains distinct from a broader product-data purge.
- Post-recovery retention, purge and anonymization policy is tracked in `docs/data-retention.md`.

## Match Filtering And Compatibility

Hard filtering is first applied in the matchmaking queue query. The query filters:

- active penalties for both queued users
- mutual gender preference
- same intention
- mutual dynamic preferred age range

`SearchLocationMatchFilter` applies mutual dynamic maximum distance from queue search locations before a pair can be accepted. Final ranking is delegated to `CompatibilityScorer`. The current basic scorer uses `CompatibilityEvaluator`, which repeats profile-level compatibility checks as a service-layer guard.

New search is also paced by the rolling Visual Review advancement cap. A
persisted `VisualReview` is the durable advancement record and counts once for
each Match participant from `VisualReview.createdAt`. The active window counts
rows with `createdAt > now - matchmaking.visual-advancement.window-hours`; at
exactly `createdAt + window`, that row no longer consumes capacity. Later
visual decisions, visual-review expiry, connection creation and Match closure
do not refund or add capacity, so the cap does not reveal counterpart decisions.
The cap blocks matchmaking admission and stale queued assignment only; it does
not block first-chat messages, first-chat `APPROVED`, mutual approval, Visual
Review creation for an existing Match, Visual decisions, scheduling or second
chat.

Advanced criteria such as affinity tags or probabilistic scoring are not implemented.

The current matching selector expects scores normalized from `0.0` to `1.0`. Environment properties define the number of SQL-filtered candidate pairs to score, the minimum accepted score and the early-accept score that stops further scoring.

## User blocks

`UserBlock` persists a durable directional action while product exclusion is pair-wide. Block creation preserves the first source, is idempotent and pair-serialized, and has no automatic expiration or unblock operation. Every command contains stale active state across all matches and connections for the pair. Manual blocks create no report, penalty, reliability event, visual decision, or chat exit request.
