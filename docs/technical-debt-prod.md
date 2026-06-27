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

Decision pending:
- Whether backend should reject provisioning or profile activation when Firebase token has `email_verified=false`.

Future work:
- Add stable error such as `EMAIL_NOT_VERIFIED`.
- Define Android resend/refresh flow.
- Decide whether this is required only for production or also dev.

---

## 2. Identity verification

Current state:
- `Profile.identityVerified` exists.
- Identity verification endpoint exists.
- Provider abstraction exists.
- Current `none` provider always returns `verified=false`.

Production decision:
- Identity verification is separate from profile photos.
- Do not infer identity from person detection, full-body detection or visual approval.

Future implementation:
- Choose identity verification provider or internal verification flow.
- Define required inputs:
  - selfie;
  - liveness check;
  - document verification;
  - profile photo comparison;
  - or hybrid process.
- Store provider reference/audit metadata.
- Define retry/failure states.
- Define frontend UX.
- Decide whether identity verification is:
  - optional;
  - required for activation;
  - required only after trust/safety escalation;
  - required for certain user actions.

---

## 3. Profile photo validation and moderation

### 3.1 Future upload lifecycle

MVP shortcut:
- Technical upload success may create `VALIDATED` photos with permissive semantic defaults.

Production target:
1. User uploads a photo.
2. Backend performs technical checks.
3. If upload succeeds, photo starts as `PENDING`.
4. Analysis/moderation updates:
   - `validation_status = VALIDATED` or `FAILED`;
   - `isPersonPhoto`;
   - `isFullBody`;
   - optional provider/model/version metadata.

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
- Add report profile/photo flow.
- Add admin ability to hide/reject/remove photos.
- Add moderation states or reuse `FAILED` carefully.
- Add optional automatic image moderation for:
  - nudity;
  - explicit sexual content;
  - violence;
  - hate symbols;
  - minors/underage risk;
  - other prohibited content.
- Define whether rejected photos are deleted, hidden, quarantined or retained for audit.

### 3.4 Media storage production work

Future work:
- Object lifecycle rules.
- Orphan cleanup after replace/delete.
- Malware/content scanning if required.
- CDN/cache strategy.
- Thumbnail generation.
- Dimension normalization.
- Avoid proxying image bytes through backend unless intentional.

---

## 4. Safety and moderation

### 4.1 Full safety report workflow

Current state:
- Safety cancellation/report can record a report and apply a penalty.
- Full manual review workflow is not complete.

Production work:
- Admin queue for reports.
- Report status transitions.
- Moderator notes.
- Evidence/context view.
- Escalation policy.
- Appeal or correction policy if needed.
- Photo/profile reports, not only chat safety reports.
- Audit trail.

### 4.2 User blocking and objectionable content controls

Future work:
- Clear user-facing block/report actions.
- Ability to avoid future rematches after report/block.
- Explicit policy for objectionable profile photos and messages.
- Internal tooling to remove content and sanction users.

### 4.3 Sensitive message data protection

Current MVP stance:
- Chat and personal message contents are stored as application text.

Before production:
- Define retention/deletion rules.
- Keep request/response bodies out of logs.
- Restrict and audit internal access.
- Evaluate application-level field encryption with keys stored outside the database.
- Define data export/deletion behavior for account deletion if required.

---

## 5. Location and geographic matching

### 5.1 Canonical country/city reference data

Future work:
- Validate profile `country` and `city` against a canonical dataset.
- Prefer reference endpoints such as:
  - `GET /api/reference/countries`;
  - optional city/region endpoints.
- Use ISO-3166 alpha-2 country codes for country identity.
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

## 7. Matchmaking scalability

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

---

## 8. Home and polling pressure

Risk:
- Home is likely to become one of the most frequently requested endpoints.
- It aggregates match, chat, visual review, connection, scheduling, second-chat and blocked-state information.

Before scale:
- Measure Home endpoint latency.
- Review generated queries.
- Avoid expensive repeated joins.
- Consider a dedicated read model if Home becomes costly.
- Reduce polling once push notifications are available.
- Consider short-lived per-user caching only after correctness is stable.

---

## 9. Chat scaling and realtime transport

### 9.1 Chat message scaling

Risk:
- Chat polling and message history can create high read volume.
- Fetching full message history does not scale well for long chats or frequent polling.

Future work:
- Add cursor-based pagination for chat messages.
- Prefer message IDs or `(sentAt, id)` cursors.
- Limit response sizes.
- Add indexes by `chat_session_id`, `sent_at`, and possibly `id`.
- Add metrics:
  - messages sent per minute;
  - messages fetched per minute;
  - active chats;
  - average message fetch latency.

### 9.2 Push notifications

Before larger production usage:
- Add Firebase Cloud Messaging support.
- Register device tokens.
- Handle token refresh.
- Add notification tap routing.
- Keep Home refresh as source of truth after notification taps.
- Keep polling as fallback.
- Reduce unnecessary polling once push is reliable.

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

Risk:
- Scheduled jobs can become bottlenecks when many records expire or transition at similar times.

Before scale:
- Process records in bounded batches.
- Ensure one bad record does not abort the entire batch.
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

Before scale, add explicit concurrency tests for:

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

Before production scale:
- Add structured logs with request/job identifiers.
- Add metrics export.
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

Before scale:
- Confirm Firebase token verification does not perform unnecessary remote calls per request.
- Monitor auth filter latency.
- Monitor 401/403 rates by reason.
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
- Add audit trails for moderation, identity verification and admin actions.

---

## 17. Code notes to revisit

Future cleanup:
- Some controller comments mention old or tentative behavior.
- Prefer service implementation and these docs as current source of truth.
- Remove or update comments once behavior stabilizes.


## clean up pre-mvp


### 1.1 First-chat guided questions

Decision pending:
- Whether first-chat guided questions/conversation starters are required for MVP.
- Whether the question set belongs fully to the frontend, fully to the backend, or backend-provided with frontend rendering.

MVP recommendation:
- Keep the first implementation simple.
- Prefer backend-owned predefined question IDs/texts if questions affect product analytics or future experimentation.
- Prefer frontend-owned static copy only if the set is temporary and not important for backend decisions.

Acceptance criteria:
- First chat can start with a predictable prompt or question.
- Users can continue chatting without being blocked by the question mechanic.
- The decision is documented so future chats do not fork behavior across app versions.


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
