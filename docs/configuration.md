# Configuration

Production and shared development profiles should receive environment-specific values from environment variables or a secret manager, not from committed files.

## Profiles

- `local-firebase`: default local profile, Firebase auth, PostgreSQL datasource for local Docker runs and schedulers disabled.
- `local-nodb`: local H2 file database, dev auto-auth and schedulers disabled.
- `local-postgres`: local PostgreSQL, dev auto-auth, Flyway enabled and schedulers disabled.
- `dev`: external database, Firebase auth, Flyway enabled by default, schedulers enabled by default and local-only `/api/local-dev/**` controllers disabled.
- `prod`: external database, Flyway enabled, schedulers enabled.
- `test`: H2 in-memory test profile under `src/test/resources`.

## Placeholder Reference

`deploy/helm/values-dev.yaml` and `deploy/helm/values-prod.yaml` are temporary Helm-style values references. Their exact role is still undecided because the final Helm chart may live in a separate infrastructure repository.

If these values files are kept, they should contain deploy-time concerns such as image tag, replicas, probes, resource limits, service/ingress settings and references to config or secret keys. Application behavior settings such as scheduler cadence, chat durations and product limits are defined directly in `src/main/resources/application-dev.yml` and `src/main/resources/application-prod.yml`.

Use the deployment platform to inject non-sensitive configuration values, and
use its secret manager for sensitive values.

Non-sensitive runtime configuration:

