# TECH_DEBT_PROD

This file lists technical debt, production hardening tasks and long-term product/architecture decisions that are not required for MVP, but should be revisited before meaningful production traffic, store distribution, scaling or stricter trust/safety requirements.

Do not implement these implicitly while working on unrelated tasks.

---

## 1. Production product decisions

### 1.1 Reveal quotas

Decision pending:
- Whether to limit visual reveals, active second matches, or pending visual reviews.
- Whether quotas are daily, weekly, lifetime, or state-based.
- Whether unused reveals expire.

Production reason:
- Controls user overload.
- Prevents gaming.
- Supports the product goal of slower, more intentional matching.

### 1.2 Advanced compatibility scoring

Current behavior:
- Matching uses SQL hard filters plus rule-based compatibility scoring.

Future work:
- Add interest/affinity overlap if useful.
- Avoid popularity, attractiveness or ELO-style ranking.
- Keep scoring explainable enough to debug.

### 1.3 ML-based matching

Deferred:
- ML-based matching is not required until there is real usage data and a clear optimization target.

Constraints:
- Do not introduce attractiveness/popularity ranking.
- Avoid opaque ranking that conflicts with product principles.

### 1.4 Production trust score

Current behavior:
- Trust score evaluator is intentionally neutral.

Future decision:
- Define whether trust score should exist.
- Define inputs and weights:
  - penalty count;
  - penalty severity;
  - penalty recency;
  - abandonment rate;
  - repeated safety reports;
  - positive completion signals.
- Do not expose gamified reputation badges unless product direction changes.

### 1.5 Social authentication providers

Deferred:
- Google Sign-In and other social providers are post-MVP.

Future work:
- Add Google Sign-In through Firebase Auth.
- Define account linking and duplicate-email behavior.
- Ensure account deletion/reactivation works across providers.
- Backend should continue receiving Firebase ID tokens regardless of provider.

### 1.6 Firebase email verification

Current behavior:
- Profile activation requires the current Firebase ID token to have `emailVerified=true`.
- Provisioning, profile creation/editing, photo operations and match-filter configuration do not require email verification.
- Activation rejection uses stable code `EMAIL_NOT_VERIFIED`.

Future work:
- Define Android resend/refresh flow.
- Decide whether additional production actions beyond activation should require a verified email.

---

## 2. Profile authenticity verification

Current state:
- `Profile.authenticityVerificationStatus` is the richer persisted profile-authenticity state and the source of truth.
- `Profile.authenticityVerified` exists as a compatibility projection with the invariant `authenticityVerified == (authenticityVerificationStatus == VERIFIED)`.
- Profile authenticity verification endpoint exists.
- Provider abstraction exists.
- Provider `none` returns `VERIFIED` for MVP/local/dev/test compatibility only; it is not liveness, face comparison, legal identity, document verification or age assurance.
- In `prod`, provider `none` fails explicitly with `AUTHENTICITY_VERIFICATION_NOT_CONFIGURED` and does not persist `VERIFIED`.

Production decision:
- Profile Authenticity Verification is not legal identity verification.
- The future target is liveness-derived live reference plus provider-neutral facial comparison signals for current candidate person photos.
- Reals policy, not the provider, owns the final profile-authenticity domain decision.
- Default MVP policy is `liveReferenceAccepted=true`, matched current candidate person photos >= 3 and contradictory current candidate person photos <= 0.
- `MATCHED` is positive evidence, `UNRESOLVED` is neutral, and `CONTRADICTORY` is comparable facial evidence inconsistent with the accepted live reference.
- Contradictory evidence currently produces `NEEDS_REVIEW`, not automatic `REJECTED`; it does not prove fraud by itself.
- Group photos can be `MATCHED` when at least one comparable face matches the live reference. Old, distant, side-profile, obscured or otherwise poor comparisons may be `UNRESOLVED`.
- `isPersonPhoto` selects authenticity comparison candidates but does not establish that the detected person is the verified user.
- Do not infer authenticity from person detection, full-body detection, moderation approval, `ProfileStatus.ACTIVE` or visual approval.
- Decide whether production profile activation must set `PROFILE_AUTHENTICITY_VERIFICATION_REQUIRE_FOR_ACTIVATION=true`.

Preferred next provider target:
- The currently preferred next real provider target is a self-hosted DeepFace REST API deployed as a separate ML container/service and consumed directly over HTTP by the existing Kotlin/Spring `reals-backend` monolith.
- Do not treat DeepFace as currently configured, deployed or production-approved. The current branch keeps the provider-neutral skeleton active: `ProfileAuthenticityVerificationProvider -> ProfileAuthenticityVerificationSignals -> ProfileAuthenticityPolicy`.
- Do not make providers decide `VERIFIED`, `NEEDS_REVIEW` or `REJECTED` for successful analyses. A future `DeepFaceProfileAuthenticityVerificationProvider` should implement `ProfileAuthenticityVerificationProvider` and map DeepFace-specific responses into `liveReferenceAccepted` plus `MATCHED`, `UNRESOLVED` and `CONTRADICTORY` photo outcomes. `ProfileAuthenticityPolicy` remains responsible for the final Reals authenticity status.
- The intended deployment shape is `reals-backend -> HTTP -> DeepFace REST API container`.
- A custom Reals Python/FastAPI adapter or Reals-owned ML microservice is not the current target. A dedicated Reals ML service may be reconsidered later if orchestration grows to multiple ML engines, custom models, GPU workload management, batching, queues or model-version lifecycle.

Preferred future DeepFace flow:
1. Android captures a fresh camera image specifically for profile-authenticity verification.
2. A future backend authenticity-verification input/session contract carries that live-reference capture. The current endpoint does not accept this input and does not implement camera freshness or liveness.
3. The DeepFace provider evaluates passive anti-spoofing/liveness for the live-reference capture only and maps that result to `liveReferenceAccepted`.
4. If the live reference is accepted, the provider compares it against current authenticity photo candidates: `validationStatus == VALIDATED AND isPersonPhoto == true`.
5. The provider maps each candidate comparison into `MATCHED`, `UNRESOLVED` or `CONTRADICTORY`.
6. `ProfileAuthenticityPolicy` applies configured `min-matched-person-photos` and `max-contradictory-person-photos`.

DeepFace REST usage direction:
- The expected integration may use DeepFace REST operations conceptually like `/represent` with anti-spoofing enabled for the live-reference capture, followed by `/verify` without anti-spoofing for live-reference versus profile-photo comparisons.
- This is not a permanent Reals HTTP contract. Exact endpoint orchestration must be validated against the pinned DeepFace version selected during implementation.
- Do not run anti-spoofing against historical profile photos. A historical profile photo is not expected to prove current physical presence.

