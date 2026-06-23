# Technical Debt And Product Decisions

This file lists known pending or intentionally unimplemented behavior. Do not implement these implicitly while working on unrelated tasks.

## Product Decisions

- Immediate pre-infrastructure objective: continue converting high-frequency frontend-facing generic failures into stable error codes where they are painful in the Android flow.
- Guided first-chat questions or conversation starters.
- Whether guided questions belong to frontend or backend.
- Exact visibility rule for visual-review personal messages beyond current `VISUAL_APPROVED` enforcement.
- Revisit a dedicated matchmaking worker process or external queue only if matchmaking volume, latency requirements or CPU cost make the scheduled DB-queue `MatchmakingJob` too expensive for app instances.
- Profile location should eventually be validated against a canonical country/city cache instead of only accepting free-text `country` and `city`. Prefer a separate reference endpoint such as `GET /api/reference/countries` returning ISO-3166 alpha-2 country codes and display names, rather than embedding global reference data in `GET /api/me/profile`.
- Decide where geolocation enters the product flow. The likely point is before first-chat matchmaking/search, using profile location plus future latitude/longitude, geohash or search radius fields.
- Decide final visibility and UX timing for visual-review personal messages. Current behavior allows reading the partner message during visual review and requires reading it before approving if the partner already submitted one.

## Not Currently Implemented

- Real-time chat via WebSocket or SSE.
- Notification delivery.
- Reveal quotas.
- Advanced compatibility scoring. Current matching uses SQL hard-filtered candidate pairs plus a rule-based `CompatibilityScorer`; future work should add interest/affinity overlap without introducing popularity, attractiveness or ELO-style ranking.
- Improve geographic matching with geohash, bounding boxes or database-supported spatial indexing if queue volume makes application-level Haversine filtering too expensive.
- Decide how `accuracyMeters` should affect matchmaking. It is currently captured and validated with the queue search location, but does not reject imprecise locations or adjust the effective distance radius.
- ML-based matching.
- Popularity, attractiveness or ELO ranking.
- Gamified reputation badges.
- Production trust score based on real behavior. The current `DefaultTrustScoreEvaluator` is intentionally neutral and returns `TrustScore.NEUTRAL`, so penalty duration scaling is effectively disabled. A real implementation needs a product decision on inputs and weights, such as penalty count/recency/severity, abandonment rate from chat history and positive engagement signals like completed connections. Do not introduce popularity, attractiveness, ELO-style ranking or visible reputation badges.
- Full moderation workflow for safety reports. Current implementation records safety cancellation and applies a penalty, but no manual review workflow exists yet.
- Firebase/JWT backend wiring exists for `dev` and `prod`, but it still needs production service account configuration and operational validation before production use.
- Firebase email verification is not enforced yet. Decide whether the backend should reject provisioning or profile activation when the Firebase token has `email_verified=false`, add a stable error such as `EMAIL_NOT_VERIFIED`, and define the frontend resend/refresh flow.
- Media storage for profile photos now supports S3-compatible upload and presigned read URLs. Remaining production work includes object lifecycle rules, orphan cleanup, moderation/quarantine promotion, malware/content scanning and CDN/cache strategy.
- Photo semantic flags (`isPersonPhoto`, `isFullBody`) currently come from the client so the frontend can unblock profile-photo flows. Before production trust is required, move these flags to a trusted source such as automatic media validation, moderation review or admin tooling, and restrict direct client overrides to local/dev/test flows.
- Identity verification has a provider abstraction and a `none` provider that keeps `Profile.identityVerified=false`. Add a real provider integration, request/response mapping, audit trail and failure policy before requiring verified identity in production flows.

## Infrastructure Gaps

