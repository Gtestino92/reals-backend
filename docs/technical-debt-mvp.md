# TECH_DEBT_MVP

This file lists technical debt, cleanup tasks and product decisions that should be resolved for a first usable MVP/beta version of Reals.

MVP scope here means: enough to run the core product flow end-to-end with controlled users, a dev/staging backend, Android APK distribution, and known temporary shortcuts clearly documented.

Do not implement these implicitly while working on unrelated tasks.


## 5. MVP infrastructure/dev environment

### 5.1 First external dev deploy target

MVP need:
- A backend environment reachable from a physical Android device and installable APK.

Decision pending:
- Choose first external development deploy target.

Candidates:
- Render.
- Fly.io.
- Railway.
- Google Cloud Run.
- AWS App Runner.
- ECS Fargate.
- Managed PostgreSQL provider such as Neon, Supabase, Render PostgreSQL, Railway PostgreSQL or AWS RDS.

MVP recommendation:
- Prefer simple container platform + managed PostgreSQL before Kubernetes.

### 5.2 Dev deployment model

Before distributing APKs beyond local machine:
- Define runtime platform.
- Define managed PostgreSQL instance.
- Define Firebase service-account secret.
- Define environment variables.
- Define health check path.
- Define rollback strategy.
- Define which GHCR tag dev tracks.

### 5.3 Smoke check workflow

MVP task:
- Wire the manual `Smoke check` GitHub Actions workflow into the eventual dev deploy pipeline once the dev runtime platform exists.

Acceptance criteria:
- Smoke check runs against the deployed backend.
- `/actuator/health` and `/actuator/info` are aligned with deployed image metadata.

---

## 6. Explicitly deferred from MVP

The following are intentionally not MVP blockers:

- Real-time chat via WebSocket or SSE.
- Additional push notification event coverage beyond currently implemented MVP events.
- Google Sign-In / social auth providers.
- Reveal quotas.
- Advanced compatibility scoring.
- ML-based matching.
- Popularity, attractiveness or ELO-style ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full manual moderation workflow.
- Profile authenticity verification provider integration.
- Canonical city/locality reference dataset.
- Geohash/spatial indexing.
- CDN/cache strategy for media.
- Application-level message encryption.
- Parallel matchmaking workers.
- Kubernetes/Helm/Terraform unless the chosen platform requires them.

Backend concurrency hardening status:
- Message sends are serialized per `Chat` row with a pessimistic database lock.
- Firebase provisioning retries one concurrent UID/email unique-conflict attempt in a fresh transaction.
- Durable profile-photo DB/object-storage consistency is implemented with `media_cleanup_tasks`.
- PostgreSQL remains authoritative for profile-photo references; new objects are protected by delayed cleanup guards until DB finalization commits.
- Old profile-photo objects are deleted only after the referencing DB transaction commits; deletion is idempotent, durable and retryable.
- Completed cleanup tasks are removed. `FAILED` cleanup tasks require operational inspection.
- PostgreSQL/Testcontainers concurrency coverage remains deferred to a separate production-hardening block.
- The first-chat `ChatDecision` race is intentionally unchanged because Android only supports manual retry after request completion and `actionLoading` reset.
- Exact notification delivery claiming/outbox/retries, observability, Home cleanup, rate limiting, presigned direct-to-S3 uploads, CDN behavior and PostgreSQL/Testcontainers media-concurrency coverage remain out of scope.

Implemented scheduler hardening:

- frequent lifecycle jobs now load bounded deterministic batches and leave backlog for later scheduled runs;
- batch sizes are configurable per frequent job, with a default of `100`;
- one candidate failure is isolated and does not abort the rest of the batch;
- backlog is logged as a cheap `batchSize + 1` signal, not exposed as a public API;
- matchmaking now checks the queue every `15000` ms by default while preserving `maxPairsPerRun`, candidate-pair limits and scalable queue claiming;
- no scheduler drains an entire backlog in one run, and no parallel workers, queues or generic job framework were introduced.

### 6.1 Push notification delivery workflow cleanup

Implemented notification transaction-boundary cleanup:

- provider calls now run outside active database transactions;
- event preparation and delivery-result persistence use short database transactions;
- aggregate locks, including the `VisualReview` reminder lock, are released before FCM transport;
- product-transaction notification call sites were inspected; current provider-call paths are scheduler/dev-job driven after product transitions have committed.

Current notification event services intentionally keep their event-specific behavior explicit, but they still repeat related workflow shape:

- check existing `PushNotificationDelivery` by user, notification type and aggregate id;
- load active device tokens;
- send through `PushNotificationSender` after commit;
- disable invalid tokens after provider response;
- save `SENT`, `FAILED` or `SKIPPED_NO_ACTIVE_TOKEN`;
- catch per-user failures without failing the owning product transition.

Remaining deferred cleanup:

- Extract a shared `PushNotificationDeliveryService` under `service.notification`.
- Keep event services responsible for eligibility, recipients and payload shape.
- Keep provider transport under `service.notification.sender`.
- Preserve existing idempotency semantics and delivery statuses.
- Avoid changing notification payload contents during the extraction.
- Delivery deduplication remains best effort through the existing `(userId, notificationType, aggregateId)` unique key. A duplicate push remains theoretically possible if two workers prepare before either persists a delivery row.
- A prepared push can become stale if the user completes the action immediately after preparation and before transport. Exact delivery claiming, outbox, retries and backoff remain deferred.
- PostgreSQL/Testcontainers lock verification remains deferred to the final concurrency-test block.