Remaining design requirements before implementation:
- Define the future HTTP/session contract carrying the live-reference capture.
- Define Android fresh-camera capture UX and whether gallery/imported images are forbidden for the live reference.
- Define live-reference size limits and technical image validation.
- Define temporary live-reference handling.
- Define reference-image retention versus immediate deletion.
- Define biometric/privacy policy.
- Pin the exact DeepFace version.
- Select the exact face-recognition model and detector backend.
- Define similarity/distance thresholds and mapping to `MATCHED`, `UNRESOLVED` and `CONTRADICTORY`.
- Define DeepFace API authentication and network exposure.
- Define provider connect/read timeouts.
- Size container CPU and memory requirements.
- Define model startup/readiness behavior.
- Decide how DeepFace accesses current profile-photo content. Current `S3StorageService` does not expose an internal object-read operation for provider analysis, so the real provider implementation must either add an internal read path or provide short-lived provider-accessible URLs.
- Define retry behavior.
- Define `NEEDS_REVIEW` operational workflow.
- Define provider/model/version audit metadata.
- Review licenses for the exact DeepFace-wrapped recognition and detector models selected for production.
- Avoid persisting face embeddings or biometric templates in the Reals relational database unless a future design explicitly requires and reviews that decision.
- Keep age assurance and legal/document verification separate.
- Decide whether profile authenticity verification is:
  - optional;
  - required for activation;
  - required only after trust/safety escalation;
  - required for certain user actions.

---

## 3. Profile photo validation and moderation

### 3.1 Future upload lifecycle

Implemented production photo pipeline:
- identity provider `none` does not create `VERIFIED` in `prod`;
- photo moderation provider `none` does not create `APPROVED` in `prod`;
- technical photo validation does not create person/full-body semantic facts in `prod`;
- production activation defaults to requiring `APPROVED` moderation.
- minimal backend admin moderation review is implemented for `NEEDS_REVIEW -> APPROVED` and `NEEDS_REVIEW -> REJECTED`.
- production temporarily defaults minimum full-body photos to `0` because no real full-body detector exists.
- Sightengine can be enabled with `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine`.
- Sightengine performs one synchronous multipart request per upload/replacement after technical validation, requesting `face-analysis`, `nudity-2.1`, `violence`, `gore-2.0` and `offensive-2.0`.
- Sightengine real face presence provides an MVP person-photo signal: at least one `faces` entry sets `isPersonPhoto=true`, while `artificial_faces` do not count.
- Sightengine model output is mapped to Reals-owned moderation signals for sexual explicit, sexual suggestive, violence/threat, gore and hate/extremism policy.
- The existing admin moderation queue handles `NEEDS_REVIEW` outcomes.

Current shortcut split:
- Outside `prod`, `validationStatus=VALIDATED`, `isPersonPhoto=true` and `isFullBody=true` are preserved for MVP/local/dev/test compatibility after technical upload validation.
- In `prod`, technical upload validation alone leaves photos as `validationStatus=PENDING`, `isPersonPhoto=false` and `isFullBody=false`.
- Outside `prod`, moderation provider `none` returns `APPROVED` for compatibility.
- In `prod`, moderation provider `none` returns `NEEDS_REVIEW`.
- Sightengine face analysis is not profile authenticity verification, legal identity verification, facial recognition, face matching, liveness, age estimation, minor detection or a full-body detector.
- Sightengine moderation does not solve child safety, CSAM/CSAE handling, legal escalation or user sanctioning.

Current synchronous target:
1. User uploads a photo.
2. Backend performs technical checks.
3. If upload succeeds and Sightengine is configured, one provider request returns face and moderation signals.
4. Semantic policy updates `validation_status`, `isPersonPhoto` and `isFullBody`.
5. Reals moderation policy updates `moderation_status`.
6. Optional provider/model/version metadata is still not recorded.

### 3.2 Future activation analysis

Decision pending:
- Whether activation-time analysis should process all photos or only enough photos to satisfy the required counts.

Option A:
- Analyze all profile photos before activation.
- Preferred if all photos will be visible to other users.

Option B:
- Analyze only until minimum activation requirements are satisfied.
- Remaining photos stay non-counting/non-visible until validated.

Preferred long-term direction:
- Analyze all photos that will be visible to other users.
- Avoid showing unvalidated photos in visual review.

### 3.3 Future moderation workflow for photos

Future work:
- True person detection beyond face presence.
- Full-body detection.
- Face/person consistency and group/other-person validation.
- Age estimation/minor-risk analysis if product/legal policy requires it.
- CSAM/CSAE tooling, legal escalation and external-authority reporting procedures.
- More nuanced nudity/context rules.
- Broader weapon, self-harm or restricted-substance policy if desired.
- Sightengine provider/model/version/request metadata persistence.
- Raw normalized signal persistence if operationally justified.
- Rejected-media retention/removal policy.
- Expand manual photo-review operations beyond the minimal backend queue, including reopen/override and removal workflows.
- Define provider artifact privacy/retention.
- Define whether rejected photos are deleted, hidden, quarantined or retained for audit.
- Add an admin web UI for the backend review queue.
- Define reopen/override workflow for already resolved moderation decisions.
- Decide whether and how rejected photo moderation should feed safety reports or penalties; no automatic safety report, ban or penalty exists now.
- Define operational retention/removal policy for rejected media.
- Define asynchronous callbacks/webhooks only if a future provider requires them.
- Review provider privacy/retention before meaningful production traffic.

Historical production data limitation:
- The backend does not persist enough historical provider/analyzer identity to safely determine which old positive rows came from MVP shortcuts and which could theoretically come from another implementation.
- Before meaningful production traffic, any existing prod data created while the MVP none/semantic shortcuts were active must be inventoried and either deleted, reset or revalidated according to an explicit operator decision.

### 3.4 Media storage production work

Implemented:
- Profile-photo metadata in PostgreSQL is authoritative.
- New profile-photo objects are protected by delayed `DELETE_OBJECT` cleanup guards before upload; successful DB finalization removes the guard in the same transaction that persists the `ProfilePhoto`.
- Replacements upload to a new object key. The old object is never deleted before the DB transaction commits the new reference.
- Deletes and replacements create durable cleanup tasks for old objects after DB metadata is removed or replaced.
- Object deletion is idempotent: an already absent object is a successful cleanup.
- Cleanup processing is bounded and retryable through `media_cleanup_tasks`; successful deletion removes the task row, while `FAILED` tasks require operational inspection.
- Existing stored objects are not backfilled. They become managed when later replaced or deleted.
- Defaults: `scheduler.media-cleanup-job.fixed-delay=300000`, `storage.media-cleanup.batch-size=100`, `lease-duration=PT5M`, `guard-delay=PT30M`, `initial-retry-delay=PT1M`, `max-retry-delay=PT1H`, `max-attempts=10`.