- PostgreSQL is the only supported non-local database driver for now. Reintroduce another database driver only when a concrete environment needs it.
- Local H2 profiles use file storage and disable Flyway. Keep this local-only; external environments should use PostgreSQL plus Flyway.
- Keep Spring Boot on the latest stable `4.0.x` patch line until `4.1.x` is stable and the release notes have been reviewed.
- Remove the temporary `tomcat.version` override once a Spring Boot 4.0.x patch manages Tomcat 11.0.22 or newer.
- Add production release image tagging when a production environment exists. Dev should keep using moving `development` and immutable `sha-*` tags; production should publish immutable `v*` tags from Git tags, such as `v1.0.0`.
- Helm-style values under `deploy/helm` are placeholders for app-specific deployment inputs. Decide whether the final chart/deploy config lives in this repository or a separate infrastructure repository once the first runtime platform is chosen.
- Decide the first external development deploy target. Candidates to compare: Render, Fly.io, Railway, Google Cloud Run, AWS App Runner or ECS Fargate, and a managed PostgreSQL provider such as Neon, Supabase, Render PostgreSQL, Railway PostgreSQL or AWS RDS.
- For the first dev environment, prefer a simple container platform plus managed PostgreSQL before Kubernetes. Kubernetes, Helm and Terraform/CDK become worthwhile when there are multiple services, networking rules, autoscaling needs or repeatable environment provisioning requirements.
- Before enabling deploy automation, define the deployment model: runtime platform, managed PostgreSQL instance, Firebase service-account secret, environment variables, health check path, rollback strategy and which GHCR tag dev should track.
- Wire the manual `Smoke check` GitHub Actions workflow into the eventual deploy pipeline once the dev runtime platform exists. The workflow is already aligned with the Docker image metadata exposed by `/actuator/info`.
- Decide whether infrastructure should be represented as Infrastructure as Code. If the first provider is AWS, prefer Terraform or AWS CDK for repeatability; if the first provider is Render, Fly.io or Railway, start with their service config and document manual console steps until the shape stabilizes.

## Observability And Error Handling

- Add metrics export before production. Actuator health/info is available, but metrics are intentionally disabled for now. Later track HTTP latency/statuses, auth failures by reason, scheduled job runs, processed/skipped/failed item counts and key state transitions.
- Continue hardening scheduled jobs so one failing record does not abort an entire run. Scheduler jobs now log final processed/succeeded/skipped/failed summaries; future work should add metrics export once the backend chooses an observability stack.
- Include exception stacktraces in job failure logs. Avoid logging only `ex.message` for unexpected scheduler failures.
- Continue replacing generic `IllegalArgumentException` and `IllegalStateException` paths with explicit domain exception types and stable error codes where the frontend needs deterministic handling.
- Add production log policy for sensitive fields. Do not log tokens, chat contents, personal messages, full emails, private media URLs or raw request bodies.

## Security Decisions

- CSRF protection is intentionally disabled while Reals remains a stateless API authenticated with explicit `Authorization: Bearer ...` tokens and no cookie-based browser session. Re-enable and test CSRF protection before introducing cookie authentication, form login, browser-managed sessions or any credential automatically attached by the browser.
- Never commit real Firebase Web API keys, Firebase test user passwords, ID tokens or service-account credentials. Bruno tracked environments must keep placeholders; real values belong only in local uncommitted environment state or deployment secrets.
- Local Bruno environment files with real credentials must use ignored local files, not the tracked `local.template.bru`.
- For MVP, chat and visual personal message contents remain stored as plain application text in the database. Before production, revisit message data protection for private/sensitive user-generated content: define retention/deletion rules, keep request/response bodies out of logs, restrict/audit internal access, and evaluate application-level field encryption with keys stored outside the database.

## Multi-Instance Deployment Risks

