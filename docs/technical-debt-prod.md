# Production readiness / technical debt

This file tracks remaining production work and known decisions. It should not be
read as an architecture document or changelog.

## How to read this document

- Current behavior is documented in `docs/architecture.md`,
  `docs/configuration.md`, `docs/data-retention.md`, `docs/security-mvp.md`,
  `docs/user-reliability-score.md`, `docs/matchmaking-ranking.md`,
  `docs/operational-state-model.md`, deployment docs and `docs/commons/*`.
- If this document conflicts with code, migrations, configuration or canonical
  current-state docs, treat this document as stale and update it.
- P0 means required before first production deployment or public exposure. P1
  means required before meaningful public beta or traffic. P2 means scale or
  operational hardening triggered by evidence. P3 means future product or
  architecture evolution that should not delay initial production.
- This document intentionally omits work that is already implemented unless a
  short implemented-foundation note prevents confusion about remaining debt.

## P0 - required before first production/public exposure

### Production environment and release boundary

- Create a production GitHub Environment with manual approval, production-only
  secrets and production-only deployment variables.
- Create a separate production AWS OIDC role and trust policy; do not reuse the
  dev SSM target or dev deployment role.
- Define the production runtime shape separately from AWS dev: hostname/TLS,
  Nginx or load-balancer boundary, JVM/container settings, private PostgreSQL,
  S3 bucket, Firebase project, App Check mode, allowed app ids and readiness
  checks.
- Promote immutable release SHAs or version tags only; dev can continue using
  the existing `development` and `sha-*` image workflow.
- Define backup/restore and rollback procedures, including how failed Flyway
  migrations are handled for a production database.
- Keep automatic dev rollback separate from production promotion. Production
  rollback must be an explicit operator procedure against a known release.

### Credential and template hygiene

- Keep committed Bruno environment templates placeholder-only. Real Firebase
  API keys, admin emails, passwords, ID tokens, refresh tokens and environment
  object ids must live only in ignored local files or secret stores.
- If any previously committed development credentials or tokens were real,
  rotate/revoke them outside this repository task before public exposure. Do
  not treat template sanitation as credential revocation.
- Re-run a repository secret scan before release and verify that deployment
  logs, Actuator responses and Bruno examples do not expose tokens, Firebase
  service accounts, private media URLs or passwords.

### Production photo analysis and activation

- Configure and smoke-test the production photo-analysis provider before users
  can activate profiles. Startup now rejects `prod` unless the configured
  provider is `sightengine`; operators still need an environment-level smoke
  test with real media before opening traffic.
- Verify Sightengine credentials, account plan and model access for
  `face-analysis`, `nudity-2.1`, `violence`, `gore-2.0` and
  `offensive-2.0` in the actual production environment.
- Keep the current provider boundaries explicit: Sightengine moderation and real
  face presence are not legal identity verification, facial recognition, face
  matching, liveness, age assurance, minor detection or full-body detection.
- Decide the production fail-closed policy for provider errors
  (`PROFILE_PHOTO_MODERATION_FAIL_UPLOAD_ON_PROVIDER_ERROR`) and document the
  operational response for `NEEDS_REVIEW` backlogs.
- Maintain the temporary `PROFILE_MIN_FULL_BODY_PHOTOS=0` production stance
  until a real full-body/person-consistency detector exists.

### Account deletion and data retention

- Complete the post-recovery purge/anonymization policy for profiles, media
  metadata, media objects, chats, personal messages, blocks, penalties,
  reliability events, safety reports, evidence snapshots, audit events, legal
  actions, logs, metrics and backups.
- Decide exactly which records remain for safety, fraud-prevention, regulatory,
  audit or legal-evidence reasons after the recovery window ends.
- Define backup-retention and restore procedures so finalized deletions are not
  silently resurrected from database or object-store backups.
- For Google Play production distribution, publish a public account-deletion web
  resource and align Data safety disclosure copy with the actual 30-day recovery
  window and final retention policy.

### Backoffice and safety operations boundary

- Define production access for `/api/admin/**`: internal-only network, VPN/Zero
  Trust, MFA, Firebase custom claims or persisted admin roles. The current
  verified-email allowlist is acceptable for dev/MVP but is not a complete
  production access model by itself.
- Define operator runbooks for report triage, evidence handling, penalty
  confirmation, abusive-report dismissal, correction, appeal and audit review.
- Define child-safety legal/operational workflows before public users: specialist
  review ownership, escalation criteria, external-authority reporting, CSAM/CSAE
  handling, preservation rules and user communications.
- If a browser-based admin UI is introduced, configure restrictive CORS for the
  official admin origin only. CORS is not a substitute for authentication,
  authorization, MFA or network controls.

### Rate-limit and edge configuration

- Configure the production reverse proxy or load balancer so the servlet
  container receives the real client IP only from trusted infrastructure. The
  pre-auth limiter keys on `request.remoteAddr` and does not trust arbitrary
  forwarded-IP headers.
- Review public capacities for provisioning, password reset, message sends,
  profile-photo uploads/replacements and safety reports before opening traffic.