Future work:
- Object lifecycle rules.
- Malware/content scanning if required.
- CDN/cache strategy.
- Thumbnail generation.
- Dimension normalization.
- Avoid proxying image bytes through backend unless intentional.
- Presigned direct-to-S3 upload architecture remains out of scope.

---

## 4. Safety and moderation

### 4.1 Full safety report workflow

Current state:
- Safety cancellation/report can record a report and apply a penalty.
- BACK-5 explicit child-safety concern reporting is implemented. `CHILD_SAFETY_CONCERN` is supported by direct safety reports and chat safety cancellation, remains `PENDING`, and receives derived (non-persisted) priority ordering while pending.
- BACK-5 preserves existing user-created block/containment behavior and does not automatically penalize or ban the reported user; penalties still require explicit admin confirmation.
- User-created reports can target chat, visual profile, personal message and profile-photo contexts when the backend can validate a real interaction.
- User-created reports automatically create a directional user block, and matchmaking treats a block in either direction as a bidirectional exclusion.
- Admin-created reports can be general `USER` context reports or contextual reports. Admin-created reports do not auto-block, auto-close chats or auto-apply penalties.
- Safety reports capture an evidence snapshot with message counts, timestamps and transcript hash, not a full transcript copy.
- Safety-relevant backend flows record `audit_events` with operational metadata only.
- Admin safety DTOs intentionally avoid raw email, Firebase UID and full `User` exposure.
- Pending/confirmed report counters are computed dynamically for the reported user.
- Full manual review workflow is not complete.

Production work:
- Admin queue for reports.
- Report status transitions.
- Moderator notes.
- Evidence/context view.
- Escalation policy.
- Appeal or correction policy if needed.
- Request context enrichment for audit events, including request id and hashed IP/user-agent if needed.
- Additional admin filters/pagination if report volume grows.
- Decide when admin-created reports should optionally create blocks or other containment actions.
- Broader child-safety moderation remains deferred: automated detection, image/content moderation, age estimation, provider escalation, a dedicated specialist workflow, legal/regulatory reporting procedures, external-authority reporting procedures, and CSAM/CSAE scanning or classification.

### 4.2 User blocking and objectionable content controls

Current state:
- The manual user-facing backend block command and Android manual-block UI are implemented.
- Android presents definitive-action confirmation before manual block submission.
- Reporting and blocking remain separate user actions.
- Report-created and manual blocks enforce pair-wide matchmaking exclusion.
- Active-interaction containment and a central positive-progression guard are implemented.
- Blocks are durable and there is no unblock endpoint or UI in the current MVP.

Future work:
- Define future unblock policy and admin correction behavior.
- Explicit policy for objectionable profile photos and messages.
- Internal tooling to remove content and sanction users.
- Define and implement future `SYSTEM` safety report sources if automated detection creates reports.

### 4.3 Sensitive message data protection

Current MVP stance:
- Chat and personal message contents are stored as application text.

Before production:
- Define retention/deletion rules.
- Keep request/response bodies out of logs.
- Restrict and audit internal access.
- Evaluate application-level field encryption with keys stored outside the database.
- Define data export/deletion behavior for account deletion if required.

### 4.4 Backoffice production access hardening

Current state:

* Admin endpoints are exposed under `/api/admin/**`.
* Access is protected at application level with `ROLE_ADMIN`.
* Admin role is currently derived from configured admin email allowlist.
* There is no dedicated backoffice UI yet.
* CORS is not currently a meaningful protection for non-browser tools such as Bruno, Postman, curl, or internal scripts.

Before production backoffice usage:

* Define the production backoffice access model:

  * internal-only tooling;
  * browser-based admin UI;
  * VPN/Zero Trust protected access;
  * or public internet endpoint with stricter controls.
* If a browser-based backoffice UI is introduced, add restrictive CORS:

  * allow only the official admin origin, e.g. `https://admin.reals.app`;
  * allow dev/staging admin origins only in non-prod profiles;
  * do not use wildcard origins for admin endpoints;
  * keep `allowCredentials=false` while using Bearer tokens;
  * revisit CSRF if cookie-based admin sessions are ever introduced.
* Require MFA for all admin accounts through the chosen identity provider.
* Revisit admin role assignment:

  * current email allowlist is acceptable for MVP/dev;
  * production should consider Firebase custom claims, persisted admin roles, or another auditable role management mechanism;
  * avoid relying indefinitely on static config-only admin identity.
* Add admin-specific rate limiting:

  * protect `/api/admin/**` from accidental scripts, abusive clients, and brute-force-like usage;
  * keep limits separate from user-facing safety report rate limits.
* Consider network-layer restrictions for backoffice:

  * VPN;
  * Cloudflare Access / Zero Trust;
  * IP allowlist;
  * private ingress;
  * or equivalent platform-level access control.
* Keep application-level authorization even if network restrictions are added.
* Strengthen admin audit coverage:

  * record admin user id;
  * record action type;
  * record target aggregate/entity;
  * record result/success/failure;
  * record timestamp;
  * avoid storing raw sensitive content unless explicitly required;
  * avoid exposing raw email, Firebase UID, message bodies, report details, or storage keys in broad admin summaries.
* Add operational visibility for admin usage:

  * count admin requests by endpoint;
  * alert on repeated forbidden/unauthorized admin access;
  * alert on unusual admin activity volume;
  * monitor admin action failure rates.
* Define admin session/token operational policy:

  * expected token lifetime;
  * reauthentication expectations for sensitive actions;
  * behavior when admin access is revoked;
  * emergency admin lockout/revocation procedure.
* Document which admin actions are safe read-only actions and which are mutating/escalating actions.
* Consider requiring explicit confirmation or stronger authorization for high-impact actions:

  * confirming/dismissing safety reports;
  * applying sanctions;
  * removing content;
  * changing user status;
  * accessing sensitive evidence views.

Non-goals for MVP:

* Do not add a full admin role management system until there is real backoffice usage.
* Do not introduce cookie-based admin sessions unless there is a clear product/security reason.
* Do not treat CORS as a replacement for authentication, authorization, MFA, audit, or network-level access controls.


---

## 5. Location and geographic matching

### 5.1 Canonical city/locality reference data

Current country state:
- Profile country identity uses `Profile.countryCode`, a canonical uppercase ISO-3166 alpha-2 code.
- `GET /api/reference/countries` returns the backend country reference list for clients.
- The country catalog is built once from Java runtime ISO country data with Spanish display names and kept as immutable in-memory reference data.

Future city/locality work:
- Validate profile `city` against a canonical locality dataset.
- Consider optional city/region reference endpoints if the product needs structured city selection.
- GeoNames or a similar dataset may be considered for future city/locality data, but it is not part of the current country reference implementation.
- Avoid embedding global reference data in `GET /api/me/profile`.