| Variable | Required | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | Use `dev` or `prod` outside local development. |
| `DATABASE_URL` | yes | JDBC URL, for example `jdbc:postgresql://host:5432/reals`. |
| `DATABASE_USERNAME` | yes | PostgreSQL user. |
| `ACCOUNT_DELETION_RECOVERY_WINDOW_DAYS` | no | Defaults to `30`; controls how long a deleted account can be reactivated before finalization. |
| `STORAGE_S3_ENDPOINT` | when media upload is enabled | S3-compatible API endpoint used by the backend for object operations. For R2 use `https://<cloudflare-account-id>.r2.cloudflarestorage.com`. Legacy fallback: `S3_ENDPOINT`. |
| `STORAGE_S3_PRESIGNED_URL_ENDPOINT` | when returned URLs need a different public host | Endpoint used only when generating presigned read URLs. For R2 this usually matches `STORAGE_S3_ENDPOINT`; for Android Emulator local MinIO it may be `http://10.0.2.2:9000`. Legacy fallback: `S3_PRESIGNED_URL_ENDPOINT`. |
| `STORAGE_S3_REGION` | when media upload is enabled | Use `auto` for R2. Legacy fallback: `S3_REGION`. |
| `STORAGE_S3_BUCKET` | when media upload is enabled | Bucket names are not treated as secrets, but keep one value per environment. Legacy fallback: `S3_PROFILE_PHOTOS_BUCKET`. |
| `STORAGE_S3_PUBLIC_BASE_URL` | only with `STORAGE_S3_READ_URL_MODE=PUBLIC` | Public base URL used when objects are intentionally public. Not required for private R2 buckets in `PRESIGNED` mode. Legacy fallback: `S3_PUBLIC_BASE_URL`. |
| `STORAGE_S3_PATH_STYLE_ACCESS_ENABLED` | no | Keep `true` for MinIO and R2 unless testing proves otherwise. Legacy fallback: `S3_PATH_STYLE_ACCESS_ENABLED`. |
| `STORAGE_S3_READ_URL_MODE` | no | `PRESIGNED` by default for private buckets; `PUBLIC` only for intentionally public media. Legacy fallback: `S3_READ_URL_MODE`. |
| `STORAGE_S3_SIGNED_URL_DURATION_MINUTES` | no | Presigned read URL validity duration. Defaults to `15`. Legacy fallback: `S3_SIGNED_URL_DURATION_MINUTES`. |
| `PROFILE_PHOTO_MAX_SIZE_BYTES` | no | Maximum accepted multipart profile-photo file size. |
| `PROFILE_PHOTO_MODERATION_PROVIDER` | no | Profile photo moderation provider. Defaults to `none`, which approves without external review. |
| `PROFILE_PHOTO_MODERATION_FAIL_UPLOAD_ON_PROVIDER_ERROR` | no | If `true`, provider errors reject photo upload. Defaults to `false`, which persists `NEEDS_REVIEW`. |
| `PROFILE_PHOTO_MODERATION_PERSIST_REJECTED_PHOTOS` | no | If `true`, rejected photos can be persisted with `moderationStatus=REJECTED`. Defaults to `false`, which rejects upload before storage. |
| `PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION` | no | If `true`, profile activation requires every required photo to be moderation-approved. Defaults to `false` for MVP/local compatibility. |
| `RATE_LIMIT_SAFETY_REPORT_CAPACITY` | no | Token bucket capacity for `POST /api/safety/reports`. Defaults to `5`. |
| `RATE_LIMIT_SAFETY_REPORT_REFILL_TOKENS` | no | Tokens refilled for safety report creation. Defaults to `5`. |
| `RATE_LIMIT_SAFETY_REPORT_REFILL_PERIOD_SECONDS` | no | Safety report refill period in seconds. Defaults to `86400`. |
| `SCHEDULING_ACTIVATION_DELAY_MINUTES` | no | Production/dev override for the delay between mutual visual approval and scheduling becoming actionable. Defaults to `5` in current profiles. |
| `CHAT_FIRST_CHAT_DURATION_MINUTES` | no | Dev/prod first-chat absolute duration in minutes. Defaults to `15`. |
| `CHAT_FIRST_CHAT_INACTIVITY_THRESHOLD_MINUTES` | no | Dev/prod first-chat inactivity threshold in minutes. Defaults to `5`. Legacy fallback: `SCHEDULER_INACTIVITY_CHECK_JOB_INACTIVITY_THRESHOLD_MINUTES`. |
| `SCHEDULER_MATCHMAKING_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for queued-user matchmaking. Defaults to `60000`. |
| `SCHEDULER_MATCHMAKING_JOB_MAX_PAIRS_PER_RUN` | no | Dev/prod upper bound for pairs processed per matchmaking run. Defaults to `10`. |
| `SCHEDULER_CHAT_TIMEOUT_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for first-chat absolute timeout expiration. Defaults to `60000`. |
| `SCHEDULER_SECOND_CHAT_LIFECYCLE_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for second-chat availability, timeout and read-only cleanup. Defaults to `120000`. |
| `SCHEDULER_SECOND_CHAT_REMINDER_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for `SecondChatReminderNotificationJob`. Defaults to `60000`, which gives a 1-minute reminder pickup window. |
| `SCHEDULER_MATCH_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for stale match expiration fallback. Defaults to `300000`. |
| `SCHEDULER_MATCH_EXPIRATION_JOB_MAX_CHAT_DURATION` | no | Dev/prod ISO-8601 duration for first-chat match expiration fallback. Defaults to `PT20M`. |
| `SCHEDULER_INACTIVITY_CHECK_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for inactivity abandonment checks. Defaults to `60000`. |
| `SCHEDULER_INACTIVITY_CHECK_JOB_INACTIVITY_THRESHOLD_MINUTES` | no | Legacy fallback for first-chat inactivity threshold. Prefer `CHAT_FIRST_CHAT_INACTIVITY_THRESHOLD_MINUTES`. |
| `SCHEDULER_PENALTY_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for expiring temporary penalties. Defaults to `600000`. |
| `SCHEDULER_VISUAL_PHASE_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for visual phase expiration. Defaults to `300000`. |
| `SCHEDULER_SCHEDULING_TIMEOUT_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for scheduling negotiation timeout cleanup. Defaults to `900000`. |
| `SCHEDULER_SCHEDULING_ACTIVATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for enabling deferred scheduling. Defaults to `60000`. |
| `SCHEDULER_ACCOUNT_DELETION_FINALIZATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for finalized recoverable account deletion cleanup. Defaults to `3600000`. |
| `NOTIFICATIONS_SECOND_CHAT_REMINDER_MINUTES_BEFORE` | no | Comma-separated positive lead-time list for confirmed second-chat reminders, for example `120,10`. Defaults to `10`; keep multiple values in descending order for readability. |
| `IDENTITY_VERIFICATION_PROVIDER` | no | Defaults to `none`. |

Sensitive runtime secrets:

| Variable | Required | Notes |
| --- | --- | --- |
| `DATABASE_PASSWORD` | yes | PostgreSQL password. |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | one Firebase credential source | Preferred for lightweight container runtimes. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | one Firebase credential source | Raw service-account JSON when the platform supports multiline secrets safely. |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | one Firebase credential source | Path to a mounted service-account JSON file. |
| `STORAGE_S3_ACCESS_KEY_ID` | when S3 credentials are not provided by the runtime | MinIO/R2/S3-compatible access key. Legacy fallback: `S3_ACCESS_KEY_ID`. |
| `STORAGE_S3_SECRET_ACCESS_KEY` | when S3 credentials are not provided by the runtime | MinIO/R2/S3-compatible secret key. Legacy fallback: `S3_SECRET_ACCESS_KEY`. |
| `IDENTITY_VERIFICATION_API_KEY` | when a non-`none` provider exists | Keep empty while identity verification is disabled. |

S3-compatible storage has two endpoint concerns. `STORAGE_S3_ENDPOINT` is where the
backend writes and deletes objects. `STORAGE_S3_PRESIGNED_URL_ENDPOINT` is the host
embedded in returned presigned URLs and must be reachable by the client that
renders the image. Profile photo rows store storage metadata, including the
object key, and response URLs are generated from that key when the API returns a
photo DTO.

For Cloudflare R2 shared/dev/prod-like environments, see
`docs/storage-r2-configuration.md`. R2 should use private buckets and
`STORAGE_S3_READ_URL_MODE=PRESIGNED` for MVP.

For AWS deployments, prefer IAM roles for S3 access instead of long-lived access keys. In that setup the application usually only needs the bucket setting; AWS credentials come from the runtime role.

## Helm Chart Location

The Helm chart does not have to live in this repository. A common setup is:

- Application repository: source code, Dockerfile/build pipeline metadata and per-environment `values-*.yaml`.
- Infrastructure repository: shared Helm chart, Kubernetes templates, cluster-specific configuration and deployment automation.

In that setup, CI builds and publishes the image from this repository, then the deployment pipeline applies the chart from the infrastructure repository using this app's values file.

## Environment-Specific Files

`application-dev.yml` and `application-prod.yml` intentionally repeat operational settings such as scheduler cadence, chat durations, scheduling limits and profile photo limits. This makes deploy-time behavior explicit without relying on implicit inheritance from `application.yml`.

These files should use placeholders only for environment-specific or secret-backed values, such as database credentials, S3 bucket/region, Firebase service account location and future identity-verification credentials.

Do not commit real credentials in any `application-*.yml` file.

## Scheduler Cadences

Dev and prod use the same MVP scheduler defaults:

| Job | Default | Operational purpose |
| --- | --- | --- |
| `MatchmakingJob` | `60000` ms, `10` pairs/run | Keep queued users moving into first chats. |
| `SchedulingActivationJob` | `60000` ms | Make scheduling actionable shortly after the deferred availability time. |
| `SecondChatReminderNotificationJob` | `60000` ms | Pick up second-chat reminders within a 1-minute window. |
| `ChatTimeoutJob` | `60000` ms | Expire timed-out first chats without leaving users stuck. |
| `SecondChatLifecycleJob` | `120000` ms | Make due second chats available, expire inactive scheduled windows, expire active second chats and close read-only chats. |
| `InactivityCheckJob` | `60000` ms, `5` minute threshold | Abandon inactive first chats before the absolute timeout when no messages are sent. |
| `MatchExpirationJob` | `300000` ms, `PT20M` first-chat fallback | Safety net for stale matches that did not progress after first-chat timeout handling. |
| `VisualPhaseExpirationJob` | `300000` ms | Expire visual reviews whose deadline passed. |
| `PenaltyExpirationJob` | `600000` ms | Remove expired temporary penalties from active enforcement. |
| `SchedulingNegotiationTimeoutJob` | `900000` ms | Close scheduling negotiations after their deadline. |
| `AccountDeletionFinalizationJob` | `3600000` ms | Finalize deleted accounts after the recovery window. |

These defaults are intentionally more frequent for user-visible bottlenecks and
less frequent for hour/day-scale cleanup. Local profiles keep
`scheduler.enabled=false`; use Bruno or local-dev endpoints to trigger the same
jobs deterministically.

Scheduling has two separate jobs. `SchedulingActivationJob` moves deferred
connections from `SCHEDULING_PENDING` to `SCHEDULING_PHASE` once
`schedulingAvailableAt` is due and initializes the negotiation. Only after that
does `SchedulingNegotiationTimeoutJob` close expired scheduling negotiations.
`schedulingExpiresAt` on a pending connection is provisional because activation
recalculates the actionable deadline from the activation time.

## Identity Verification

`identity-verification.provider` currently supports only `none`. The provider
abstraction exists so a real identity-verification integration can be added
later without changing profile creation flow. Identity verification is invoked
explicitly through `POST /api/me/profile/identity-verification`; profile
creation does not call the provider. With `provider=none`, profiles keep
`identityVerified=false`.

`IDENTITY_VERIFICATION_API_KEY` is reserved for a future provider and should stay
empty until that provider is implemented.

## Matchmaking Tuning

`matchmaking.candidate-pair-limit` controls how many SQL-filtered candidate pairs are scored per matchmaking selection. Local and test profiles keep this low for deterministic, cheap checks. Dev/prod use higher starting values and should be adjusted using queue size, job duration and match creation metrics.

`matchmaking.min-compatibility-score` discards scored pairs below the configured threshold. `matchmaking.early-accept-compatibility-score` stops scoring as soon as a strong enough pair is found. Scores are expected to be normalized from `0.0` to `1.0`.

## How Injection Works

The application only sees environment variables such as `DATABASE_PASSWORD` or `STORAGE_S3_BUCKET`. The CI/CD or runtime platform is responsible for resolving those values from its own config store or secret manager before starting the process.

Example flow:

1. `application-prod.yml` references `${DATABASE_PASSWORD}`.
2. `deploy/helm/values-prod.yaml` says `DATABASE_PASSWORD` should come from secret key `database_password`.
3. The deploy pipeline reads that secret from its configured secret manager.
4. The pipeline starts the app with `DATABASE_PASSWORD=<resolved secret>`.
5. Spring resolves `${DATABASE_PASSWORD}` at startup.

## Health And Smoke Checks

Runtime readiness checks should call:

```http
GET /actuator/health/readiness
```

Runtime liveness checks should call:

```http
GET /actuator/health/liveness
```

Post-deploy smoke checks should verify readiness, ping and the image metadata
served by `/actuator/info`:

```http
GET /actuator/info
GET /api/ping
```

`/actuator/info` includes Docker image metadata when the application is started
from a CI-built image:

```json
{
  "image": {
    "repository": "ghcr.io/gtestino92/reals-backend",
    "tag": "development",
    "revision": "<full-git-sha>"
  }
}
```

Use the manual `Smoke check` GitHub Actions workflow after a runtime update. For
the dev environment, set `expected_image_repository` to
`ghcr.io/gtestino92/reals-backend`, `expected_image_tag` to `development` and
`expected_image_revision` to the deployed commit SHA or its prefix.