- Scheduler jobs must remain safe when more than one app instance exists. ShedLock prevents most duplicate scheduled executions, but each job should still be idempotent: re-running it should not create duplicate chats, penalties, locks or state transitions.
- State transitions that create dependent records need stronger concurrency protection before multi-instance production. Examples: mutual visual approval creating a `Connection`, scheduling confirmation creating/activating second-chat availability, unilateral cancellation creating penalties, and lock creation/release. Use database constraints, transactions and optimistic or pessimistic locking where needed.
- Active engagement limits can race under concurrent requests. Counting current `ActiveEngagementLock` rows and then inserting new rows is not enough by itself if two app instances do it at the same time. Revisit with transactional locking or database-level constraints before real scale.
- Matchmaking processing uses a scheduled DB-queue worker with PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`. A focused PostgreSQL/Testcontainers concurrency test covers two simultaneous processors against the same queue; keep ShedLock enabled until parallel matchmaking workers are explicitly introduced.
- Profile photo files must remain in shared object storage such as S3, R2, GCS, Firebase Storage or another external media service. They should not be stored on one backend instance's local disk, because another instance would not have the file.
- Future real-time chat via WebSocket or SSE needs multi-instance routing. Options include sticky sessions, a shared pub/sub layer such as Redis, or managed realtime infrastructure. Plain in-memory connection state will not work across instances.
- Add production observability before multi-instance rollout: structured logs with request/job identifiers, metrics for scheduled jobs and state transitions, and alerts for stuck negotiations, failed jobs or repeated retries.

## Concurrency Hardening Tasks

- Add explicit tests for concurrent mutual visual approval. Assert that only one `Connection` is created for a match, locks are upgraded once and repeated/competing approvals do not duplicate state.
- Add explicit tests for concurrent scheduling confirmation and scheduled second-chat availability. Assert that only one second chat exists per connection and repeated job runs are idempotent.
- Add explicit tests for concurrent chat cancellation/safety reports. Assert that penalties and `ChatExitRequest` rows are not duplicated for the same terminal chat transition.
- Review and document lock acquisition order for operations that lock both users. Keep the current canonical order by user id and apply the same rule to future user-pair operations to reduce deadlock risk.
- Convert expected concurrency failures such as optimistic-lock conflicts, duplicate-key conflicts and deadlocks into stable API responses or bounded job retries where the operation is idempotent.
- Add database constraints where missing for one-per-domain invariants. Current examples already covered include one connection per match, one chat per match/type, one chat per connection/type and one queue row per user; revisit `ChatExitRequest`, penalties and schedule proposals before production traffic.
- Extend job-level summaries with duplicate/idempotent no-op detail where a future job can distinguish that from generic skipped work. Keep metrics export as a later step if Prometheus or another metrics backend is introduced.

## Long-Term Scalability

- Parallel matchmaking workers are intentionally deferred. The current `MatchmakingJob` should remain ShedLock-protected so only one instance processes the queue. If matchmaking queue backlog or latency becomes a real bottleneck, revisit a worker model where multiple instances process the queue concurrently using PostgreSQL `FOR UPDATE SKIP LOCKED`, bounded per-worker limits, short transactions, database constraints and dedicated concurrency tests.

## Code Notes To Revisit

- Some controller comments mention old or tentative behavior; prefer service implementation and these docs as the current source of truth.
- `TECH_DEBT.md` recovered from the previous setup was empty.


## To improve:

Explicit terminal states for historical flows

-Current lifecycle cleanup leaves some historical records in semantically valid but non-terminal-looking states. Example: a Match can remain in VISUAL_APPROVED after its derived Connection has already reached CLOSED, and a ScheduleNegotiation can remain CONFIRMED after the second-chat lifecycle has ended.

-This is not currently a functional issue because these records are no longer visible in Home and no longer hold active_engagement_locks. However, it can make operational/debug queries less clear.

-Potential future improvement:

    Add more explicit terminal/history states, such as MatchState.CONNECTED / COMPLETED or a separate read model.
    Add a terminal scheduling state such as ScheduleNegotiationStatus.CLOSED / COMPLETED, or keep CONFIRMED but document that it is historical and not active.  
    Ensure analytics/backoffice queries distinguish “historically successful” from “currently actionable”.



## Pre-MVP technical debt / cleanup

### 1. Temporary permissive profile photo validation

For the MVP, uploaded profile photos may use permissive backend defaults so the Android client does not need to send temporary semantic flags (`isPersonPhoto`, `isFullBody`) before real image analysis exists.

Current pre-MVP approach:

* Treat uploaded photos as validated enough to unblock profile activation.
* Default uploaded photos to `isPersonPhoto = true`.
* Default uploaded photos to `isFullBody = true`.
* Keep this explicitly documented as temporary behavior.

Later implementation:

* Replace permissive defaults with real image validation.
* Detect whether the photo contains a person.
* Detect whether the photo is full-body.
* Keep profile activation dependent on validated photo semantics.
* Remove any temporary backend assumptions once validation is available.

### 2. Matchmaking job configuration clarity

`MatchmakingJob` already exists and processes queued users into first-chat matches through `MatchmakingProcessorService`.

Pre-MVP cleanup:

* Add explicit config entries for `scheduler.matchmaking-job.fixed-delay` and `scheduler.matchmaking-job.max-pairs-per-run` in application configuration, instead of relying only on defaults in the annotation.
* Confirm that the target MVP environment has schedulers enabled.
* Confirm that local/dev environments can keep schedulers disabled when manual DevJobController execution is preferred.

### 3. Explicit terminal states for historical flows

Some historical records can remain in states that are semantically valid but not obviously terminal. Example: a `Match` can remain `VISUAL_APPROVED` after its derived `Connection` has already reached `CLOSED`, and a `ScheduleNegotiation` can remain `CONFIRMED` after the second-chat lifecycle has ended.

This is not currently a functional issue because these records are no longer visible in Home and no longer hold `active_engagement_locks`.

Future improvement:

* Add more explicit terminal/history states such as `MatchState.CONNECTED`, `COMPLETED`, or a separate read model.
* Add a terminal scheduling state such as `ScheduleNegotiationStatus.CLOSED` / `COMPLETED`, or document that `CONFIRMED` is historical and not active.
* Ensure analytics and backoffice queries distinguish historical success from currently actionable records.

### 4. Canonical city/country validation

Profile city and country are currently plain validated text. A future improvement is to validate them against a canonical cached location dataset.

This is not required before MVP if matchmaking relies primarily on search coordinates and distance filters.

### 5. Legacy photo URL endpoints

The backend still keeps legacy profile-photo endpoints based on external URLs. The official MVP flow should be multipart upload.

Future cleanup:

* Keep legacy endpoints during transition if useful for tests.
* Mark multipart upload as the official app flow.
* Remove legacy URL-based endpoints once Android no longer depends on them.

### Profile photo validation, activation flags and moderation

Profile photo validation must be separated from semantic activation requirements and user identity verification.

Definitions:

* Technical upload validation: confirms that the file can be accepted and stored.
* `validation_status`: controls whether a photo is allowed to be part of the visible/public profile.
* `isPersonPhoto`: semantic flag indicating whether the photo contains a person.
* `isFullBody`: semantic flag indicating whether the photo qualifies as full-body.
* `identityVerified`: separate user-level identity verification. It is not implied by profile photos.

## MVP behavior

For MVP, image validation is intentionally permissive.

### Upload flow

When the user uploads a profile photo through multipart upload:

1. Backend performs technical validation:

    * file exists;
    * file is not empty;
    * file size is within the configured limit;
    * content type is allowed;
    * storage succeeds;
    * ideally, the file can be decoded as an image and has acceptable dimensions.

2. If technical validation fails:

    * the upload is rejected;
    * no usable profile photo should be created.

3. If technical validation passes:

    * the photo is stored;
    * `validation_status = VALIDATED`;
    * `isPersonPhoto = true`;
    * `isFullBody = true`.

This is a deliberate pre-MVP shortcut to unblock profile activation and end-to-end testing.

### Activation flow

For MVP, profile activation may continue using the existing photo requirements, but because uploaded photos are temporarily marked as `VALIDATED`, `isPersonPhoto = true`, and `isFullBody = true`, activation is effectively gated by:

* required number of uploaded photos;
* successful technical upload/storage;
* existing profile state rules.

### MVP meaning of `VALIDATED`

For MVP only:

* `VALIDATED` means the photo passed technical upload validation and is allowed to be used in the visible profile.
* `VALIDATED` does not mean that real image moderation has happened.
* `VALIDATED` does not mean that the system truly verified person/full-body semantics.
* The `isPersonPhoto` and `isFullBody` flags are temporary permissive defaults.

### MVP moderation stance

Strict automatic image moderation is not required for MVP.

However, the product should still keep a basic path to handle objectionable content:

* user safety reports;
* admin/backoffice review;
* ability to penalize or ban users;
* future ability to hide, reject, or remove problematic photos.

## Post-MVP behavior

After MVP, profile photo validation should become stricter and more semantically accurate.

### Intended future upload flow

1. User uploads a profile photo.
2. Backend performs technical upload validation.
3. If technical validation passes, the photo is stored as:

    * `validation_status = PENDING`;
    * `isPersonPhoto = false` or unknown/default;
    * `isFullBody = false` or unknown/default.
4. The photo is later analyzed by a trusted process.

The trusted process may be:

* automatic image analysis;
* moderation review;
* third-party provider;
* hybrid automatic + manual review.

### Intended future activation flow

When the user attempts to activate the profile:

1. Backend finds all photos for the profile.
2. Backend analyzes photos that are still `PENDING`, or reads existing analysis results.
3. Backend updates each analyzed photo:

    * `validation_status = VALIDATED` or `FAILED`;
    * `isPersonPhoto`;
    * `isFullBody`;
    * optional future metadata such as provider, analyzed timestamp, failure reason or model version.
4. Backend evaluates activation requirements using only photos with `validation_status = VALIDATED`.

Activation should require:

* enough `VALIDATED` photos;
* enough `VALIDATED` photos with `isPersonPhoto = true`;
* enough `VALIDATED` photos with `isFullBody = true`.

### Open future product decision

Decide whether activation-time analysis should:

Option A:

* analyze all profile photos before activation;
* only allow activation if the full visible photo set is acceptable.

Option B:

* analyze photos until the minimum activation requirements are satisfied;
* leave the rest for later analysis or treat them as non-counting/non-visible until validated.

Preferred long-term direction:

* analyze all photos that will be visible to other users;
* avoid showing unvalidated photos in visual review.

### Future meaning of `VALIDATED`

Post-MVP:

* `VALIDATED` means the photo is accepted for public profile display.
* `VALIDATED` does not necessarily mean `isPersonPhoto = true`.
* `VALIDATED` does not necessarily mean `isFullBody = true`.
* A photo may be valid as a profile photo but not count toward person/full-body requirements, depending on product rules.

Examples:

* clear selfie: `VALIDATED`, `isPersonPhoto = true`, `isFullBody = false`;
* clear full-body photo: `VALIDATED`, `isPersonPhoto = true`, `isFullBody = true`;
* allowed non-person photo: `VALIDATED`, `isPersonPhoto = false`, `isFullBody = false`;
* prohibited or unusable photo: `FAILED`.

### Future `FAILED` cases

A photo may become `FAILED` if:

* it is not decodable as an image;
* it is corrupt;
* it violates content rules;
* it contains prohibited or objectionable content;
* it is too blurry or unusable, if that becomes a product rule;
* it fails moderation or provider analysis.

### Identity verification remains separate

Photo validation and semantic photo analysis do not verify identity.

Do not infer `identityVerified = true` from:

* uploaded photos;
* full-body photos;
* person-photo detection;
* visual approval by another user.

Identity verification is a separate post-MVP feature that may require:

* selfie/liveness check;
* document verification;
* provider integration;
* audit trail;
* retry/failure handling;
* clear frontend UX.