### 5.2 Geographic search optimization

Future work:
- Improve geographic matching with:
  - geohash;
  - bounding boxes;
  - spatial database indexes;
  - location buckets;
  - region partitioning.
- Revisit if queue volume makes application-level Haversine filtering too expensive.

### 5.3 Accuracy meters policy

Decision pending:
- How `accuracyMeters` should affect matchmaking.

Future options:
- Reject imprecise locations.
- Expand effective radius for imprecise locations.
- Warn user but allow queue entry.
- Store and ignore, as currently.

---

## 6. Database, query and data-volume hardening

Likely first production bottleneck:
- PostgreSQL.

Before meaningful production traffic:
- Add query latency monitoring.
- Track slow queries.
- Review indexes for:
  - `matchmaking_queue`;
  - `profiles`;
  - `penalties`;
  - `matches`;
  - `connections`;
  - `chat_sessions`;
  - `chat_messages`;
  - `schedule_negotiations`;
  - `schedule_proposals`;
  - `active_engagement_locks`.
- Track connection pool usage.
- Track database CPU.
- Track lock wait time and deadlocks.
- Add pagination/cursor access where payloads can grow.

Important metrics:
- HTTP latency by endpoint.
- DB query latency.
- DB connection pool saturation.
- Deadlock count.
- Table growth.
- Queue sizes and job backlogs.

---

## 7.1 Matchmaking scalability

Risk:
- Current matchmaking job is safe but serialized.
- Queue backlog can grow if users enter faster than the job processes.

Before scale:
- Track matchmaking queue size.
- Track average time in queue before match.
- Track processed/skipped/failed pairs per run.
- Tune:
  - `scheduler.matchmaking-job.fixed-delay`;
  - `scheduler.matchmaking-job.max-pairs-per-run`.
- Add indexes based on actual query plans.
- Consider partitioning/filtering by:
  - location bucket;
  - intention;
  - gender/preference group;
  - age range.

Future option:
- Introduce parallel matchmaking workers only if backlog or latency requires it.
- Keep PostgreSQL `SKIP LOCKED`.
- Add stronger database constraints.
- Add concurrency tests for duplicate match prevention.

PR1 query-refactor follow-up:
- Queue CRUD and candidate discovery are now separate repository responsibilities.
- Candidate selection uses JDBC/native PostgreSQL SQL with independent
  active-interaction and historical-cooldown fragments so local repeatable mode
  can omit active duplicate and/or historical predicates without weakening
  user-block, profile, queue, penalty, age, gender or distance filters.
- Defensive pair checking uses one focused database query after deterministic
  user locking and before match persistence.
- Added pair/state indexes support the current active and cooldown lookups.

PR2 scalable-claim follow-up:
- Candidate claiming now locks one eligible anchor row, reads a bounded
  non-locking partner window, ranks partners in application code and claims one
  selected partner row at a time with hard revalidation.
- Exact mutual Haversine distance runs in SQL before partner `LIMIT`, while the
  Kotlin distance filter remains as a defensive parity check.
- Partner contention is normal: a missed partner claim falls back to the next
  ranked candidate without counting as a failed matchmaking pair.
- Probabilistic partner ranking is implemented behind
  `matchmaking.ranking.mode`. Do not enable it in production before calibrating
  parameters from real reliability distributions, adding observability for
  selected FIFO rank, reliability gaps, waiting-time percentiles and fallback
  frequency, reviewing fairness/starvation effects, designing user-facing
  reliability guidance, evaluating a future gradual compatibility scorer and
  deciding whether ranking parameters should differ by market/liquidity.
- Deferred future work: PostGIS, spatial indexes based on real `EXPLAIN`
  evidence, canonical pair columns, derived pair-exclusion tables, materialized
  eligibility caches, queue partitioning, sharding, multi-region matchmaking
  and durable pagination or rotation for extremely large partner windows.

---

## 7.2 UserReliabilityScore cache and production scaling

`UserReliabilityScore` is initially computed from active `user_reliability_events`.

This is acceptable for MVP/early dev because:

* only recent events affect the score;
* the active scoring window is short;
* the expected number of reliability events per user is low;
* the score is not user-visible;
* the score does not require instant, event-by-event consistency.

The current design should keep reliability events as the source of truth.

```text
effectiveScore = baseScore + weightedSum(activeReliabilityEvents)
```

Temporal weighting must be applied at calculation time. Event deltas should not be mutated when they move from full weight to half weight.

Expected weighting model:

```text
0-9 days:
  100% weight

10-19 days:
  50% weight

20+ days:
  expired
```

Expired reliability events may be deleted by a scheduled cleanup job because long-term operational audit should live outside the scoring table.

### Future cache / materialized score

If matchmaking starts evaluating large candidate pools or score calculation becomes a measurable bottleneck, add a cache or materialized score table.

Suggested future table:

```text
user_reliability_score_cache
  user_id
  effective_score
  calculated_at
  valid_until
```

A simple TTL-based cache is acceptable. `UserReliabilityScore` does not require strict instant consistency. Small delays are acceptable because the score is used as a soft matchmaking modifier, not as a safety or access-control mechanism.

Recommended behavior:

```text
if cache exists and valid_until > now:
  use cached score

else:
  recompute score from active reliability events
  upsert cache row
```

The cache must be reconstructable from `user_reliability_events`.

### Cache invalidation rules

A future cache implementation should consider these invalidation triggers:

* new reliability event created;
* reliability event cleanup job deletes expired events;
* scoring configuration changes;
* cache TTL expires.

A minimal implementation may rely only on TTL-based lazy recomputation, as long as the resulting staleness is documented and bounded.

Do not implement a cache that can remain stale indefinitely.

### Matchmaking usage

Matchmaking should avoid per-candidate N+1 score queries.

Preferred MVP shape:

```text
getEffectiveScores(userIds: Set<UUID>): Map<UUID, Int>
```

This allows the matchmaking processor to fetch scores in batch for the current candidate pool.

If a cache is added later, the same service boundary should remain stable so matchmaking does not need to know whether scores are computed from events or read from a cache.

### Non-goals

Do not use `UserReliabilityScore` cache for:

* user-visible score display;
* safety/moderation sanctions;
* hard bans;
* permanent user classification;
* overriding core eligibility, blocks, locks, distance filters or compatibility constraints.

The reliability score should remain a bounded, recoverable, short-memory matchmaking modifier.


## 8. Home and polling pressure

Risk:
- Home is likely to become one of the most frequently requested endpoints.
- It aggregates match, chat, visual review, connection, scheduling, second-chat and blocked-state information.

