# Technical Debt And Product Decisions

This file lists known pending or intentionally unimplemented behavior. Do not implement these implicitly while working on unrelated tasks.

## Product Decisions

- Guided first-chat questions or conversation starters.
- Whether guided questions belong to frontend or backend.
- Exact visibility rule for visual-review personal messages beyond current `VISUAL_APPROVED` enforcement.
- Whether matchmaking should be processed by a scheduler/worker instead of the dev-only `/api/dev/matchmaking/process` endpoint.

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
- Full Firebase/JWT production authentication flow.

## Infrastructure Gaps

- `pom.xml` includes Oracle and PostgreSQL drivers, but this repository currently does not include `application-dev.yml` or `application-prod.yml`.
- Local profile uses H2 file storage and disables Flyway.
- Maven CLI may be unavailable on the target machine; IntelliJ IDEA is the reliable local execution path.

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