- Keep the current Caffeine limiter limitation explicit: it is per-instance. A
  gateway or distributed limiter is required if multi-instance traffic makes
  per-instance buckets insufficient.

### Legal document publication

- Replace placeholder legal content with legally reviewed production documents.
- Publish exact canonical document bytes at stable public URLs and preserve
  historical availability.
- Keep backend SHA-256 verification as the content-identity guarantee. URL
  naming conventions help operators but do not prove remote immutability.

## P1 - required before meaningful public beta/traffic

### Observability and alerting

- Select and configure the production metrics/logging backend. The application
  currently exposes request IDs, secured Actuator metrics and custom Micrometer
  meters, but it does not configure Prometheus, Grafana, OTLP export,
  distributed tracing or alert routing.
- Add alerts for job failures, matchmaking backlog, stale active locks, failed
  media cleanup, failed notification delivery, DB connection saturation,
  repeated deadlocks, elevated auth/App Check failures, elevated rate limiting
  and high API latency.
- Add operational dashboards for the core funnel: time to first match,
  first-chat completion, visual approval, scheduling confirmation, second-chat
  attendance, safety reports, penalties, account deletion/reactivation and
  photo moderation backlog.

### Notification delivery operations

- Smoke-test FCM delivery in the production-like remote environment with the
  pinned Firebase Admin SDK and the production Android sender configuration.
- Verify that Firebase `UNREGISTERED` and relevant invalid-registration errors
  are mapped to `invalidTokens` and disabled in PostgreSQL. The implementation
  exists, but the pinned SDK/environment behavior still needs production-like
  validation.
- Add delivery observability for `SENT`, `FAILED`, `SKIPPED_NO_ACTIVE_TOKEN`,
  `SKIPPED_USER_PREFERENCE`, invalid-token cleanup and provider exceptions.
- Define retry/backoff and operator response for transient provider failures.
  Current provider calls happen after preparation and before result
  persistence, so exact outbox semantics remain unresolved.
- Decide how stale prepared pushes should be handled when a user completes the
  action after preparation but before transport.

### Safety and moderation operations

- Provide production-grade backoffice UX/tooling for the implemented backend
  safety report and profile-photo review endpoints.
- Add pagination or cursor access to admin lists when report/photo-review volume
  outgrows the current bounded list shape.
- Define policy for admin-created reports optionally creating blocks or other
  containment actions. User-created reports already create directional blocks;
  admin-created reports currently do not.
- Define rejected-media retention, quarantine, deletion and reopen/override
  policies for profile-photo moderation.

### Calibration, fairness and abuse review

- Calibrate active Match capacity, active Connection capacity and rolling Visual
  Advancement capacity with production-like data. These mechanisms are
  implemented admission controls for new opportunities only; falling effective
  caps never retroactively cancel existing matches, visual reviews, connections
  or chats.
- Calibrate user reliability event weights, decay windows and capacity curves.
  Reliability events are the source of truth; there is no materialized score or
  cache currently.
- Review the feedback loop between reliability and opportunity access so low
  scores do not create unfair permanent starvation.
- Observe probabilistic ranking and affinity shadow metrics before enabling
  experimental ranking modes in production. Global, dev and prod defaults remain
  `LEGACY_EARLY_ACCEPT`; `local-firebase` defaults to
  `PROBABILISTIC_WEIGHTED` with affinity `SHADOW`.
- Define fairness/market-liquidity guardrails for waiting-time relaxation,
  reliability similarity and affinity influence before turning affinity
  ranking `ACTIVE`.

### Unverified and inactive account cleanup

- Decide whether unverified Firebase/backend accounts that never enter the
  social flow should expire or be disabled after a defined window.
- Coordinate local cleanup with Firebase Auth so users do not end up with a
  Firebase identity that cannot be explained by backend state.
- Add metrics for created, verified, expired, cleaned and failed-cleanup
  accounts. Photo upload and replacement now require verified email; profile
  creation/editing and match-filter edits remain allowed before verification.

### Production smoke and manual validation

- Build a production smoke plan that covers readiness, authenticated requests,
  Firebase App Check, profile photo upload/replacement, profile activation,
  matchmaking, Home polling, chat reads, notifications, safety reporting and
  account deletion request/reactivation/finalization paths.
- Keep smoke checks public only for readiness/ping. `/actuator/info` and
  `/actuator/metrics/**` must remain admin-only.

## P2 - scale/operational hardening triggered by evidence

### Data access and read models

- Add read models or projections only when measured Home aggregation, admin
  queues or lifecycle diagnostics justify them. Current Home supports full
  reads, `/api/me/home/pending`, `/api/me/home/status`, persisted versioning and
  shared snapshot loading.
- Add additional cursor pagination where payloads can grow beyond current
  bounded contracts. Chat messages already use cursor pagination by
  `sentAt ASC, id ASC` with `limit + 1` and `hasMore`.
- Add database diagnostics for stale locks, expired lifecycle rows, cleanup
  failures and queue backlog if operational incidents or dashboard needs justify
  them.

### Notification exactness