Implemented first step:
- Full `GET /api/me/home` remains the source of truth and keeps its existing response contract.
- `GET /api/me/home/pending` returns lightweight pending/actionable navigation state for future polling without partner summaries or the full Home summary.
- `GET /api/me/home/status` returns a persisted per-user Home `version`, `dirty` flag and `serverTime`.
- Home-relevant state transitions bump the persisted version in PostgreSQL, so change detection works across multiple backend instances.
- Android can later poll `/api/me/home/status` and only call full Home when the version changes.
- Full and pending Home now share one operational snapshot-loading path for blocked users, matches, visual reviews, chats, decisions, connections, negotiations and pending scheduling state.
- First-chat `ChatDecision` reads for Home are batched by match id, removing the per-active-match N+1 read.
- `reals.home.load` records full/pending Home service duration with bounded tags `variant` and `outcome`.
- Hibernate Statistics query-count tests measure the current fixture shape without enabling production Hibernate statistics.
- H2 fixture measurements from the production-readiness block: full/no-interaction `12`, pending/no-interaction `6`, full/one active first chat `16`, full/three active first chats `18`, pending/one active first chat `8`, pending/three active first chats `8`. These are test-fixture measurements, not production guarantees.

Before scale:
- Continue reviewing generated queries against representative production data.
- Reduce polling only after the implemented push paths are reliable enough for
  the relevant product surfaces; polling remains the source-of-truth fallback.
- Keep the persisted version semantics and invalidation hooks correct before introducing stronger caching or projections.

Future options:
- Full PostgreSQL read model/projection: add a `user_home_projection` table with precomputed pending/Home JSON or structured columns if full Home aggregation becomes expensive.
- Redis/Valkey distributed cache: cache `HomeResponse` or pending state per user and invalidate on Home status bumps after correctness and invalidation are stable.
- ETag / `If-None-Match` / `304`: use the Home version as the basis once version semantics are stable.
- Push-driven refresh: Firebase Cloud Messaging can notify clients to refresh Home status or full Home; polling should remain the fallback.
- Realtime transport: WebSocket or SSE should be considered only if chat/Home polling plus push is not enough, and must use shared pub/sub or managed infrastructure in multi-instance mode.

---

## 9. Chat scaling and realtime transport

### 9.1 Chat message scaling

Risk:
- Chat polling and message history can create high read volume.
- Fetching full message history does not scale well for long chats or frequent polling.

Implemented first step:
- Chat message reads accept optional `limit` with default 200 and maximum 500.
- Initial legacy reads return the most recent bounded page as the existing JSON array, ordered chronologically.
- Incremental reads use cursor pagination ordered by `sentAt ASC, id ASC`, request `limit + 1`, and set real `hasMore`.
- The supporting index is `idx_chat_messages_session_sent_at_id` on `(chat_session_id, sent_at, id)`.
- `reals.chat.messages.read` records initial/incremental read duration with bounded tags `mode` and `outcome`.
- `reals.chat.messages.returned` records successful returned-message counts with bounded tag `mode`.
- PostgreSQL/Testcontainers profile `postgres-it` includes a 5,000-message baseline for bounded initial and incremental reads, UUID tie-break pagination and V30 index presence. Local durations are diagnostic logs, not CI performance thresholds.

Future work:
- Message reactions are persisted on existing message rows, so changing `reaction_type` does not advance the `(sentAt, id)` cursor. Cross-device synchronization for old-row reaction mutations requires a future reaction event stream, updated-at cursor, tail polling strategy or equivalent product decision.
- Add broader product/traffic metrics only after there is real usage volume:
  - messages sent per minute;
  - active chats;
  - unread/recovery behavior if product adds it.

### 9.2 Push notifications

Implemented:
- Firebase Cloud Messaging sender integration is active in `local-firebase`,
  `dev` and `prod` profiles.
- `PUT /api/me/push-tokens` persists Android FCM registration tokens for the
  authenticated user.
- Token registration has refresh/upsert semantics by registration token: it
  reassigns the token to the current user, re-enables it and refreshes
  `lastSeenAt`.
- Multiple active tokens per user are supported; one logical user-level
  reminder can result in multiple provider sends.
- Delivery results are persisted in `push_notification_deliveries` as `SENT`,
  `FAILED` or `SKIPPED_NO_ACTIVE_TOKEN`; `SENT` means at least one provider
  delivery succeeded.
- Delivery deduplication uses the existing unique key over user, notification
  type and aggregate id.
- The visual-review reminder job and manual local-dev trigger are implemented.
- Second-chat reminder, scheduling-available, scheduling-proposals-received and
  scheduling-confirmed notification paths are implemented where their current
  services prove eligibility and payload behavior.
- FCM/provider transport calls execute outside active database transactions.
- Notification preparation and delivery-result persistence use short transactions.
- Aggregate locks are released before transport; this specifically prevents the visual-review reminder from holding the `VisualReview` pessimistic lock while Firebase is called.
- Current provider-call entry points are scheduler/dev-job driven after product transitions commit.
- Android-compatible string data contracts are present for implemented
  notification types. The backend also supplies display title/body through the
  FCM notification payload.

Representative verification:
- On July 21, 2026, a `local-firebase` smoke with a signed optimized Android
  `localRelease` installation verified Firebase Authentication, Firebase App
  Check verification, Android FCM token registration, manual visual-review
  reminder execution through the local-dev job path, representative provider
  delivery and Android device reception.
- That evidence does not verify production delivery rates, Play Integrity, all
  OEM background modes, retries or remote deployment.

Still deferred or incomplete:
- Exact delivery claiming/outbox semantics, a `PENDING` state, retries and
  backoff remain deferred.
- Delivery deduplication remains best effort through the existing
  `(userId, notificationType, aggregateId)` unique key. Duplicate push delivery
  remains theoretically possible if concurrent workers prepare before either
  persists a delivery row.
- A push can become stale after preparation if the user completes the action
  before transport.
- Production delivery observability, dashboards and alerts remain deferred.
- Notification-open analytics and routing instrumentation remain deferred; Home
  refresh remains the source of truth after notification taps.
- Broader notification event coverage remains deferred beyond the currently
  implemented MVP event paths.
- Foreground/background presentation consistency remains deferred.
- Old-token lifecycle policy, including device/session ownership and cleanup
  after refresh/logout, remains deferred.
- Production remote-environment FCM and App Check verification remains
  unverified.

Observed stale-token cleanup gap:
- During the July 21, 2026 `local-firebase` smoke, two provider attempts returned
  textual `NotRegistered`.
- After the run, the corresponding database rows were still observed as enabled.
- Current code is intended to disable provider-declared invalid tokens, but the
  observed result indicates that the mapping or cleanup path requires focused
  investigation.
- The precise root cause is not asserted here; verify it against the pinned
  Firebase Admin SDK exception and error-code forms before changing code.
