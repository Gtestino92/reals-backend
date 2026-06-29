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
- `ChatDecision`
- `ChatExitRequest`
- `SafetyReport`
- `VisualReview`
- `Connection`
- `ScheduleNegotiation`
- `ScheduleProposal`
- `Penalty`
- `ActiveEngagementLock`
- `PushDeviceToken`
- `PushNotificationDelivery`

## Main Enums

Profile:

- `Gender`: `MALE`, `FEMALE`, `NON_BINARY`, `OTHER`
- `LookingForGender`: `MEN`, `WOMEN`, `EVERYONE`, `OTHER`
- `Intention`: `DATE`, `FRIENDSHIP`, `CASUAL`
- `ProfileStatus`: `DRAFT`, `ACTIVE`, `INACTIVE`

Matching and chat:

- `MatchState`: `CHAT_ACTIVE`, `VISUAL_PHASE`, `VISUAL_APPROVED`, `CHAT_REJECTED`, `VISUAL_REJECTED`, `EXPIRED`
- `ChatType`: `FIRST_CHAT`, `SECOND_CHAT`
- `ChatStatus`: `AVAILABLE`, `ACTIVE`, `FINISHED`, `CANCELLED`, `EXPIRED`, `ABANDONED`, `CLOSED`
- `ChatContinueDecision`: `APPROVED`, `REJECTED`
- `ChatParticipantDecisionStatus`: `PENDING`, `APPROVED`, `REJECTED`, `ABANDONED` (API-facing status derived from chat decisions and terminal chat outcomes)
- `ChatExitRequestType`: `MUTUAL_CANCEL`, `UNILATERAL_CANCEL`, `SAFETY_REPORT`
- `ChatExitRequestStatus`: `PENDING`, `ACCEPTED`, `REJECTED`, `TIMED_OUT`
- `ChatExitReason`: `NO_LONGER_INTERESTED`, `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `OTHER`
- `SafetyReportStatus`: `PENDING`, `DISMISSED`, `CONFIRMED`
- `SafetyReportReason`: `INAPPROPRIATE_BEHAVIOR`, `HARASSMENT`, `OTHER`
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
- `PushNotificationType`: `VISUAL_REVIEW_AVAILABLE`, `SECOND_CHAT_REMINDER`
- `PushDeliveryStatus`: `SENT`, `SKIPPED_NO_ACTIVE_TOKEN`, `FAILED`

## Relationships

- A `User` may have one `Profile`.
- A `Profile` has many `ProfilePhoto` records.
- A `Match` has `userAId` and `userBId`.
- A `Chat` belongs to a `Match`; `SECOND_CHAT` also has `connectionId`.
- `ChatDecision` belongs to a chat and match.
- `ChatExitRequest` records mutual cancellation requests, unilateral cancellations and safety-report chat closures.
- `SafetyReport` is the moderation source of truth for reported safety incidents.
- `VisualReview` belongs to a match.
- `Connection` belongs to a match.
- `ScheduleNegotiation` belongs to a connection.
- `ScheduleProposal` belongs to a connection and user.
- `ActiveEngagementLock` logically belongs to a user and either a match or connection.
- `PushDeviceToken` belongs to a user and stores an enabled FCM device token.
- `PushNotificationDelivery` deduplicates external push attempts per user, notification type and aggregate id. For `VISUAL_REVIEW_AVAILABLE`, the aggregate id is the match id. For `SECOND_CHAT_REMINDER`, the aggregate id is a deterministic reminder key derived from connection id and `minutesBefore`, so multiple configured reminder lead times can be sent once each.

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
- Safety cancellation closes the chat as `CANCELLED`, exempts the reporting user and creates a `SafetyReport` in `PENDING` status. It also records an accepted `SAFETY_REPORT` exit request as operational chat-closure history. The reported participant is penalized only if an admin confirms the report.
- Temporary penalties have `PenaltyType.TEMPORARY_BAN` and a non-null `expiresAt`; the penalty expiration job deactivates them after expiry.
- Permanent penalties have `PenaltyType.PERMANENT_BAN`, `expiresAt = null` and are never expired by the job.
- Active penalties block matchmaking. Creating a penalty removes the penalized user from the matchmaking queue if present.

## Profile Rules

- A profile starts as `DRAFT`.
- Only `ACTIVE` profiles can enter matchmaking.
- Activation validates photo requirements from `profile.photos.*`.
- Default photo requirements are 9 photos, 3 person photos and 1 full-body photo.
- Local and test profiles override required photos to 4, min person to 1 and min full-body to 1.
- Birth date and gender are immutable after creation.
- Editable fields include display name, bio, city, country, intention and looking-for gender.
- Dynamic matchmaking filters include preferred minimum age, preferred maximum age and maximum distance in kilometers. They are required profile values. Preferred ages are enforced in the basic matchmaking query. Maximum distance is enforced from the current search location sent when a user enters the matchmaking queue.
- Photo positions are unique per profile.
- Removing a required photo can revert an active profile to `DRAFT`.
- File-backed profile photos store provider, bucket and object key. API responses expose renderable read URLs; private storage should use presigned URLs and clients should refresh them instead of treating them as permanent identifiers.

## Account Deletion And Recovery

- `DELETE /api/me` moves the user to `DELETED` and sets `deletedAt` plus `deletionFinalizesAt`.
- The account remains recoverable until `deletionFinalizesAt`.
- During the recovery window, the email and Firebase UID remain reserved and cannot provision a new account.
- Deletion closes active matches/connections and releases engagement locks. Reactivation does not reopen previous engagements.
- Deletion moves the profile back to `DRAFT` while preserving profile data and photos.
- `POST /api/me/reactivation` restores the user to `ACTIVE` only while the recovery window is still open. The profile remains `DRAFT` and must be activated again before matchmaking.
- The account-deletion finalization job anonymizes the email and releases the Firebase UID after the recovery window expires.

## Match Filtering And Compatibility

Hard filtering is first applied in the matchmaking queue query. The query filters:

- active penalties for both queued users
- mutual gender preference
- same intention
- mutual dynamic preferred age range

`SearchLocationMatchFilter` applies mutual dynamic maximum distance from queue search locations before a pair can be accepted. Final ranking is delegated to `CompatibilityScorer`. The current basic scorer uses `CompatibilityEvaluator`, which repeats profile-level compatibility checks as a service-layer guard.

Advanced criteria such as affinity tags or probabilistic scoring are not implemented.

The current matching selector expects scores normalized from `0.0` to `1.0`. Environment properties define the number of SQL-filtered candidate pairs to score, the minimum accepted score and the early-accept score that stops further scoring.
