# Technical Debt And Product Decisions

This file lists known pending or intentionally unimplemented behavior. Do not implement these implicitly while working on unrelated tasks.

## Product Decisions

- Guided first-chat questions or conversation starters.
- Whether guided questions belong to frontend or backend.
- Exact visibility rule for visual-review personal messages beyond current `VISUAL_APPROVED` enforcement.
- Revisit a dedicated matchmaking worker process or external queue only if matchmaking volume, latency requirements or CPU cost make the scheduled DB-queue `MatchmakingJob` too expensive for app instances.
- Profile location should eventually be validated against a canonical country/city cache instead of only accepting free-text `country` and `city`.
- Decide where geolocation enters the product flow. The likely point is before first-chat matchmaking/search, using profile location plus future latitude/longitude, geohash or search radius fields.
- Decide final visibility and UX timing for visual-review personal messages. Current behavior allows reading the partner message during visual review and requires reading it before approving if the partner already submitted one.

## Not Currently Implemented

- Real-time chat via WebSocket or SSE.
- Notification delivery.
- Reveal quotas.
- Advanced compatibility scoring. Current matching uses a SQL basic-compatible pair filter plus a rule-based `CompatibilityScorer`; future work should add geographic proximity, age-range preferences and interest/affinity overlap without introducing popularity, attractiveness or ELO-style ranking.
- ML-based matching.
- Popularity, attractiveness or ELO ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full moderation workflow for safety reports. Current implementation records safety cancellation and applies a penalty, but no manual review workflow exists yet.
- Firebase/JWT backend wiring exists for `dev` and `prod`, but it still needs production service account configuration and operational validation before production use.
- Own media storage for profile photos with S3. `ProfilePhoto` already has storage provider/bucket/key fields, but upload endpoints, presigned URL generation, object lifecycle, quarantine path and moderation promotion are not implemented yet.
- Restrict client overrides of photo validation flags (`isPersonPhoto`, `isFullBody`) to local/dev or trusted admin tooling once automatic validation exists.
- Identity verification is only represented by `Profile.identityVerified` for now; no dedicated identity-verification provider is called yet.

## Infrastructure Gaps

- `pom.xml` includes Oracle and PostgreSQL drivers, but only PostgreSQL is represented in `application-dev.yml` and `application-prod.yml`.
- Local profile uses H2 file storage and disables Flyway.
- Upgrade Spring Boot to the latest stable major line, currently `4.x`, once PostgreSQL/Flyway and CI are stable. First keep the current `3.5.x` line on its latest patch release, then do the `4.x` migration in a dedicated branch with full regression testing.
- Remove the temporary `tomcat.version` override once a Spring Boot 3.5.x patch manages Tomcat 10.1.55 or newer.
- Add production release image tagging when a production environment exists. Dev should keep using moving `development` and immutable `sha-*` tags; production should publish immutable `v*` tags from Git tags, such as `v1.0.0`.
- Helm values under `deploy/helm` are placeholders; the final chart location and deploy repository convention are not decided yet.
- Decide the first external development deploy target. Candidates to compare: Render, Fly.io, Railway, Google Cloud Run and a managed PostgreSQL provider such as Neon or Supabase.

## Observability And Error Handling

- Add metrics export before production. Actuator health/info is available, but metrics are intentionally disabled for now. Later track HTTP latency/statuses, auth failures by reason, scheduled job runs, processed/skipped/failed item counts and key state transitions.
- Harden scheduled jobs so one failing record does not abort an entire run. Each job should log a final summary with processed/succeeded/failed/skipped counts.
- Include exception stacktraces in job failure logs. Avoid logging only `ex.message` for unexpected scheduler failures.
- Consider explicit domain exception types with stable error codes for frontend handling, instead of relying only on `IllegalArgumentException` and `IllegalStateException`.
- Add production log policy for sensitive fields. Do not log tokens, chat contents, personal messages, full emails, private media URLs or raw request bodies.

## Multi-Instance Deployment Risks

- Scheduler jobs must remain safe when more than one app instance exists. ShedLock prevents most duplicate scheduled executions, but each job should still be idempotent: re-running it should not create duplicate chats, penalties, locks or state transitions.
- State transitions that create dependent records need stronger concurrency protection before multi-instance production. Examples: mutual visual approval creating a `Connection`, scheduling confirmation creating/activating second-chat availability, unilateral cancellation creating penalties, and lock creation/release. Use database constraints, transactions and optimistic or pessimistic locking where needed.
- Active engagement limits can race under concurrent requests. Counting current `ActiveEngagementLock` rows and then inserting new rows is not enough by itself if two app instances do it at the same time. Revisit with transactional locking or database-level constraints before real scale.
- Matchmaking processing now uses a scheduled DB-queue worker with PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`. Before multi-instance production, validate this behavior against the real PostgreSQL isolation level under concurrent workers and keep ShedLock enabled.
- Profile photos are safe as URLs only if the actual files live in shared object storage such as S3, GCS, Firebase Storage or another external media service. They should not be stored on one backend instance's local disk, because another instance would not have the file.
- Future real-time chat via WebSocket or SSE needs multi-instance routing. Options include sticky sessions, a shared pub/sub layer such as Redis, or managed realtime infrastructure. Plain in-memory connection state will not work across instances.
- Add production observability before multi-instance rollout: structured logs with request/job identifiers, metrics for scheduled jobs and state transitions, and alerts for stuck negotiations, failed jobs or repeated retries.

## Concurrency Hardening Tasks

- Add PostgreSQL-backed concurrency tests for `MatchmakingProcessorService` with two simultaneous processors. Assert that the same queued user is never matched twice and that queue rows are removed exactly once. H2 is not enough for validating `FOR UPDATE SKIP LOCKED`.
- Add explicit tests for concurrent mutual visual approval. Assert that only one `Connection` is created for a match, locks are upgraded once and repeated/competing approvals do not duplicate state.
- Add explicit tests for concurrent scheduling confirmation and scheduled second-chat availability. Assert that only one second chat exists per connection and repeated job runs are idempotent.
- Add explicit tests for concurrent chat cancellation/safety reports. Assert that penalties and `ChatExitRequest` rows are not duplicated for the same terminal chat transition.
- Review and document lock acquisition order for operations that lock both users. Keep the current canonical order by user id and apply the same rule to future user-pair operations to reduce deadlock risk.
- Convert expected concurrency failures such as optimistic-lock conflicts, duplicate-key conflicts and deadlocks into stable API responses or bounded job retries where the operation is idempotent.
- Add database constraints where missing for one-per-domain invariants. Current examples already covered include one connection per match, one chat per match/type, one chat per connection/type and one queue row per user; revisit `ChatExitRequest`, penalties and schedule proposals before production traffic.
- Add job-level summaries for concurrency-sensitive jobs: processed, succeeded, skipped, failed, duplicate/idempotent no-op and duration. Keep metrics export as a later step if Prometheus or another metrics backend is introduced.

## Long-Term Scalability

- Parallel matchmaking workers are intentionally deferred. The current `MatchmakingJob` should remain ShedLock-protected so only one instance processes the queue. If matchmaking queue backlog or latency becomes a real bottleneck, revisit a worker model where multiple instances process the queue concurrently using PostgreSQL `FOR UPDATE SKIP LOCKED`, bounded per-worker limits, short transactions, database constraints and dedicated concurrency tests.

## Code Notes To Revisit

- Some controller comments mention old or tentative behavior; prefer service implementation and these docs as the current source of truth.
- `TECH_DEBT.md` recovered from the previous setup was empty.