- Do not enforce one token per user as a fix because multiple legitimate
  devices per user must remain possible.

Acceptance criteria for the later cleanup fix:
- Provider-declared unregistered or invalid tokens are deterministically
  disabled.
- Valid tokens for the same user remain enabled.
- Partial success is persisted without losing invalid-token cleanup.
- Tests cover the actual Firebase exception and error-code forms used by the
  pinned SDK.
- No raw registration token is logged.

Notification-copy contract debt:
- The backend currently supplies display title/body plus data fields for the
  implemented notification types.
- Android may render recognized data notifications using its own fixed copy, so
  one logical notification type can have different foreground/background copy.
- This is non-blocking for the completed local smoke.
- A later task must explicitly choose either unified mixed-payload copy or a
  data-only presentation contract.

### 9.3 Realtime transport

Not required for MVP.

Future options:
- WebSocket.
- Server-Sent Events.
- Managed realtime service.
- Push-notification-driven refresh.

If realtime chat is introduced:
- Use shared pub/sub or managed infrastructure.
- Do not rely on in-memory connection state when more than one backend instance exists.
- Keep REST polling fallback.
- Define reconnect behavior.
- Define missed-message recovery.
- Keep durable message persistence.

---

## 10. Lifecycle jobs and reconciliation

### 10.1 Lifecycle job hardening

Implemented hardening:
- Frequent lifecycle jobs process bounded deterministic batches and leave remaining backlog for later scheduled runs.
- `ChatTimeoutJob`, `InactivityCheckJob`, `SecondChatReminderNotificationJob`, `SchedulingActivationJob`, `SecondChatLifecycleJob`, `MatchExpirationJob` and `VisualPhaseExpirationJob` have configurable batch sizes.
- Candidate queries order by the relevant due timestamp and stable UUID tie-breaker where practical.
- One candidate failure is isolated and does not abort the rest of the batch.
- Jobs do not loop to drain an entire backlog, and no worker queues, parallel executors or generic job framework were introduced.
- Matchmaking now runs every `15000` ms by default; `maxPairsPerRun`, candidate-pair limits and scalable queue claiming remain unchanged.

Remaining before scale:
- Add metrics for:
  - processed;
  - succeeded;
  - skipped;
  - failed;
  - duration;
  - backlog.
- Add indexes for status/deadline fields used by jobs.
- Add retry/no-op handling for expected stale states.
- Add alerts for stuck records.
- Low-frequency jobs that remain intentionally outside this batching block: visual-review reminder, penalty expiration, scheduling negotiation timeout, user reliability cleanup and account deletion finalization.
- Micrometer backlog gauges remain deferred to the observability block.

Relevant domains:
- First-chat expiration.
- Visual-review expiration.
- Scheduling activation.
- Scheduling expiration.
- Second-chat availability.
- Second-chat expiration.
- Account deletion finalization.
- Penalty expiration/cleanup.

### 10.2 State consistency diagnostics

Future work:
- Add diagnostics for inconsistent or partially advanced states:
  - visual-approved match without connection;
  - connection without expected locks;
  - closed connection with remaining locks;
  - confirmed schedule without second-chat availability;
  - second-chat available but expired;
  - duplicated penalties;
  - stale queue rows for users with active engagements.

Production preference:
- State transitions should be idempotent.
- Re-running a transition should either complete missing dependent records or no-op safely.

---

## 11. Concurrency hardening

Current hardening:
- Chat message writes now lock the target `Chat` row with a pessimistic write lock before final validation, `ChatMessage` insert, and `lastMessageAt` update. This serializes writes per chat while keeping different chats independent.
- Firebase provisioning retries at most one concurrent UID/email unique-conflict attempt, and the retry reruns the authoritative provisioning decision in a fresh transaction.
- Notification provider calls run after short eligibility/token preparation commits, with result persistence in a separate short transaction.
- Targeted PostgreSQL/Testcontainers coverage now explicitly verifies:
  - Home query-count shape on real PostgreSQL;
  - bounded message reads and native cursor ordering on PostgreSQL;
  - concurrent messages in one first chat;
  - concurrent activation of one available second chat;
  - concurrent Firebase provisioning with PostgreSQL unique constraints.

Still deferred:
- The first-chat `ChatDecision` creation race is intentionally unchanged here. Android allows a manual retry immediately after the failed request completes and `actionLoading` is reset; there is no automatic or rapid-fire retry.
- Exhaustive PostgreSQL concurrency coverage remains deferred; H2 service tests are still not proof for every PostgreSQL lock interaction.
- Exact notification delivery claiming/outbox/retries, broad observability, rate limiting and PostgreSQL/Testcontainers media-concurrency coverage are out of scope for this block.
- DB/object-storage consistency through durable profile-photo cleanup tasks is implemented. Presigned upload/direct-to-S3 architecture remains a separate future design.

Before scale, keep explicit concurrency tests for:

### 11.1 Mutual visual approval

Assert:
- only one connection is created;
- locks are upgraded once;
- repeated/competing approvals are idempotent.

### 11.2 Scheduling confirmation

Assert:
- only one confirmed negotiation result exists;
- only one second-chat availability transition occurs;
- repeated job runs are safe.

### 11.3 Second-chat materialization

Assert:
- only one second chat exists per connection;
- concurrent entry/send attempts do not create duplicates.

### 11.4 Chat cancellation and safety reports

Assert:
- terminal chat state is set once;
- penalties are not duplicated;
- exit requests are not duplicated.

### 11.5 Account deletion

Assert:
- active locks are removed;
- queue rows are removed;
- active engagements are closed;
- repeated deletion attempts return stable behavior.

### 11.6 Active engagement lock hardening

Future work:
- Document lock acquisition rule:
  - operations involving two users must lock both users in canonical user-id order.
- Ensure all new match/connection flows follow that rule.
- Add diagnostics for locks referencing terminal matches/connections.
- Add cleanup for orphaned locks.
- Consider additional database constraints where invariants can be expressed.

---

## 12. Observability and alerting

Implemented narrow diagnostics:
- Custom Micrometer meters are enabled under `management.metrics.enable.reals=true` while `management.metrics.enable.all=false` remains the default stance.
- `/actuator/health` and `/actuator/health/**` remain public.
- `/actuator/info`, `/actuator/metrics` and `/actuator/metrics/**` require `ROLE_ADMIN`.
- Current custom meters:
  - `reals.home.load` with `variant=full|pending` and `outcome=success|error`;
  - `reals.chat.messages.read` with `mode=initial|incremental` and `outcome=success|error`;
  - `reals.chat.messages.returned` with `mode=initial|incremental`;
  - `reals.app_check.requests` with bounded `mode`, `outcome`, `endpoint_group` and `exception` tags.
