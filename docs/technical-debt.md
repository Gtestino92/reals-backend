# Technical Debt And Product Decisions

This file lists known pending or intentionally unimplemented behavior. Do not implement these implicitly while working on unrelated tasks.

## Product Decisions

- Guided first-chat questions or conversation starters.
- Whether guided questions belong to frontend or backend.
- Exact visibility rule for visual-review personal messages beyond current `VISUAL_APPROVED` enforcement.
- Whether matchmaking should be processed by a scheduler/worker instead of the dev-only `/api/dev/matchmaking/process` endpoint.
- Profile location should eventually be validated against a canonical country/city cache instead of only accepting free-text `country` and `city`.
- Decide where geolocation enters the product flow. The likely point is before first-chat matchmaking/search, using profile location plus future latitude/longitude, geohash or search radius fields.
- Decide final visibility and UX timing for visual-review personal messages. Current behavior allows reading the partner message during visual review and requires reading it before approving if the partner already submitted one.

## Not Currently Implemented

- Real-time chat via WebSocket or SSE.
- Notification delivery.
- Reveal quotas.
- Advanced compatibility scoring.
- ML-based matching.
- Popularity, attractiveness or ELO ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full moderation workflow for safety reports. Current implementation records safety cancellation and applies a penalty, but no manual review workflow exists yet.
- Firebase/JWT backend wiring exists for `dev` and `prod`, but it still needs a real Firebase project, service account configuration and manual token validation before production use.
- Own media storage for profile photos with S3. `ProfilePhoto` already has storage provider/bucket/key fields, but upload endpoints, presigned URL generation, object lifecycle, quarantine path and moderation promotion are not implemented yet.
- Restrict client overrides of photo validation flags (`isPersonPhoto`, `isFullBody`) to local/dev or trusted admin tooling once automatic validation exists.
- Identity verification is only represented by `Profile.identityVerified` for now; no dedicated identity-verification provider is called yet.

## Infrastructure Gaps

- `pom.xml` includes Oracle and PostgreSQL drivers, but only PostgreSQL is represented in `application-dev.yml` and `application-prod.yml`.
- Local profile uses H2 file storage and disables Flyway.
- Helm values under `deploy/helm` are placeholders; the final chart location and deploy repository convention are not decided yet.

## Observability And Error Handling

- Add request correlation IDs. Propagate incoming `X-Request-Id` or generate one, return it in responses and include it in log MDC.
- Add Spring Boot Actuator and metrics export before production. Track HTTP latency/statuses, auth failures by reason, scheduled job runs, processed/skipped/failed item counts and key state transitions.
- Harden scheduled jobs so one failing record does not abort an entire run. Each job should log a final summary with processed/succeeded/failed/skipped counts.
- Include exception stacktraces in job failure logs. Avoid logging only `ex.message` for unexpected scheduler failures.
- Consider explicit domain exception types with stable error codes for frontend handling, instead of relying only on `IllegalArgumentException` and `IllegalStateException`.
- Add production log policy for sensitive fields. Do not log tokens, chat contents, personal messages, full emails, private media URLs or raw request bodies.

## Multi-Instance Deployment Risks

- Scheduler jobs must remain safe when more than one app instance exists. ShedLock prevents most duplicate scheduled executions, but each job should still be idempotent: re-running it should not create duplicate chats, penalties, locks or state transitions.
- State transitions that create dependent records need stronger concurrency protection before multi-instance production. Examples: mutual visual approval creating a `Connection`, scheduling confirmation creating/activating second-chat availability, unilateral cancellation creating penalties, and lock creation/release. Use database constraints, transactions and optimistic or pessimistic locking where needed.
- Active engagement limits can race under concurrent requests. Counting current `ActiveEngagementLock` rows and then inserting new rows is not enough by itself if two app instances do it at the same time. Revisit with transactional locking or database-level constraints before real scale.
- Matchmaking processing must claim queued users atomically if it becomes a real scheduled worker. The risk is two workers selecting the same queued user pair at the same time. PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` or equivalent queue-claiming logic is the usual solution.
- Profile photos are safe as URLs only if the actual files live in shared object storage such as S3, GCS, Firebase Storage or another external media service. They should not be stored on one backend instance's local disk, because another instance would not have the file.
- Future real-time chat via WebSocket or SSE needs multi-instance routing. Options include sticky sessions, a shared pub/sub layer such as Redis, or managed realtime infrastructure. Plain in-memory connection state will not work across instances.
- Add production observability before multi-instance rollout: structured logs with request/job identifiers, metrics for scheduled jobs and state transitions, and alerts for stuck negotiations, failed jobs or repeated retries.

## Code Notes To Revisit

- Some controller comments mention old or tentative behavior; prefer service implementation and these docs as the current source of truth.
- `TECH_DEBT.md` recovered from the previous setup was empty.