- Introduce an outbox/claiming model if duplicate or lost notification attempts
  become material. Current deduplication uses the
  `(userId, notificationType, aggregateId)` unique key and short persistence
  transactions, but the provider-send-before-result-persistence window remains.
- Add retries/backoff with bounded attempts and dead-letter/operator inspection
  if transient provider failures are observed.

### Multi-instance and queue scale

- Move rate limiting to a gateway or distributed store if per-instance Caffeine
  buckets are insufficient.
- Add parallel matchmaking workers only after queue volume, claim contention and
  job duration data justify it. Current jobs process bounded deterministic
  batches and revalidate under locks.
- Add Redis, SSE/WebSocket, push-driven refresh, or Home projections only when
  polling pressure and latency measurements require them.

### Reliability score cache

- Add a TTL or materialized reliability-score cache only if event-sourced score
  reads become expensive. The cache must be reconstructable from
  `user_reliability_events` and invalidated on event creation and cleanup.
- Do not turn reliability into a public reputation system, safety sanction or
  deterministic eligibility tier without a separate product/security decision.

### Storage and media delivery

- Consider direct-to-S3 upload, generated thumbnails/previews or CDN behavior
  only if backend bandwidth, image latency or object-store load justifies the
  added contract and operational complexity.
- Keep old-object cleanup durable and retryable through `media_cleanup_tasks`;
  add dashboards and manual repair tooling if failed tasks occur.

## P3 - product/architecture evolution

### Matching product evolution

- Replace the current binary `BasicCompatibilityScorer` with gradual
  compatibility only after there is a clear optimization target and evaluation
  data.
- Keep ML-based matching, popularity scoring, attractiveness scoring, ELO-like
  ranking and gamified reputation badges out of initial production unless the
  product direction explicitly changes.
- Consider canonical city/locality reference data, geospatial indexing or
  PostGIS if markets need structured city selection or distance queries outgrow
  current SQL/filtering.

### Profile authenticity evolution

- A real profile-authenticity provider remains future work unless the product
  decides authenticity verification is required. The current provider `none`
  fails in `prod` instead of granting `VERIFIED`.
- Future authenticity work needs a live-reference capture contract, freshness
  and liveness rules, provider/model pinning, thresholds, provider access to
  current photo bytes, privacy/biometric review, provider metadata, retry
  behavior and a `NEEDS_REVIEW` operations path.
- Keep authenticity separate from moderation, legal identity verification, KYC,
  age assurance and full-body detection.

### Future moderation capabilities

- Add automated child-safety detection, CSAM/CSAE tooling, age estimation,
  richer content policy, system-created safety reports or automatic penalties
  only after legal, privacy, safety and operator workflows are defined.
- Add unblock/correction flows for manual blocks if product support requires
  reversing durable pair exclusions.

### Infrastructure evolution

- Introduce Terraform/CDK, Kubernetes, Helm, autoscaling, sharding or service
  decomposition only when production operations require repeatable
  provisioning, multiple services or scaling beyond the current monolith.
- Revisit CSRF only if the backend introduces browser-managed sessions, cookies
  or form login. The current API remains stateless bearer-token based.

## Implemented foundations / assumptions

- Chat ownership is split across `ChatService`, `ChatAccessService`,
  `ChatMessageService`, `FirstChatResolutionService`, `ChatLifecycleService`,
  `ChatExitService`, `SecondChatLifecycleService` and
  `SecondChatConversationLifecycleService`. Do not describe message writes,
  first-chat decisions or lifecycle jobs as owned by an old monolithic
  `ChatService`.
- Profile ownership is split between `ProfileService` for profile-level state
  and `service.photo.ProfilePhotoService` for photo reads, upload, replacement,
  deletion, reorder, activation photo validation, storage coordination, durable
  cleanup coordination and photo-mutation consequences.
- `PUT /api/me/profile/photos/reorder` is implemented. Backend profile-photo
  reordering is not remaining production debt.
- Firebase password and Google sign-in providers are represented through
  Firebase ID tokens and backend-owned `authOrigin`. Future debt is provider
  linking/operational policy, not basic Google/Firebase provider support.
- Profile activation requires verified Firebase email. Profile photo upload and
  replacement also require verified email before the backend reads or processes
  the file. Profile creation/editing, photo delete, photo reorder and
  match-filter edits do not require verified email.
- The shared push preparation/persistence/sender workflow exists through
  `PushRecipientPreparationService`, `PreparedPushCommand`,
  `PreparedPushCommandProcessor`,
  `PushNotificationDeliveryPersistenceService`,
  `NotificationProviderDispatcher` and `service.notification.sender`.
- Account deletion request containment, 30-day recovery and Firebase/local
  finalization are implemented. The remaining debt is final purge,
  anonymization and retention policy after recovery.
- AWS dev deployment is implemented through GitHub Actions, GHCR immutable SHA
  images, GitHub OIDC, SSM Run Command, private RDS/S3, Nginx, readiness checks
  and host-side automatic application-image rollback. Production deployment is a
  separate P0 design and approval boundary.