- No user id, chat id, match id, cursor id, raw path, token, complete JWT claim set, HTTP status or message count is used as a metric tag.
- No Prometheus registry, Grafana dashboard, OTLP exporter, distributed tracing or production Hibernate statistics is configured.

Before production scale:
- Add structured logs with request/job identifiers where operationally useful.
- Add metrics export only when the runtime target is selected.
- Add alerts for:
  - matchmaking backlog;
  - job failures;
  - high API latency;
  - DB connection pool saturation;
  - repeated deadlocks;
  - stuck negotiations;
  - stale active locks;
  - failed notification delivery;
  - elevated auth failures;
  - elevated chat send failures.

Core product metrics:
- Time to first match.
- First-chat completion rate.
- Visual approval rate.
- Scheduling confirmation rate.
- Second-chat attendance rate.
- Safety report rate.
- Penalty rate.
- Account deletion/reactivation rate.

---

## 13. Firebase Auth and request volume

Risk:
- Auth verification and local user lookup happen on many API requests, especially with polling.
- `FirebaseTokenFilter` validates Firebase ID tokens on every protected request and caches only successful revocation/disabled-user checks for `security.firebase-auth.revocation-cache-ttl` (`PT60S` by default). External Firebase revocation can therefore be detected up to one TTL later for a recently checked token, while local deleted-account enforcement remains immediate.
- Firebase App Check adds one JWT verification step before Firebase Authentication when enabled. JWKS keys are library-cached and rotated through Firebase JWKS, but first use or key refresh can still depend on Firebase JWKS availability.

Before scale:
- Measure auth-filter latency and the cost of revocation verification under expected request volume.
- Measure App Check verification latency and unavailable rates after `MONITOR` rollout.
- Do not remove revocation checking casually: deletion/session invalidation relies on rejected revoked tokens after the configured cache window.
- Monitor 401/403 rates by reason.
- Monitor App Check `missing`, `invalid` and `unavailable` outcomes before enforcing.
- Avoid Android refresh loops.
- Consider short-lived caching of local user/session metadata if DB reads become excessive.

---

## 14. Infrastructure and deployment hardening

### 14.1 Production deployment model

Before production:
- Choose runtime platform.
- Choose managed PostgreSQL.
- Define backup/restore strategy.
- Define secrets management.
- Define rollback strategy.
- Define health/readiness checks.
- Define release image tagging.
- Define dev/prod environment separation.

### 14.2 Release image tagging

Future work:
- Dev can use moving `development` and immutable `sha-*` tags.
- Production should publish immutable `v*` tags from Git tags, such as `v1.0.0`.

### 14.3 Infrastructure as Code

Decision pending:
- Whether infrastructure should be represented as IaC.

Recommendation:
- If first provider is AWS, prefer Terraform or AWS CDK.
- If first provider is Render, Fly.io or Railway, start with service config and documented manual steps until shape stabilizes.

### 14.4 Kubernetes / Helm

Current stance:
- Helm-style values under `deploy/helm` are placeholders.
- Kubernetes, Helm and Terraform/CDK become worthwhile when there are multiple services, networking rules, autoscaling needs or repeatable environment provisioning requirements.

---

## 15. Framework and dependency maintenance

Future maintenance:
- Keep Spring Boot on the latest stable `4.0.x` patch line until `4.1.x` is stable and release notes have been reviewed.
- Remove temporary `tomcat.version` override once Spring Boot manages a sufficiently patched Tomcat version.
- PostgreSQL remains the only supported non-local database driver unless a concrete environment requires another one.
- Local H2/file-storage profiles remain local-only; external environments should use PostgreSQL plus Flyway.

---

## 16. Security decisions for future auth models

### 16.1 CSRF

Current stance:
- CSRF protection is disabled while Reals remains a stateless API with explicit bearer tokens.

Future requirement:
- Re-enable and test CSRF before introducing:
  - cookie authentication;
  - form login;
  - browser-managed sessions;
  - credentials automatically attached by browsers.

### 16.2 Sensitive logs and audit

Production work:
- Define sensitive-field log policy.
- Keep tokens, chat contents, personal messages, full emails, private media URLs and raw request bodies out of logs.
- Add audit trails for moderation, profile authenticity verification and admin actions.

---

## 17. Code notes to revisit

Future cleanup:
- Some controller comments mention old or tentative behavior.
- Prefer service implementation and these docs as current source of truth.
- Remove or update comments once behavior stabilizes.

---

## 18. BACK-7 — Immutable legal document publication and historical evidence

Backend historical-content-identity implementation is present:

- canonical versioned legal source layout under `legal-documents/`;
- exact-byte SHA-256 identity for `document.html`;
- configured current document `content-sha256`;
- startup canonical-file/hash verification from bundled classpath resources;
- server-side persistence of content SHA-256 with new legal actions;
- hash-aware legal requirement satisfaction;
- no client-supplied hash;
- no full HTML snapshot in PostgreSQL.

The selected source-of-truth location is
`Gtestino92/reals-backend/legal-documents/`. Do not reintroduce a separate
`reals-legal` repository as the preferred design.

Remaining production publication/legal operational work:

- legally reviewed production document content;
- public static hosting/publication target;
- stable production legal domain;
- pipeline/process that publishes exact canonical file bytes without HTML transformation;
- preservation and availability of historical public URLs.

Do not add URL-shape validation as a substitute for content immutability. A URL
naming convention can help operators organize published documents, but it is not
a technical guarantee that remote content is immutable. The backend verifies the
bundled canonical file bytes against the configured SHA-256; it does not fetch
or hash the remote URL.

---

## 19. BACK-0 / BACK-4 — Data inventory and account-deletion cleanup

Implemented:

- The authoritative backend data inventory and retention matrix is in `docs/data-retention.md`.
- Account deletion atomically deletes ephemeral matchmaking, engagement-lock, push-token, push-delivery, connection Home-dismissal and deleted-user Home-status state.
- Active interaction is contained while historical lifecycle and content rows remain.
- Recovery-window behavior for profile, media metadata/objects, messages, safety, anti-abuse, legal and audit data is documented.
- Firebase Auth external-user deletion/finalization is implemented: the identity is retained during recovery, and finalization deletes or confirms absence of the Firebase user before releasing the local Firebase UID.
- External deletion failures keep local finalization pending and retryable.
- Current post-window finalization is not a complete product-data purge.

Remaining policy and implementation work:

- post-recovery purge or anonymization of profiles and lifecycle rows;
- profile-photo object deletion after finalization;
- chat-message and personal-message retention;
- block, penalty and reliability-event treatment after final deletion without overriding existing expiry behavior;
- safety-report/evidence, audit and legal-action retention periods and anonymization;
- database/object-store backup retention and restore handling;
- application log, metric, cache and rate-limit state retention.

