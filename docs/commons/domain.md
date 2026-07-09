# Domain Model

The domain is state-driven and anonymous-first. Business transitions are validated in services, not encoded in controllers or repositories.

## Entities

- `User`
- `Profile`
- `ProfilePhoto`
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
- `IdentityVerificationStatus`: `NOT_STARTED`, `PENDING`, `VERIFIED`, `REJECTED`, `NEEDS_REVIEW`

Matching and chat:

- `MatchState`: `CHAT_ACTIVE`, `VISUAL_PHASE`, `VISUAL_APPROVED`, `CHAT_REJECTED`, `VISUAL_REJECTED`, `EXPIRED`
- `ChatType`: `FIRST_CHAT`, `SECOND_CHAT`
- `ChatStatus`: `AVAILABLE`, `ACTIVE`, `FINISHED`, `CANCELLED`, `EXPIRED`, `ABANDONED`, `CLOSED`
- `ChatEndReason`: `MUTUAL_CANCEL`, `UNILATERAL_CANCEL`, `SAFETY_REPORT`, `ABSOLUTE_TIMEOUT`, `INACTIVITY_TIMEOUT`, `SECOND_CHAT_READ_ONLY_EXPIRED`, `USER_DELETED`, `SYSTEM_CLOSED`
- `ChatContinueDecision`: `APPROVED`, `REJECTED`
- `ChatParticipantDecisionStatus`: `PENDING`, `APPROVED`, `REJECTED`, `ABANDONED` (API-facing status derived from chat decisions and terminal chat outcomes)
- `ChatExitRequestType`: `MUTUAL_CANCEL`, `UNILATERAL_CANCEL`, `SAFETY_REPORT`
- `ChatExitRequestStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `TIMED_OUT`
- `ChatExitReason`: `NO_LONGER_INTERESTED`, `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `CHILD_SAFETY_CONCERN`, `OTHER`
- `SafetyReportStatus`: `PENDING`, `DISMISSED`, `DISMISSED_ABUSIVE_OR_UNJUSTIFIED`, `CONFIRMED`
- `SafetyReportReason`: `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `CHILD_SAFETY_CONCERN`, `OTHER`
- `SafetyReportContextType`: `CHAT`, `VISUAL_PROFILE`, `PERSONAL_MESSAGE`, `PROFILE_PHOTO`, `USER`
- `SafetyReportSource`: `USER`, `ADMIN`, `SYSTEM`
- `AuditEventType`: `SAFETY_REPORT_CREATED`, `SAFETY_REPORT_DISMISSED`, `SAFETY_REPORT_CONFIRMED`, `USER_BLOCK_CREATED`, `CHAT_ENDED`, `PROFILE_PHOTO_UPLOADED`, `PROFILE_PHOTO_REPLACED`, `PROFILE_PHOTO_DELETED`, `PROFILE_PHOTOS_REORDERED`, `PROFILE_ACTIVATED`, `PHOTO_MODERATION_UPDATED`, `IDENTITY_VERIFICATION_UPDATED`, `ACCOUNT_DELETION_REQUESTED`, `ACCOUNT_DELETION_FINALIZED`, `ACCOUNT_REACTIVATED`, `PENALTY_APPLIED`, `LEGAL_DOCUMENT_ACTION_RECORDED`
- `AuditAggregateType`: `USER`, `PROFILE`, `PROFILE_PHOTO`, `CHAT`, `MATCH`, `CONNECTION`, `SAFETY_REPORT`, `USER_BLOCK`, `PENALTY`
- `LegalDocumentType`: `TERMS_OF_USE`, `PRIVACY_NOTICE`, `COMMUNITY_GUIDELINES`
- `LegalDocumentAction`: `ACCEPTED`, `ACKNOWLEDGED`
- `UserReliabilityEventType`: `FIRST_CHAT_MUTUAL_POSITIVE_RESOLUTION`, `FIRST_CHAT_MUTUAL_NO_SPARK_CLOSURE`, `FIRST_CHAT_UNILATERAL_CLOSE_AFTER_MINIMUM_PARTICIPATION`, `FIRST_CHAT_EARLY_UNILATERAL_CLOSE`, `FIRST_CHAT_CLOSED_AFTER_COUNTERPARTY_INACTIVE`, `FIRST_CHAT_MUTUAL_CLOSE_REQUEST_IGNORED`, `FIRST_CHAT_EXPIRED_NO_DECISION`, `VISUAL_REVIEW_EXPIRED_NO_DECISION`, `VISUAL_PERSONAL_MESSAGE_SUBMITTED`, `SCHEDULING_SLOTS_PROPOSED_ON_TIME`, `SCHEDULING_EXPIRED_NO_PROPOSAL`, `SECOND_CHAT_CONFIRMED_ATTENDED`, `SECOND_CHAT_NO_SHOW`, `SAFETY_REPORT_DETERMINED_ABUSIVE`
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
negotiation. Home surfaces this state through
`activeInteractionsSummary.pendingSchedulingConnectionCount` and the passive
notice `SCHEDULING_PREPARING`, not through `nextSteps`.

Scheduling proposals represent second-chat slots inside the app. They do not represent in-person meeting times. A proposal row stores one possible slot, its `roundNumber` and its `preferenceOrder` within the user's submitted list. A confirmed negotiation schedules the second chat for `confirmedDateTime`; `GET /api/connections/{connectionId}/chat` materializes and activates the `SECOND_CHAT` idempotently when `now >= confirmedDateTime` and before the configured writable window expires. Home exposes that agreed time as `secondChat.availableAt`. After `timeoutAt`, second chats become `EXPIRED` and read-only until `readOnlyUntil`; cleanup then marks them `CLOSED` and closes the connection.

Engagement:

- `EngagementType`: `MATCH`, `CONNECTION`

User:

- `UserStatus`: `ACTIVE`, `DELETED`

Push notifications:

- `PushPlatform`: `ANDROID`
- `PushNotificationType`: `VISUAL_REVIEW_AVAILABLE`, `SCHEDULING_AVAILABLE`, `SECOND_CHAT_REMINDER`
- `PushDeliveryStatus`: `SENT`, `SKIPPED_NO_ACTIVE_TOKEN`, `FAILED`

## Relationships

- A `User` may have one `Profile`.
- A `Profile` has many `ProfilePhoto` records.
- A `Match` has `userAId` and `userBId`.
- A `Chat` belongs to a `Match`; `SECOND_CHAT` also has `connectionId`.
- `FirstChatGuidance` belongs to one `FIRST_CHAT` through a unique `chatId`. It stores the active question id/text snapshot, ordinal, activation timestamp, per-participant next-question request timestamps and optional completion timestamp. It does not store message counters or the full selected sequence.
- `ChatDecision` belongs to a chat and match.
- `ChatExitRequest` records mutual cancellation requests, unilateral cancellations and safety-report chat closures.
- `SafetyReport` is the moderation source of truth for reported safety incidents. It stores an explicit source, context type and context id; chat safety cancellation uses `CHAT` with the chat id, visual profile and personal message reports use the match id, profile photo reports use the photo id, and admin-only general user reports use `USER` with the reported user id.
- `SafetyReport.reporterUserId` is nullable because admin/system reports may not have a user reporter. `source = USER` means user-submitted, `source = ADMIN` means backoffice-created, and `SYSTEM` is reserved for future flows.
- `SafetyReportEvidenceSnapshot` stores one auxiliary evidence snapshot per safety report. It stores chat/message counts and a deterministic transcript SHA-256 hash, but does not store the transcript or duplicate message contents.
- `AuditEvent` records safety-relevant operational events with minimal metadata. It must not store raw IP addresses, raw user agents, chat message contents, report details, emails, Firebase UIDs, photo URLs, storage keys or other sensitive payloads.
- `UserBlock` records directional blocks. Matchmaking treats a block in either direction between two users as a bidirectional exclusion.
- `VisualReview` belongs to a match.
- `Connection` belongs to a match.
- `ScheduleNegotiation` belongs to a connection.
- `ScheduleProposal` belongs to a connection and user.
- `ActiveEngagementLock` logically belongs to a user and either a match or connection.
- `PushDeviceToken` belongs to a user and stores an enabled FCM device token.
- `PushNotificationDelivery` deduplicates external push attempts per user, notification type and aggregate id. For `VISUAL_REVIEW_AVAILABLE`, the aggregate id is the match id. For `SCHEDULING_AVAILABLE`, the aggregate id is the connection id. For `SECOND_CHAT_REMINDER`, the aggregate id is a deterministic reminder key derived from connection id and `minutesBefore`, so multiple configured reminder lead times can be sent once each.
- `UserLegalDocumentAction` is an append-oriented factual record that a user performed a configured action for a legal document type/version/content SHA-256 at a backend-generated timestamp. It stores `userId`, `documentType`, `documentVersion`, nullable `documentContentSha256`, `action` and `actedAt`; it does not store document text or document URLs.

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

Current legal status is authoritative for protected participation/content
writes. `LegalComplianceService` delegates to `LegalDocumentService.getStatus`
on each guarded operation; it does not cache state or duplicate status
calculation. Empty configured catalogs are naturally satisfied. Historical
actions remain persisted but do not satisfy a newer configured version.

Unsatisfied protected participation/content writes fail with
`409 LEGAL_ACTION_REQUIRED`. The detailed status contract remains
`GET /api/me/legal-status`; generic conflict responses do not enumerate missing
documents. Reads, account deletion/reactivation, legal action recording, chat
exit/cancellation/safety operations and safety/reporting flows remain outside
the legal gate.

The legal model does not add legal fields to `User`, does not add a user status,
and does not treat every action as legal consent.

## Active Engagement Locks

Users can have multiple active matches and connections up to configured limits:

- `engagement.max-active-matches: 5`
- `engagement.max-active-connections: 2`

The lock table is the source of truth for active engagement counting.

- Match creation creates one `MATCH` lock per user.
- Chat rejection or match expiration deletes match locks for both users.
- A visual decision releases the deciding user's match lock immediately.
- Mutual visual approval creates a `SCHEDULING_PENDING` connection and creates `CONNECTION` locks immediately, so the pending connection occupies connection capacity before scheduling is actionable.
- Visual rejection closes the match and releases remaining match locks only after both users have decided or the visual phase expires.
- Connection closure deletes connection locks.

Do not infer active engagement counts from match or connection state alone.

## Chat Exit Rules

Chats can end through approval/normal completion, timeout, inactivity abandonment or explicit cancellation.

- Mutual cancellation creates a pending `ChatExitRequest`. Acceptance, rejection and client-triggered timeout all close the chat as `CANCELLED`.
- Accepted mutual cancellation has no penalty.
- Rejected mutual cancellation currently has no penalty. Future scoring may apply a lower penalty to the requester.
- Timed-out mutual cancellation currently has no penalty. It is not a unilateral cancellation; if the requester resolves the timeout because the responder did not answer in time, the requester must not be penalized.
- Unilateral cancellation closes the chat as `CANCELLED` and applies a penalty when the cancelling user has not reached the configured minimum messages for penalty-free cancellation.
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
- `identityVerified` remains a compatibility boolean. `identityVerificationStatus` is the richer persisted verification state and is the source of truth for future activation policy.
- Default photo requirements are 9 photos, 3 person photos and 1 full-body photo.
- Local and test profiles override required photos to 4, min person to 1 and min full-body to 1.
- Birth date and gender are immutable after creation.
- Editable fields include display name, bio, city, country, intention and looking-for gender.
- Dynamic matchmaking filters include preferred minimum age, preferred maximum age and maximum distance in kilometers. They are required profile values. Preferred ages are enforced in the basic matchmaking query. Maximum distance is enforced from the current search location sent when a user enters the matchmaking queue.
- Photo positions are unique per profile.
- Removing a required photo can revert an active profile to `DRAFT`.
- File-backed profile photos store provider, bucket and object key. The database does not store renderable photo URLs; `storageKey` is the source of truth for retrieval. API responses still expose `PhotoResponse.url`, but that URL is generated at response time from the stored object key. Private storage may use presigned, time-limited URLs, so clients should refresh photo responses instead of treating URLs as permanent identifiers.
- Profile photo technical validation is immediate and blocking. Invalid file type, size, decode or dimensions reject the upload before storage or persistence. `validationStatus` records that technical validation result.
- Profile photo content moderation is separate and persisted as `moderationStatus`. The current `none` provider returns `APPROVED` without external review. Future moderation providers may handle adult/nudity/safety checks, person detection, full-body detection, face/person consistency and minor/age-risk signals.
- By default profile activation does not require moderation approval. When `profile.photos.require-moderation-approval-for-activation=true`, every technically valid required photo must have `moderationStatus = APPROVED`.
- Identity verification is separate from photo moderation. The current `none` provider sets `identityVerificationStatus = VERIFIED` without external review. Future providers may cover document verification, selfie/liveness, age verification, fraud/replay handling, callbacks/webhooks, manual review for `NEEDS_REVIEW` and data-retention policy for provider artifacts.
- By default profile activation does not require verified identity. When `profile.identity-verification.require-for-activation=true`, profile activation requires `identityVerificationStatus = VERIFIED`.

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

Advanced criteria such as affinity tags or probabilistic scoring are not implemented.

The current matching selector expects scores normalized from `0.0` to `1.0`. Environment properties define the number of SQL-filtered candidate pairs to score, the minimum accepted score and the early-accept score that stops further scoring.

## User blocks

`UserBlock` persists a durable directional action while product exclusion is pair-wide. Block creation preserves the first source, is idempotent and pair-serialized, and has no automatic expiration or unblock operation. Every command contains stale active state across all matches and connections for the pair. Manual blocks create no report, penalty, reliability event, visual decision, or chat exit request.