No retention periods are selected by BACK-0/BACK-4. These items require separate product, privacy, legal, security and infrastructure decisions.

## 20. Google Play account-deletion web resource and Data safety disclosure

**DEFERRED / REQUIRED BEFORE GOOGLE PLAY PRODUCTION DISTRIBUTION**

Current state:

- Android has an in-app account-deletion path.
- The backend has a recoverable deletion and finalization lifecycle.
- Reals does not yet have a public web resource where a user can request account deletion without reinstalling or opening the Android app.

Before Google Play production distribution:

- Publish a functional public web resource for account-deletion requests.
- Prominently expose the account-deletion request path.
- Reference Reals or the production developer name used in the Play listing.
- Allow the user to initiate the deletion request without being redirected to the Android app or required to reinstall it.
- Decide the minimum account-ownership/support flow for web deletion requests.
- Disclose the account/data deletion URL in the Play Console Data safety form.
- Align public deletion copy with the 30-day recovery window and the actual retention policy.
- Clearly describe retained safety, fraud-prevention, regulatory, audit or legal-evidence data once those retention policies are finalized.

This section documents a production requirement only. It does not add a public endpoint, unauthenticated deletion API or email-based deletion request flow.


## clean up pre-mvp


### 1.1 First-chat guided questions

MVP decision implemented:
- Guided questions are backend-owned for first chat.
- The catalog is a static Spanish resource, not a database table or admin CRUD.
- The active question is shared by both participants and persisted as an id/text snapshot.
- The per-chat sequence is deterministic from chat id and catalog order. Reordering the static catalog may affect not-yet-selected future questions for active first chats; this is acceptable while first chats are short-lived.
- Chat remains free-form. The backend does not semantically evaluate answers.
- A participant needs 40 accumulated persisted characters during the active question interval before requesting another question. One long message can satisfy the threshold.
- Advancement requires both participants to independently request it. Partner readiness/request state is not exposed.
- The maximum is 3 questions. When both participants request continuation from the penultimate question, the final configured question becomes active and remains incomplete. Guidance completes only after both participants reach the required participation score on that final question and request `COMPLETE`; no fourth question is selected.
- Clients observe changes through existing first-chat polling. No analytics events are implemented yet.

Future work:
- Curate the final Spanish question corpus.
- Decide whether additional locales, analytics, experimentation or admin tooling are useful after MVP usage.


## 2. Backend MVP cleanup

### 2.2.1 Future profile photo ordering endpoint

MVP decision:
- Multipart profile photo upload remains slot-based and requires `position`.
- Do not implement drag-and-drop reordering as part of the MVP validation shortcut.

Future cleanup:
- Add a dedicated reorder endpoint, for example `PUT /api/me/profile/photos/order`, when the product needs real drag-and-drop ordering.
- The endpoint should accept ordered `photoIds`, validate that every photo belongs to the current user's profile, reject duplicate or missing photos, and reassign positions atomically.

Acceptance criteria:
- Upload and replace-file flows remain unchanged for Android.
- Reordering is handled by the dedicated endpoint rather than overloading upload semantics.

### 2.2.2 Post-MVP media pipeline options

MVP decision:
- Keep profile photo uploads backend-mediated.
- Keep original images only; no generated thumbnails or previews.

Post-MVP options:
- Consider direct-to-storage upload using presigned write URLs if backend bandwidth becomes a concern.
- Add generated thumbnails/previews when profile photo load performance needs it.

Acceptance criteria:
- These options remain separate from the MVP R2 setup.
- Endpoint contracts stay stable until Android and backend agree on a new media flow.


## Tech debt prod: cleanup de cuentas no verificadas

### Contexto

Reals permite que un usuario cree cuenta y avance parcialmente en onboarding antes de verificar su email. La verificación de email se exige como precondición para activar el perfil y entrar al flujo operativo real.

Esta decisión mejora el onboarding y evita bloquear la creación de cuenta prematuramente, pero deja una deuda operativa: pueden acumularse cuentas Firebase y usuarios locales que nunca verifican email ni llegan a activar perfil.

### Riesgo

Cuentas no verificadas pueden generar:

* acumulación de usuarios inactivos en Firebase Auth;
* acumulación de usuarios locales en base de datos;
* perfiles incompletos o en borrador sin valor operativo;
* posible consumo innecesario de storage si se permitieron fotos antes de verificar;
* ruido en métricas de adquisición/onboarding;
* superficie de abuso por creación masiva de cuentas no verificadas.

### Regla funcional actual recomendada

Mantener permitido antes de verificar email:

* sign-up/sign-in;
* provisioning backend;
* creación de perfil;
* edición de perfil;
* configuración de filtros;
* eventualmente carga de fotos para no friccionar onboarding MVP.

Bloquear antes de verificar email:

* activación de perfil;
* entrada a matchmaking;
* cualquier operación que haga visible/operativo al usuario dentro del flujo social.

### Cleanup propuesto para producción

Implementar un job periódico que detecte cuentas no verificadas e inactivas y las limpie o marque para limpieza.

Criterios tentativos:

* usuario local creado hace más de 14 o 30 días;
* email Firebase sigue sin verificar;
* perfil inexistente, DRAFT o INACTIVE;
* nunca activó perfil;
* nunca participó en matchmaking/chat/visual review/connection;
* no tiene reportes, auditoría crítica ni datos que deban preservarse.

### Acciones posibles

Opción A — Soft cleanup local:

* marcar usuario local como `DELETED` o estado específico `UNVERIFIED_EXPIRED`;
* anonimizar datos locales no necesarios;
* conservar auditoría mínima.

Opción B — Cleanup completo coordinado:

* borrar o deshabilitar usuario en Firebase Auth;
* marcar/borrar datos locales asociados;
* eliminar fotos/storage no usadas;
* registrar evento de auditoría técnico.

Opción C — Fase intermedia:

* primero marcar como “eligible for cleanup”;
* luego ejecutar eliminación final después de una ventana adicional.

### Consideraciones

* No borrar cuentas que hayan tenido actividad real del flujo social.
* No borrar cuentas vinculadas a reportes, bloqueos, auditoría de seguridad o disputas.
* Coordinar cleanup local con Firebase Auth para evitar estados inconsistentes.
* Evitar que un usuario pueda quedar en limbo: Firebase activo pero backend eliminado, o viceversa, sin mensaje claro.
* Agregar métricas: cuentas creadas, verificadas, expiradas, limpiadas, fallidas.
* Agregar rate limit/cooldown para reenvío de email de verificación antes de producción pública.
* Revisar si fotos deben estar permitidas antes de verificar email o si deben bloquearse en producción para reducir abuso de storage.

### Prioridad

No es bloqueante para MVP cerrado/local.
Debe resolverse antes de una beta pública amplia o producción abierta.
