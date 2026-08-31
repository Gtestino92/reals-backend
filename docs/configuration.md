# Configuration

Production and shared development profiles should receive environment-specific values from environment variables or a secret manager, not from committed files.

## Profiles

- `local-firebase`: local Firebase auth, PostgreSQL datasource for local Docker runs and schedulers disabled.
- `local-nodb`: local H2 file database, dev auto-auth and schedulers disabled.
- `local-postgres`: local PostgreSQL, dev auto-auth, Flyway enabled and schedulers disabled.
- `dev`: external database, Firebase auth, Flyway enabled by default, schedulers enabled by default and `/api/local-dev/**` tooling registered for authenticated `ROLE_ADMIN` users.
- `prod`: external database, Flyway enabled, schedulers enabled.
- `test`: H2 in-memory test profile under `src/test/resources`.

Exactly one execution profile from this set must be active:

```text
local-nodb
local-postgres
local-firebase
dev
prod
test
```

The shared `application.yml` does not set a default execution profile. Local
Docker selects `local-firebase` explicitly, and shared deployments must set
`SPRING_PROFILES_ACTIVE` to `dev` or `prod`. Startup fails when no execution
profile is active or when more than one execution profile is active, for example
`prod,local-firebase` or `dev,prod`. Auxiliary profiles are allowed as long as
they do not add a second execution profile.

`/api/local-dev/**` is tooling for controlled Bruno/manual verification. It
remains unauthenticated in `local-nodb`, `local-postgres` and
`local-firebase`. Hosted `dev` registers the same paths but requires an
authenticated `ROLE_ADMIN` user. Admin role assignment uses the existing
Firebase-backed local user plus the verified Firebase token email in the
`BACKOFFICE_ADMIN_EMAILS` allowlist. The persisted backend email is not an
administrator allowlist fallback. `prod` and `test` deny the route prefix, and
`prod` does not register the real `Dev*` controllers.

The H2 console is accessible only with `local-nodb`; Spring Security explicitly
denies `/h2-console/**` for every other execution profile.

Firebase App Check mode defaults are profile-specific:

- `local-firebase`: `DISABLED`; may be explicitly set to `MONITOR` or
  `ENFORCED` for local Android debug-provider end-to-end testing, using normal
  token verification.
- `dev`: `DISABLED`; intended rollout is configure Firebase project number and
  accepted dev Firebase App ID, switch to `MONITOR`, deploy Android App Check,
  inspect results, then switch to `ENFORCED`.
- `prod`: `ENFORCED`; startup fails if mode is not `ENFORCED`, the Firebase
  project number is blank or nonnumeric, the Firebase App ID allowlist is empty,
  or the JWKS URI is blank/invalid.

External-provider defaults are intentionally different by profile:

| Integration | Local | Dev default | Dev smoke | Prod |
| --- | --- | --- | --- | --- |
| Sightengine | no-op `none` provider | no-op `none` provider | real opt-in with `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` | required real Sightengine |
| Firebase Auth | `local-firebase` real Admin SDK; non-Firebase local profiles use local auto-auth | real Firebase Admin verification | real Firebase Admin verification | real Firebase Admin verification |
| App Check | disabled by default in `local-firebase` | disabled by default | `MONITOR` or `ENFORCED` with project/app config | `ENFORCED` required |
| FCM | no-op outside Firebase profiles; Firebase sender in `local-firebase` | real Firebase Admin Messaging | real device delivery through normal flows | real Firebase Admin Messaging |
| Storage | local MinIO for `local-firebase` | configured DEV S3-compatible bucket | real DEV bucket upload/read/replace/delete | private PROD S3-compatible bucket |
| Firebase account deletion | no-op only when Firebase Admin is not configured | real Firebase Auth deletion during finalization | disposable DEV Firebase user | real Firebase Auth deletion during finalization |

## Placeholder Reference

`deploy/helm/values-dev.yaml` and `deploy/helm/values-prod.yaml` are temporary Helm-style values references. Their exact role is still undecided because the final Helm chart may live in a separate infrastructure repository.

If these values files are kept, they should contain deploy-time concerns such as image tag, replicas, probes, resource limits, service/ingress settings and references to config or secret keys. Application behavior settings such as scheduler cadence, chat durations and product limits are defined directly in `src/main/resources/application-dev.yml` and `src/main/resources/application-prod.yml`.

The Helm-style values references expose App Check deployment inputs without
real identifiers: dev declares the safe initial `DISABLED` mode, prod declares
`ENFORCED`, and both reference the Firebase project number plus accepted
Firebase App IDs through `configRefs`. Local Docker Compose also accepts
optional App Check overrides while remaining disabled by default.

Use the deployment platform to inject non-sensitive configuration values, and
use its secret manager for sensitive values.

Non-sensitive runtime configuration:

| Variable | Required | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | Use `dev` or `prod` outside local development. |
| `DATABASE_URL` | yes | JDBC URL, for example `jdbc:postgresql://host:5432/reals`. |
| `DATABASE_USERNAME` | yes | PostgreSQL user. |
| `FIREBASE_APP_CHECK_MODE` | prod: yes, dev/local: no | `DISABLED`, `MONITOR` or `ENFORCED`. Defaults to `DISABLED` in `local-firebase`/`dev` and `ENFORCED` in `prod`; prod rejects any non-`ENFORCED` value. |
| `FIREBASE_PROJECT_NUMBER` | prod and enabled App Check | Numeric Firebase project number used for App Check issuer and audience checks. This is not the Firebase project ID. |
| `FIREBASE_APP_CHECK_ALLOWED_APP_IDS` | prod and enabled App Check | Comma-separated Firebase App IDs accepted from the App Check token subject. These are not Android package names. |
| `FIREBASE_APP_CHECK_JWKS_URI` | no | App Check JWKS URI. Defaults to `https://firebaseappcheck.googleapis.com/v1/jwks`; when App Check is enabled it must be an absolute HTTPS URI with a host. Override only for controlled testing. |
| `FIREBASE_AUTH_REVOCATION_CACHE_TTL` | no | Successful Firebase ID-token revocation/disabled-user checks are cached for this duration. Defaults to `PT60S`; token signature and expiry are still validated on every request. |
| `FIREBASE_WEB_API_KEY` | password reset delivery | Firebase Web API key used only for Identity Toolkit `accounts:sendOobCode` password-reset delivery. Do not commit real keys. If blank, public reset responses remain generic and no reset email is sent. |
| `FIREBASE_AUTH_REST_BASE_URL` | no | Identity Toolkit base URL. Defaults to `https://identitytoolkit.googleapis.com/v1`; override only for controlled tests or local stubs. |
| `FIREBASE_AUTH_REST_CONNECT_TIMEOUT_MS` | no | Connect timeout for Firebase Auth REST calls. Defaults to `3000`. |
| `FIREBASE_AUTH_REST_READ_TIMEOUT_MS` | no | Read timeout for Firebase Auth REST calls. Defaults to `10000`. |
| `ACCOUNT_DELETION_RECOVERY_WINDOW_DAYS` | no | Defaults to `30`; controls how long a deleted account can be reactivated before finalization. |
| `STORAGE_S3_CREDENTIALS_MODE` | no | S3 credential mode. Defaults to `STATIC` for backward compatibility. Use `DEFAULT_CHAIN` for AWS-hosted EC2/ECS runtimes that should consume the AWS SDK default credential provider chain. Legacy fallback: `S3_CREDENTIALS_MODE`. |
| `STORAGE_S3_ENDPOINT` | S3-compatible providers only | Optional S3-compatible API endpoint used by the backend for object operations. Set it for MinIO/R2. Leave it absent/blank for native Amazon S3 so the AWS SDK resolves the normal regional endpoint. For R2 use `https://<cloudflare-account-id>.r2.cloudflarestorage.com`. Legacy fallback: `S3_ENDPOINT`. |
| `STORAGE_S3_PRESIGNED_URL_ENDPOINT` | when returned URLs need a different public host | Optional endpoint used only when generating presigned read URLs. Precedence is this value, then `STORAGE_S3_ENDPOINT`, then the AWS regional endpoint. For R2 this usually matches `STORAGE_S3_ENDPOINT`; for Android Emulator local MinIO it may be `http://10.0.2.2:9000`. Legacy fallback: `S3_PRESIGNED_URL_ENDPOINT`. |
| `STORAGE_S3_REGION` | when media upload is enabled | Required and nonblank. Use a real AWS Region such as `us-east-1` when `STORAGE_S3_ENDPOINT` is absent. Use `auto` only with an explicit S3-compatible endpoint such as R2. Legacy fallback: `S3_REGION`. |
| `STORAGE_S3_BUCKET` | when media upload is enabled | Application media bucket. New hosted deployments must set this variable. Bucket names are not treated as secrets, but keep one value per environment. Deprecated fallback: `S3_PROFILE_PHOTOS_BUCKET`. |
| `STORAGE_S3_PUBLIC_BASE_URL` | only with `STORAGE_S3_READ_URL_MODE=PUBLIC` | Public base URL used when objects are intentionally public. Not required for private R2 buckets in `PRESIGNED` mode. Legacy fallback: `S3_PUBLIC_BASE_URL`. |
| `STORAGE_S3_PATH_STYLE_ACCESS_ENABLED` | no | Keep `true` for MinIO and R2 unless testing proves otherwise. Native Amazon S3 should normally use `false`. Legacy fallback: `S3_PATH_STYLE_ACCESS_ENABLED`. |
| `STORAGE_S3_READ_URL_MODE` | no | `PRESIGNED` by default for private buckets; `PUBLIC` only for intentionally public media outside `prod`. `prod` refuses to start with `PUBLIC`. Legacy fallback: `S3_READ_URL_MODE`. |
| `STORAGE_S3_SIGNED_URL_DURATION_MINUTES` | no | Presigned read URL validity duration. Defaults to `15`; `prod` startup rejects non-positive values. Legacy fallback: `S3_SIGNED_URL_DURATION_MINUTES`. |
| `PROFILE_PHOTO_MAX_FILE_SIZE_BYTES` | no | Maximum accepted multipart profile-photo file size. Defaults to `5242880` bytes. Legacy fallback: `PROFILE_PHOTO_MAX_SIZE_BYTES`. |
| `PROFILE_PHOTO_MAX_INPUT_WIDTH` | no | Maximum decoded input image width. Defaults to `6000`. |
| `PROFILE_PHOTO_MAX_INPUT_HEIGHT` | no | Maximum decoded input image height. Defaults to `6000`. |
| `PROFILE_PHOTO_MAX_INPUT_PIXELS` | no | Maximum decoded input pixel count. Defaults to `20000000`. |
| `PROFILE_PHOTO_MAX_OUTPUT_DIMENSION` | no | Maximum normalized JPEG width or height. Defaults to `2048`. |
| `PROFILE_PHOTO_JPEG_QUALITY` | no | JPEG quality for server-normalized profile photos. Defaults to `0.88`; must be `> 0` and `<= 1`. |
| `PROFILE_PHOTO_UPLOAD_MAX_CONCURRENT` | no | Single-instance maximum concurrent costly photo upload/replacement pipelines. Defaults to `2`. |
| `PROFILE_PHOTO_UPLOAD_PERMIT_WAIT_DURATION` | no | How long an upload waits for a photo pipeline permit. Defaults to `PT0S` for immediate 503. |
| `PROFILE_PHOTO_UPLOAD_RETRY_AFTER_SECONDS` | no | `Retry-After` value returned with `PROFILE_PHOTO_UPLOAD_BUSY`. Defaults to `1`. |
| `CHAT_AUDIO_ENABLED` | no | Enables creation of chat audio messages. Local profiles enable audio. Shared `dev` defaults to `true`. `prod` defaults to `true`. Set `CHAT_AUDIO_ENABLED=false` to disable creation of new audio messages. Disabling creation does not hide existing audio messages. |
| `CHAT_AUDIO_MAX_DURATION_MILLIS` | no | Product maximum audio duration if externalized in a deployment. Current committed default is `60000`; equality is accepted. |
| `CHAT_AUDIO_MAX_FILE_SIZE_BYTES` | no | Product maximum audio file size if externalized in a deployment. Current committed default is `2097152` bytes. The global multipart limit remains larger for profile photos; audio enforces this inside the audio pipeline. |
| `CHAT_AUDIO_UPLOAD_MAX_CONCURRENT` | no | Single-instance maximum concurrent costly audio upload pipelines. Defaults to `2`. |
| `CHAT_AUDIO_UPLOAD_PERMIT_WAIT_DURATION` | no | How long an audio upload waits for a pipeline permit. Defaults to `PT0S` for immediate 503. |
| `CHAT_AUDIO_UPLOAD_RETRY_AFTER_SECONDS` | no | `Retry-After` value returned with `CHAT_AUDIO_UPLOAD_BUSY`. Defaults to `1`. |
| `PROFILE_PHOTO_MULTIPART_MAX_FILE_SIZE` | no | Servlet multipart parser file-size limit for profile-photo uploads. Defaults to `5MB`; keep aligned with the 5 MiB product limit. |
| `PROFILE_PHOTO_MULTIPART_MAX_REQUEST_SIZE` | no | Servlet multipart parser request-size limit. Defaults to `6MB` to leave room for multipart headers and the `position` field. |
| `PROFILE_PHOTO_MODERATION_PROVIDER` | prod: yes; dev Sightengine smoke: yes | Profile photo analysis/moderation provider. Supported values: `none`, `sightengine`. Defaults to `none`. DEV starts with no-op moderation unless explicitly set to `sightengine`. In `dev` and `prod`, selecting `sightengine` enables the real Sightengine provider and requires usable Sightengine configuration. In `prod`, startup requires `sightengine`; it cannot fall back to `none`. |
| `PROFILE_PHOTO_MODERATION_FAIL_UPLOAD_ON_PROVIDER_ERROR` | no | If `true`, provider errors reject photo upload. Defaults to `false`, which persists `NEEDS_REVIEW`. |
| `PROFILE_PHOTO_MODERATION_PERSIST_REJECTED_PHOTOS` | no | If `true`, rejected photos can be persisted with `moderationStatus=REJECTED`. Defaults to `false`, which rejects upload before storage. |
| `PROFILE_PHOTO_SIGHTENGINE_ENDPOINT` | when Sightengine selected | Sightengine check endpoint. Defaults to `https://api.sightengine.com/1.0/check.json`; when selected in `dev` or `prod`, startup requires a valid absolute HTTPS URI. |
| `PROFILE_PHOTO_SIGHTENGINE_CONNECT_TIMEOUT_MS` | no | Sightengine connect timeout in milliseconds. Defaults to `3000`; must be positive. |
| `PROFILE_PHOTO_SIGHTENGINE_READ_TIMEOUT_MS` | no | Sightengine response/read timeout in milliseconds. Defaults to `10000`; must be positive. |
| `PROFILE_PHOTO_SEXUAL_EXPLICIT_REVIEW_THRESHOLD` | no | Reals sexual-explicit review score threshold. Defaults to `0.50`. |
| `PROFILE_PHOTO_SEXUAL_EXPLICIT_REJECT_THRESHOLD` | no | Reals sexual-explicit reject score threshold. Defaults to `0.80`; must be at least the review threshold. |
| `PROFILE_PHOTO_SEXUAL_SUGGESTIVE_REVIEW_THRESHOLD` | no | Reals sexual-suggestive review score threshold. Defaults to `0.80`; suggestive content alone does not auto-reject in this slice. |
| `PROFILE_PHOTO_VIOLENCE_REVIEW_THRESHOLD` | no | Reals violence/threat review score threshold. Defaults to `0.50`. |
| `PROFILE_PHOTO_VIOLENCE_REJECT_THRESHOLD` | no | Reals violence/threat reject score threshold. Defaults to `0.85`; must be at least the review threshold. |
| `PROFILE_PHOTO_GORE_REVIEW_THRESHOLD` | no | Reals gore review score threshold. Defaults to `0.40`. |
| `PROFILE_PHOTO_GORE_REJECT_THRESHOLD` | no | Reals gore reject score threshold. Defaults to `0.80`; must be at least the review threshold. |
| `PROFILE_PHOTO_HATE_REVIEW_THRESHOLD` | no | Reals hate/extremism review score threshold. Defaults to `0.50`. |
| `PROFILE_PHOTO_HATE_REJECT_THRESHOLD` | no | Reals hate/extremism reject score threshold. Defaults to `0.85`; must be at least the review threshold. |
| `PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION` | prod: yes | If `true`, profile activation requires every required photo to be moderation-approved. Defaults to `false` in shared/local configuration and `true` in `prod`; `prod` refuses to start when this is `false`. |
| `PROFILE_MIN_FULL_BODY_PHOTOS` | no | Dev/prod override for `profile.photos.min-full-body-photos`. `dev` defaults to `1`; `prod` defaults to `0` temporarily because Reals does not yet have a real full-body detector. Shared/local/test defaults remain unchanged. |
| `PROFILE_AUTHENTICITY_VERIFICATION_PROVIDER` | no | Profile authenticity verification provider. Defaults to `none`. Outside `prod`, `none` preserves the MVP verified shortcut; in `prod`, authenticity verification is unavailable and returns `409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED`. |
| `PROFILE_AUTHENTICITY_VERIFICATION_FAIL_ON_PROVIDER_ERROR` | no | If `true`, provider errors reject profile authenticity verification. Defaults to `false`, which returns `NEEDS_REVIEW`. |
| `PROFILE_AUTHENTICITY_VERIFICATION_REQUIRE_FOR_ACTIVATION` | no | If `true`, profile activation requires `authenticityVerificationStatus=VERIFIED`. Defaults to `false` for MVP/local compatibility. |
| `PROFILE_AUTHENTICITY_VERIFICATION_MIN_MATCHED_PERSON_PHOTOS` | no | Minimum current candidate person photos that must positively match the accepted live reference for automatic `VERIFIED`. Defaults to `3`; must be positive. This is separate from `profile.photos.min-person-photos`. |
| `PROFILE_AUTHENTICITY_VERIFICATION_MAX_CONTRADICTORY_PERSON_PHOTOS` | no | Maximum current candidate person photos with contradictory facial evidence allowed for automatic `VERIFIED`. Defaults to `0`; must not be negative. Contradictions currently produce `NEEDS_REVIEW`, not automatic `REJECTED`. |
| `RATE_LIMIT_ENABLED` | prod: yes | Enables the in-memory pre-auth and post-auth token-bucket limiters. Defaults to `true`; `prod` refuses to start when this is `false`. |
| `RATE_LIMIT_SAFETY_REPORT_CAPACITY` | no | Token bucket capacity for `POST /api/safety/reports`. Defaults to `5`. |
| `RATE_LIMIT_SAFETY_REPORT_REFILL_TOKENS` | no | Tokens refilled for safety report creation. Defaults to `5`. |
| `RATE_LIMIT_SAFETY_REPORT_REFILL_PERIOD_SECONDS` | no | Safety report refill period in seconds. Defaults to `86400`. |
| `SCHEDULING_ACTIVATION_DELAY_MINUTES` | no | Production/dev override for the delay between mutual visual approval and scheduling becoming actionable. Defaults to `5` in current profiles. |
| `SCHEDULING_SECOND_CHAT_CONFLICT_WINDOW_MINUTES` | no | Symmetric inclusive window, in minutes, around another confirmed second-chat start for the same user. Defaults to `60`; `0` means only the exact confirmed instant conflicts. Negative values are rejected at startup. |
| `CHAT_FIRST_CHAT_DURATION_MINUTES` | no | Dev/prod first-chat absolute duration in minutes. Defaults to `15`. |
| `CHAT_FIRST_CHAT_INACTIVITY_THRESHOLD_MINUTES` | no | Dev/prod first-chat inactivity threshold in minutes. Defaults to `5`. Legacy fallback: `SCHEDULER_INACTIVITY_CHECK_JOB_INACTIVITY_THRESHOLD_MINUTES`. |
| `CHAT_FIRST_CHAT_APPROVAL_MIN_ELAPSED_MINUTES` | no | Minimum elapsed first-chat time before `APPROVED` is accepted. Local profiles default to `0`; dev/default profiles default to `1`; prod defaults to `3`. |
| `CHAT_FIRST_CHAT_APPROVAL_MIN_MESSAGES_PER_USER` | no | Minimum confirmed first-chat messages required from each participant before `APPROVED` is accepted. Local profiles default to `0`; dev/default/prod default to `3`. |
| `MATCHMAKING_ALLOW_ACTIVE_PAIR_DUPLICATES` | no | Local Firebase override for repeated same-pair testing. Defaults to `true` in `local-firebase` Docker runs and `false` globally. Keep `false` for production-like active-pair restrictions. |
| `MATCHMAKING_EXCLUDE_PREVIOUS_PAIRING` | no | Historical previous-pair cooldown exclusion. Defaults to `true` in dev/prod and `false` in local repeatable profiles. It is independent from active-pair duplicate handling and never disables user-block exclusion. |
| `MATCHMAKING_PREVIOUS_PAIRING_COOLDOWN_DAYS` | no | Dev/prod cooldown in days for explicit chat rejection, visual rejection, visual-review expiration and closed connections. Defaults to `30`; must be non-negative. |
| `MATCHMAKING_FIRST_CHAT_EXPIRATION_COOLDOWN_DAYS` | no | Dev/prod cooldown in days for first-chat absolute timeout or inactivity abandonment. Defaults to `7`; must be non-negative. |
| `MATCHMAKING_FIRST_CHAT_DECISION_MISMATCH_COOLDOWN_DAYS` | no | Dev/prod cooldown in days for first-chat `FIRST_CHAT_DECISION_MISMATCH`. Defaults to `7`; must be non-negative. |
| `MATCHMAKING_RANKING_MODE` | no | Matchmaking partner ranking mode. Defaults to `LEGACY_EARLY_ACCEPT` globally/dev/prod and `PROBABILISTIC_WEIGHTED` in `local-firebase`. |
| `MATCHMAKING_RANKING_COMPATIBILITY_TEMPERATURE` | no | Probabilistic compatibility temperature. Defaults to `0.20`; must be finite and greater than `0`. |
| `MATCHMAKING_RANKING_RELIABILITY_SIMILARITY_SCALE` | no | Probabilistic reliability-gap scale. Defaults to `10.0`; must be finite and greater than `0`. |
| `MATCHMAKING_RANKING_WAITING_RELAXATION_PERIOD_HOURS` | no | Hours for each `+1` waiting relaxation multiplier before the cap. Defaults to `72.0`; must be finite and greater than `0`. |
| `MATCHMAKING_RANKING_MAXIMUM_SIMILARITY_SCALE_MULTIPLIER` | no | Maximum waiting relaxation multiplier. Defaults to `3.0`; must be finite and at least `1`. |
| `MATCHMAKING_RANKING_AFFINITY_MODE` | no | Private affinity ranking mode: `OFF`, `SHADOW` or `ACTIVE`. Defaults to `OFF` globally/dev/prod and `SHADOW` in `local-firebase`. `ACTIVE` requires `PROBABILISTIC_WEIGHTED`. |
| `MATCHMAKING_RANKING_AFFINITY_MAX_RELATIVE_ADJUSTMENT` | no | Maximum multiplicative affinity adjustment. Defaults to `0.10`; must be finite and in `[0.0, 0.25]`. |
| `MATCHMAKING_RANKING_AFFINITY_FULL_CONFIDENCE_SHARED_QUESTIONS` | no | Shared ranking-enabled question count for full global affinity confidence. Defaults to `12`; must be positive. |
| `MATCHMAKING_RANKING_AFFINITY_FULL_CONFIDENCE_CATEGORIES` | no | Ranking-evidence category count for full global affinity confidence. Defaults to `4`; must be positive. |
| `MATCHMAKING_RANKING_AFFINITY_CATEGORY_FULL_CONFIDENCE_QUESTIONS` | no | Per-category ranking-enabled question count for full category confidence. Defaults to `3`; must be positive. |
| `ENGAGEMENT_MAX_ACTIVE_MATCHES` | no | Local Firebase override for neutral per-user active match admission capacity. Defaults to `100` in `local-firebase` Docker runs and `5` elsewhere. |
| `ENGAGEMENT_MAX_ACTIVE_CONNECTIONS` | no | Local Firebase override for neutral per-user active connection admission capacity. Defaults to `100` in `local-firebase` Docker runs and `4` elsewhere. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_MATCH_MIN` | no | Minimum reliability-derived Match admission cap. Default `3`. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_MATCH_MAX` | no | Maximum reliability-derived Match admission cap. Default `9`. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_MATCH_REWARD_SCALE` | no | Positive reliability scale for Match capacity. Default `20`; higher values make extra capacity harder to earn. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_MATCH_PENALTY_SCALE` | no | Negative reliability scale for Match capacity. Default `10`; lower values make penalties affect capacity faster. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_CONNECTION_MIN` | no | Minimum reliability-derived Connection admission cap. Default `2`. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_CONNECTION_MAX` | no | Maximum reliability-derived Connection admission cap. Default `6`. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_CONNECTION_REWARD_SCALE` | no | Positive reliability scale for Connection capacity. Default `30`, intentionally more conservative than Match. |
| `ENGAGEMENT_RELIABILITY_CAPACITY_CONNECTION_PENALTY_SCALE` | no | Negative reliability scale for Connection capacity. Default `10`. |
| `USER_RELIABILITY_ENABLED` | no | Enables the internal user reliability event system and bounded matchmaking modifier. Defaults to `true` in `dev` and `local-firebase`, `false` elsewhere. |
| `USER_RELIABILITY_BASE_SCORE` | no | Base reliability score used when recomputing from active events. Defaults to `100`. |
| `USER_RELIABILITY_FULL_WEIGHT_DAYS` | no | Number of days reliability events count at full weight. Defaults to `10`. |
| `USER_RELIABILITY_HALF_WEIGHT_DAYS` | no | Number of days reliability events count at half weight after the full-weight window. Defaults to `10`. |
| `USER_RELIABILITY_EXPIRATION_DAYS` | no | Event retention/scoring window before cleanup deletes reliability events. Defaults to `20`. |
| `FIRST_CHAT_MIN_PARTICIPATION_MESSAGES_PER_USER` | no | Reliability-only first-chat minimum participation message threshold. Defaults to `2`. |
| `FIRST_CHAT_MIN_PARTICIPATION_MINUTES` | no | Reliability-only first-chat minimum participation elapsed-time threshold. Defaults to `5`. |
| `CHAT_SECOND_CHAT_ON_TIME_WINDOW_MINUTES` | no | On-time join window after confirmed second-chat start. Defaults to `10`. |
| `CHAT_SECOND_CHAT_ENTRY_WINDOW_MINUTES` | no | Total second-chat entry window before hard no-show cutoff. Defaults to `20`. |
| `CHAT_SECOND_CHAT_NO_SHOW_CLAIM_COUNTDOWN_SECONDS` | no | Manual partner no-show claim countdown, capped at hard cutoff. Defaults to `60`. |
| `CHAT_SECOND_CHAT_MUTUAL_COMPLETION_MINIMUM_CONVERSATION_MINUTES` | no | Minimum elapsed second-chat conversation time before mutual completion can be requested. Defaults to `10`. |
| `CHAT_SECOND_CHAT_MUTUAL_COMPLETION_REQUEST_COUNTDOWN_SECONDS` | no | Mutual-completion responder countdown, capped by absolute timeout. Defaults to `60`. |
| `CHAT_SECOND_CHAT_MUTUAL_COMPLETION_REQUESTER_COOLDOWN_SECONDS` | no | Per-requester cooldown after rejected, timed-out or message-cancelled mutual completion. Defaults to `60`. |
| `CHAT_SECOND_CHAT_INACTIVITY_CLAIMABLE_AFTER_MINUTES` | no | Delay after the latest conversational message before its author may claim partner inactivity. Defaults to `5`. |
| `CHAT_SECOND_CHAT_INACTIVITY_AUTOMATIC_CLOSE_AFTER_MINUTES` | no | Delay after the latest conversational message before automatic partner-inactivity closure. Defaults to `10`. |
| `CHAT_SECOND_CHAT_INACTIVITY_CLAIM_COUNTDOWN_SECONDS` | no | Manual partner-inactivity countdown, capped by automatic inactivity and absolute timeout. Defaults to `60`. |
| `CHAT_SECOND_CHAT_INITIAL_SILENCE_AUTOMATIC_CLOSE_AFTER_MINUTES` | no | Delay after both participants joined before closing an empty second chat for initial silence. Defaults to `10`. |
| `USER_RELIABILITY_MATCHMAKING_MAX_MODIFIER` | no | Maximum absolute reliability modifier applied after compatibility scoring in `LEGACY_EARLY_ACCEPT` mode when reliability is enabled. Defaults to `0.05`. |
| `SCHEDULER_MATCHMAKING_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for queued-user matchmaking. Defaults to `60000`. |
| `SCHEDULER_MATCHMAKING_JOB_MAX_PAIRS_PER_RUN` | no | Dev/prod upper bound for pairs processed per matchmaking run. Defaults to `10`. |
| `SCHEDULER_CHAT_TIMEOUT_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for first-chat absolute timeout expiration. Defaults to `60000`. |
| `SCHEDULER_SECOND_CHAT_LIFECYCLE_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for no-show, conversation requests, inactivity, absolute timeout and read-only cleanup. Defaults to `120000`. |
| `SCHEDULER_SECOND_CHAT_REMINDER_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for `SecondChatReminderNotificationJob`. Defaults to `60000`, which gives a 1-minute reminder pickup window. |
| `SCHEDULER_SECOND_CHAT_START_NOTIFICATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for `SecondChatStartNotificationJob`. Defaults to `240000`, which leaves scheduler slack inside the five-minute start-notification delivery window. Must remain lower than `NOTIFICATIONS_SECOND_CHAT_START_LATEST_SEND_AFTER_START_MINUTES * 60000`. |
| `SCHEDULER_SECOND_CHAT_START_NOTIFICATION_JOB_BATCH_SIZE` | no | Dev/prod bounded batch size for `SecondChatStartNotificationJob`. Defaults to `100`. |
| `SCHEDULER_VISUAL_REVIEW_REMINDER_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for `VisualReviewReminderNotificationJob`. Defaults to `1800000`, so due visual-review reminders are picked up about every 30 minutes. |
| `SCHEDULER_MATCH_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for stale match expiration fallback. Defaults to `300000`. |
| `SCHEDULER_MATCH_EXPIRATION_JOB_MAX_CHAT_DURATION` | no | Dev/prod ISO-8601 duration for first-chat match expiration fallback. Defaults to `PT20M`. |
| `SCHEDULER_INACTIVITY_CHECK_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for inactivity abandonment checks. Defaults to `60000`. |
| `SCHEDULER_INACTIVITY_CHECK_JOB_INACTIVITY_THRESHOLD_MINUTES` | no | Legacy fallback for first-chat inactivity threshold. Prefer `CHAT_FIRST_CHAT_INACTIVITY_THRESHOLD_MINUTES`. |
| `SCHEDULER_PENALTY_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for normalizing expired temporary account-ban rows. Defaults to `600000`. |
| `SCHEDULER_USER_RELIABILITY_CLEANUP_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for deleting expired reliability events. Defaults to `3600000`. |
| `SCHEDULER_VISUAL_PHASE_EXPIRATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for visual phase expiration. Defaults to `300000`. |
| `SCHEDULER_SCHEDULING_TIMEOUT_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for scheduling negotiation timeout cleanup. Defaults to `900000`. |
| `SCHEDULER_SCHEDULING_ACTIVATION_JOB_FIXED_DELAY` | no | Cadence in milliseconds for enabling deferred scheduling. Defaults to `60000` in base/dev profiles and `21600000` in prod. |
| `SCHEDULER_ACCOUNT_DELETION_FINALIZATION_JOB_FIXED_DELAY` | no | Dev/prod cadence in milliseconds for finalized recoverable account deletion cleanup. Defaults to `3600000`. |
| `NOTIFICATIONS_SECOND_CHAT_REMINDER_MINUTES_BEFORE` | no | Comma-separated positive lead-time list for confirmed second-chat reminders, for example `120,10`. Defaults to `10`; keep multiple values in descending order for readability. |
| `NOTIFICATIONS_SECOND_CHAT_START_LATEST_SEND_AFTER_START_MINUTES` | no | Latest allowed initiation time after confirmed second-chat start. Defaults to `5`; eligibility is `confirmedDateTime <= now <= confirmedDateTime + value`. |
| `NOTIFICATIONS_VISUAL_REVIEW_REMINDER_REMAINING_PERCENTAGE` | no | Remaining visual-review duration percentage used when persisting `VisualReview.reminderEligibleAt` at creation time. Defaults to `40`; must be greater than `0` and less than `100`. |

Matchmaking pair eligibility has four separate controls:

- Active-pair duplicate exclusion is controlled by `matchmaking.allow-active-pair-duplicates`. The global default is `false`, so a pair cannot receive a new match while they have an active `CHAT_ACTIVE`/`VISUAL_PHASE` match, a `VISUAL_APPROVED` match without a connection yet, or any non-`CLOSED` connection. `local-firebase` defaults it to `true` for repeated manual testing.
- Historical previous-pair exclusion is configurable through `matchmaking.exclude-previous-pairing`. It is enabled in `dev` and `prod`, disabled in local repeatable profiles, and is independent from active-pair duplicate handling. General terminal outcomes use `matchmaking.previous-pairing-cooldown-days` (30 by default), first-chat automatic timeout/inactivity uses `matchmaking.first-chat-expiration-cooldown-days` (7 by default), and first-chat `FIRST_CHAT_DECISION_MISMATCH` uses the separate `matchmaking.first-chat-decision-mismatch-cooldown-days` (7 by default).
- Per-user engagement capacity uses `engagement.max-active-matches` and `engagement.max-active-connections` as neutral baselines. With user reliability enabled, `engagement.reliability-capacity.*` derives effective Match and Connection admission caps from the current decayed reliability score. Startup validation requires each neutral baseline to be within its configured reliability-capacity min/max range, including static-cap overrides such as local-firebase's high manual-testing capacities. Allowing active duplicate pairs does not bypass these limits.
- User blocks are permanent pair exclusions in either direction until an explicit unblock feature exists. Normal chat rejection, visual rejection, expiration, scheduling failure and connection closure do not create `UserBlock` rows.

No cleanup job or derived pairing-exclusion table exists. Cooldown eligibility is calculated from persisted match, chat, visual-review and connection history. Exact cooldown boundary is eligible: a terminal timestamp equal to `now - cooldownDays` is no longer excluded.

Useful local modes:

- Fast repeated local testing: `MATCHMAKING_ALLOW_ACTIVE_PAIR_DUPLICATES=true`, `MATCHMAKING_EXCLUDE_PREVIOUS_PAIRING=false`, `ENGAGEMENT_MAX_ACTIVE_MATCHES=100`, `ENGAGEMENT_MAX_ACTIVE_CONNECTIONS=100`. The same users may be matched repeatedly; active pair interactions and recent terminal history do not block, while user blocks and hard profile compatibility still apply.
- Production-like local testing: `MATCHMAKING_ALLOW_ACTIVE_PAIR_DUPLICATES=false`, `MATCHMAKING_EXCLUDE_PREVIOUS_PAIRING=true`, `ENGAGEMENT_MAX_ACTIVE_MATCHES=5`, `ENGAGEMENT_MAX_ACTIVE_CONNECTIONS=2`. Active pair interactions, historical cooldowns and normal capacity limits apply.
- Intermediate local testing: `MATCHMAKING_ALLOW_ACTIVE_PAIR_DUPLICATES=false`, `MATCHMAKING_EXCLUDE_PREVIOUS_PAIRING=false`. Active pair restrictions apply while completed historical pairs can repeat.

Legal document configuration:

```yaml
legal:
  documents:
    - type: TERMS_OF_USE
      version: "2026-08-01"
      url: "https://legal.reals.app/legal/terms/2026-08-01/"
      content-sha256: "<64-lowercase-hex-sha256>"
      required-action: ACCEPTED
```

Supported document types are `TERMS_OF_USE`, `PRIVACY_NOTICE` and
`COMMUNITY_GUIDELINES`. Supported factual actions are `ACCEPTED` and
`ACKNOWLEDGED`. The default catalog is empty. The application fails fast if the
configured list contains duplicate document types, blank versions, unsafe
version path values, blank URLs, blank content hashes, or a `content-sha256`
that is not exactly 64 lowercase hexadecimal characters.

The configured legal document must have a canonical bundled HTML file at:

```text
legal-documents/<type-slug>/<version>/document.html
```

Type slugs are:

```text
TERMS_OF_USE          -> terms
PRIVACY_NOTICE        -> privacy
COMMUNITY_GUIDELINES  -> community-guidelines
```

At startup, the backend reads the exact canonical file bytes from the classpath,
calculates SHA-256, and compares it with the configured `content-sha256`.
Startup fails if the canonical file is missing or if the calculated hash differs
from configuration. The backend does not fetch, hash, or validate the configured
public URL; the URL remains publication metadata.

The SHA-256 is byte-exact. Do not trim, normalize line endings, parse HTML,
serialize HTML, hash rendered text, or hash the URL. Line-ending, whitespace and
indentation changes all change the hash. Operators can calculate hashes with:

```bash
sha256sum legal-documents/terms/2026-08-01/document.html
```

```powershell
(Get-FileHash -Algorithm SHA256 legal-documents/terms/2026-08-01/document.html).Hash.ToLower()
```

The current legal document catalog plus persisted user legal document actions
are the authoritative source for the legal compliance gate. Protected
participation/content writes may be rejected with `LEGAL_ACTION_REQUIRED` when
current configured requirements are not satisfied. An empty configured catalog
is naturally satisfied.

Each configured legal document URL should identify the exact published content
associated with that document type, version, and SHA-256. Published legal
document versions must not be modified retroactively. Substantive content
changes require new content, a new version directory, a new SHA-256, and a new
current catalog configuration. Historical legal document URLs should remain
available so the content associated with previously-recorded actions can still
be identified. Configured production URLs should point to stable, externally
hosted legal document resources appropriate for the platform and compliance
requirements. The publication process must copy the exact canonical
`document.html` bytes without HTML transformation.

A versioned URL such as `https://reals.example/legal/privacy/2026-08-01` is an
example publication pattern, not a backend validation rule or a technical proof
of immutability.

The current backend does not fetch configured legal document URLs, hash remote
legal document content, snapshot remote legal document content, or store legal
HTML in PostgreSQL. Public URL preservation and exact-byte publication remain
operational publication responsibilities.

Sensitive runtime secrets:

| Variable | Required | Notes |
| --- | --- | --- |
| `DATABASE_PASSWORD` | yes | PostgreSQL password. |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | one Firebase credential source | Preferred for lightweight container runtimes. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | one Firebase credential source | Raw service-account JSON when the platform supports multiline secrets safely. |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | one Firebase credential source | Path to a mounted service-account JSON file. |
| `SIGHTENGINE_API_USER` | when `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` in `dev` or `prod` | Sightengine API user. DEV default `none` does not require it. Do not commit real values. |
| `SIGHTENGINE_API_SECRET` | when `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` in `dev` or `prod` | Sightengine API secret. DEV default `none` does not require it. Do not commit or log it. |
| `STORAGE_S3_ACCESS_KEY_ID` | with `STORAGE_S3_CREDENTIALS_MODE=STATIC` | MinIO/R2/S3-compatible access key. Must be nonblank in `STATIC`; must be absent/blank in `DEFAULT_CHAIN`. Legacy fallback: `S3_ACCESS_KEY_ID`. |
| `STORAGE_S3_SECRET_ACCESS_KEY` | with `STORAGE_S3_CREDENTIALS_MODE=STATIC` | MinIO/R2/S3-compatible secret key. Must be nonblank in `STATIC`; must be absent/blank in `DEFAULT_CHAIN`. Legacy fallback: `S3_SECRET_ACCESS_KEY`. |
| `STORAGE_S3_SESSION_TOKEN` | only for explicit temporary credentials in `STATIC` mode | Optional AWS session token used with `STORAGE_S3_ACCESS_KEY_ID` and `STORAGE_S3_SECRET_ACCESS_KEY`. Must not be configured alone or in `DEFAULT_CHAIN`. Legacy fallback: `S3_SESSION_TOKEN`. |
| `PROFILE_AUTHENTICITY_VERIFICATION_API_KEY` | when a future non-`none` provider exists | Reserved for a future profile authenticity provider; keep empty while provider is `none`. |

S3-compatible storage has two optional endpoint concerns. `STORAGE_S3_ENDPOINT`
is where the backend writes and deletes objects when using MinIO, R2 or another
explicit S3-compatible API. `STORAGE_S3_PRESIGNED_URL_ENDPOINT` is the host
embedded in returned presigned URLs. The client that renders media must reach
that host. Presigner endpoint precedence is:

1. `STORAGE_S3_PRESIGNED_URL_ENDPOINT`;
2. `STORAGE_S3_ENDPOINT`;
3. no endpoint override, which lets the AWS SDK use the normal Amazon S3
   regional endpoint.

`STORAGE_S3_BUCKET` names the application media bucket. The same bucket stores
profile photos and chat audio. The backend keeps these object-key namespaces:

- `users/<userId>/profile-photos/<objectId>.<extension>`
- `chats/<chatId>/messages/<messageId>.m4a`

The `profile-photos` term remains correct inside the profile-photo object key.
It is not the bucket name. Profile photo rows and chat message rows store
storage metadata, including the bucket and object key. Response URLs are
generated from stored metadata when an API returns media.

Read operations use the persisted bucket and object key from the media row. New
uploads use the currently configured `STORAGE_S3_BUCKET` value. Changing the
configured bucket does not move existing objects.

`S3_PROFILE_PHOTOS_BUCKET` remains a deprecated compatibility input. New hosted
deployments must use `STORAGE_S3_BUCKET`. Keep the fallback only until all
hosted environments use the canonical variable.

An existing S3 bucket cannot be renamed in place. A physical bucket migration is
an infrastructure operation. It must copy objects separately, update runtime
configuration, validate access, and only then retire the old bucket.

Credential modes:

- `STATIC`: default mode. Requires `STORAGE_S3_ACCESS_KEY_ID` and
  `STORAGE_S3_SECRET_ACCESS_KEY`. If `STORAGE_S3_SESSION_TOKEN` is nonblank, the
  backend uses AWS session credentials; otherwise it uses basic static
  credentials. Use this for MinIO, R2 and explicitly configured S3-compatible
  providers.
- `DEFAULT_CHAIN`: uses the AWS SDK v2 default credential provider chain. Use
  this for preferred AWS deployments on EC2 instance profiles, ECS task roles,
  standard AWS environment credentials or standard shared AWS configuration. Do
  not inject `STORAGE_S3_ACCESS_KEY_ID`, `STORAGE_S3_SECRET_ACCESS_KEY` or
  `STORAGE_S3_SESSION_TOKEN` in this mode.

Native Amazon S3 example for AWS-hosted `dev`/`prod`:

```text
STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN
STORAGE_S3_REGION=<real-aws-region>
STORAGE_S3_BUCKET=<private-bucket-name>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=false
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

Leave these absent or blank in the native Amazon S3 role-based configuration:

```text
STORAGE_S3_ENDPOINT
STORAGE_S3_PRESIGNED_URL_ENDPOINT
STORAGE_S3_ACCESS_KEY_ID
STORAGE_S3_SECRET_ACCESS_KEY
STORAGE_S3_SESSION_TOKEN
```

MinIO example:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=http://minio:9000
STORAGE_S3_PRESIGNED_URL_ENDPOINT=http://localhost:9000
STORAGE_S3_REGION=us-east-1
STORAGE_S3_BUCKET=reals-media
STORAGE_S3_ACCESS_KEY_ID=<minio-access-key>
STORAGE_S3_SECRET_ACCESS_KEY=<minio-secret-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

R2 example:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_PRESIGNED_URL_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_REGION=auto
STORAGE_S3_BUCKET=<r2-bucket-name>
STORAGE_S3_ACCESS_KEY_ID=<r2-access-key-id>
STORAGE_S3_SECRET_ACCESS_KEY=<r2-secret-access-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

Startup validation rejects blank bucket/region, incomplete static credentials,
session tokens without key and secret, static credential fields in
`DEFAULT_CHAIN`, `auto` region without an endpoint override, public read mode in
`prod`, and non-positive presigned URL durations in `prod`. Error messages
identify property names and do not include credential values.

For Cloudflare R2, hosted MinIO and other S3-compatible shared/dev/prod-like
environments, see `docs/storage-r2-configuration.md`. Buckets should stay
private and use `STORAGE_S3_READ_URL_MODE=PRESIGNED` for MVP.

For AWS deployments, prefer `STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN` and IAM
roles for S3 access instead of long-lived access keys. EC2 instance profiles and
ECS task roles can be consumed through the AWS SDK default chain; no access key
should be injected for the preferred AWS deployment.

## Helm Chart Location

The Helm chart does not have to live in this repository. A common setup is:

- Application repository: source code, Dockerfile/build pipeline metadata and per-environment `values-*.yaml`.
- Infrastructure repository: shared Helm chart, Kubernetes templates, cluster-specific configuration and deployment automation.

In that setup, CI builds and publishes the image from this repository, then the deployment pipeline applies the chart from the infrastructure repository using this app's values file.

## Environment-Specific Files

`application-dev.yml` and `application-prod.yml` intentionally repeat operational settings such as scheduler cadence, chat durations, scheduling limits and profile photo limits. This makes deploy-time behavior explicit without relying on implicit inheritance from `application.yml`.

These files should use placeholders only for environment-specific or secret-backed values, such as database credentials, S3 bucket/region, Firebase service account location and future profile-authenticity credentials.

Do not commit real credentials in any `application-*.yml` file.

## Scheduler Cadences

Dev and prod use the same MVP scheduler defaults:

| Job | Default | Operational purpose |
| --- | --- | --- |
| `MatchmakingJob` | `60000` ms, `10` pairs/run | Keep queued users moving into first chats. |
| `SchedulingActivationJob` | `60000` ms | Make scheduling actionable shortly after the deferred availability time. |
| `SecondChatReminderNotificationJob` | `60000` ms | Pick up second-chat reminders within a 1-minute window. |
| `VisualReviewReminderNotificationJob` | `1800000` ms | Pick up persisted visual-review reminder eligibility about every 30 minutes. |
| `ChatTimeoutJob` | `60000` ms | Expire timed-out first chats without leaving users stuck. |
| `SecondChatLifecycleJob` | `120000` ms | Process expired no-show claims, resolve hard-cutoff no-shows, expire inactive scheduled windows, expire active second chats and close read-only chats. |
| `InactivityCheckJob` | `60000` ms, `5` minute threshold | Abandon inactive first chats before the absolute timeout when no messages are sent. |
| `MatchExpirationJob` | `300000` ms, `PT20M` first-chat fallback | Safety net for stale matches that did not progress after first-chat timeout handling. |
| `VisualPhaseExpirationJob` | `300000` ms | Expire visual reviews whose deadline passed. |
| `PenaltyExpirationJob` | `600000` ms | Normalize expired temporary account-ban rows to inactive; access expiry is authoritative at `now >= expiresAt` even before this job runs. |
| `UserReliabilityEventCleanupJob` | `3600000` ms | Delete expired internal reliability events after their scoring window. |
| `SchedulingNegotiationTimeoutJob` | `900000` ms | Close scheduling negotiations after their deadline. |
| `AccountDeletionFinalizationJob` | `3600000` ms | Finalize deleted accounts after the recovery window. |
| `MediaCleanupJob` | `300000` ms | Retry durable profile-photo object cleanup tasks. |

These defaults are intentionally more frequent for user-visible bottlenecks and
less frequent for hour/day-scale cleanup. Local profiles keep
`scheduler.enabled=false`; use Bruno or local-dev endpoints to trigger the same
jobs deterministically.

Scheduling has two separate jobs. `SchedulingActivationJob` moves deferred
connections from `SCHEDULING_PENDING` to `SCHEDULING_PHASE` once
`schedulingAvailableAt` is due and initializes the negotiation. The base and dev
cadence is one minute; production defaults to a six-hour fixed delay, yielding
up to four activation batches per day unless overridden. Only after activation
does `SchedulingNegotiationTimeoutJob` close expired scheduling negotiations.
`schedulingExpiresAt` on a pending connection is provisional because activation
recalculates the full actionable deadline from the actual activation time.

## Profile Authenticity Verification

`profile.authenticity-verification.provider` currently supports only `none`.
The provider abstraction exists so a real profile-authenticity integration can
be added later without changing profile creation flow. Profile authenticity
verification is invoked explicitly through
`POST /api/me/profile/authenticity-verification`; profile creation does not call
the provider. This endpoint remains the current synchronous provider abstraction
entry point, not the final liveness/session API.

Profile Authenticity Verification is not legal identity verification. It does
not prove legal name, DNI, passport identity, KYC identity or age. Age assurance
and legal/document verification are separate future concerns.

With `provider=none` outside `prod`, profiles are marked
`authenticityVerificationStatus=VERIFIED` and `authenticityVerified=true` for
MVP/local compatibility only. This does not represent liveness, face comparison,
legal identity, document verification, age assurance or fraud verification.

With `provider=none` in `prod`, the endpoint fails with
`409 AUTHENTICITY_VERIFICATION_NOT_CONFIGURED` and no `VERIFIED` state is
persisted. Profile authenticity verification remains optional for activation
unless `profile.authenticity-verification.require-for-activation=true`. When
enabled, activation requires `authenticityVerificationStatus=VERIFIED`; `STALE`
fails activation with `PROFILE_AUTHENTICITY_VERIFICATION_REQUIRED`.

The future target is a liveness-derived live reference plus provider-neutral
facial comparison signals for current candidate person photos. Candidate photos
are exactly `validationStatus=VALIDATED` and `isPersonPhoto=true`, sorted by
current profile-photo position. `isPersonPhoto` selects comparison candidates;
it does not prove that the detected person is the verified user. Non-person
photos are excluded from face comparison.

Reals policy owns the final domain decision. Successful providers return
neutral outcomes: `MATCHED` is positive evidence, `UNRESOLVED` is neutral, and
`CONTRADICTORY` is comparable facial evidence inconsistent with the accepted
live reference. The default MVP policy requires `liveReferenceAccepted=true`, at
least 3 matched candidate person photos and at most 0 contradictory candidate
person photos. Group photos can be `MATCHED` when at least one comparable face
matches the live reference. Old, distant, side-profile, obscured or otherwise
poor comparisons may be `UNRESOLVED` and do not automatically invalidate the
profile. Strong contradictory evidence prevents automatic verification under
the default zero-contradiction policy, but it does not prove fraud and currently
produces `NEEDS_REVIEW`, not automatic `REJECTED`.

The policy properties are independent from `profile.photos.min-person-photos`:
`profile.photos.min-person-photos` counts photos classified as person photos,
while `profile.authenticity-verification.policy.min-matched-person-photos`
counts current candidate person photos that positively match the accepted live
reference. Both default to `3` today but must remain independently configurable.

Uploading, replacing or deleting a profile photo invalidates previous
authenticity verification to `STALE` and sets `authenticityVerified=false`.
Reordering photos does not invalidate authenticity because the photo content and
set are unchanged.

Profile photo validation and moderation are also profile-aware. Outside `prod`,
the MVP compatibility shortcuts preserve `true`/`true`/`VALIDATED` semantic
photo validation and `APPROVED` moderation when moderation provider is `none`.
In `prod`, successful technical image validation alone returns
`isPersonPhoto=false`, `isFullBody=false` and `validationStatus=PENDING`; `none`
moderation returns `NEEDS_REVIEW`. Successful file decoding and dimension checks
are not semantic person/full-body validation. `application-prod.yml` defaults
`profile.photos.require-moderation-approval-for-activation` to `true`, while
preserving the `PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION`
override.

Profile photo upload and replacement require the authenticated Firebase email to
be verified before the backend reads or processes the uploaded file. The server
accepts only actual JPEG and PNG bytes whose declared multipart content type
matches the detected format; WebP, GIF, BMP, TIFF, SVG, HEIC, AVIF, PDF,
malformed images and content-type mismatches are rejected with
`INVALID_PROFILE_PHOTO`. Defaults are 5 MiB compressed size, 6000 input width,
6000 input height and 20,000,000 input pixels.

Every accepted image is normalized before analysis or storage: EXIF orientation
is applied when present, the image is resized to fit within 2048 pixels on its
largest side, transparent PNG pixels are rendered over a fixed neutral
background, and the output is re-encoded as `image/jpeg` at quality `0.88`.
Source EXIF, GPS, XMP, IPTC, thumbnails and other client-supplied metadata are
not preserved. Moderation/semantic analysis and object storage receive the same
normalized JPEG byte array and content type.

Costly upload and replacement pipelines share a single-instance semaphore. By
default at most two pipelines run concurrently, there is no permit wait, and a
third concurrent request returns `503 PROFILE_PHOTO_UPLOAD_BUSY` with
`Retry-After: 1` before analysis or storage. Servlet multipart parsing happens
before controller-level semaphore acquisition, so the semaphore protects byte
materialization from `MultipartFile`, decoding, moderation, storage,
persistence and compensation, but not initial HTTP-body reception by the
servlet container. Hosted deployments should configure an equivalent
request-body limit at the reverse proxy, gateway or WAF. This is intentionally
process-local and not a Redis/distributed limiter. The authenticated post-auth
rate-limit bucket for photo upload and replacement is `profile-photo-uploads`,
keyed by backend user id, with a default quota of 12 requests per 60 seconds.
Delete, list and reorder do not consume that bucket; the broad pre-auth IP
limiter is unchanged. Current rate-limit buckets are in-memory and
single-instance.

Set `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` in `dev` only for explicit
real-provider smoke testing and in `prod` for production. DEV defaults to
`none`, requires no Sightengine credentials and makes no Sightengine calls.
When `dev` or `prod` selects `sightengine`, the backend sends one synchronous
multipart request to
`PROFILE_PHOTO_SIGHTENGINE_ENDPOINT` per technically valid upload or
replacement. The request uses the server-normalized JPEG bytes as the `media`
part before object storage and includes the fixed MVP model list:
`face-analysis,nudity-2.1,violence,gore-2.0,offensive-2.0`. Operators must
verify that the configured Sightengine account can use this model set before
production deployment; account plan/model restrictions are treated as provider
failures, not silently downgraded requests.

Sightengine credentials are `SIGHTENGINE_API_USER` and
`SIGHTENGINE_API_SECRET`. They are required when provider `sightengine` is
selected in `dev` or `prod`. Startup also requires a valid absolute HTTPS
endpoint and positive connect/read timeouts. Do not commit credentials, log
them, or expose raw provider responses to clients.

Sightengine `faces` entries are used only for the MVP `isPersonPhoto` signal.
Any real face makes `isPersonPhoto=true`; zero real faces makes it false.
`artificial_faces` do not count. This is not facial recognition, face matching,
liveness, profile authenticity verification, legal identity verification, age
estimation or minor detection. Group-photo and other-person false positives are
accepted MVP limitations. Sightengine does not establish `isFullBody`;
successful Sightengine analyses always persist `isFullBody=false`. Because
`isPersonPhoto` currently means at least one real face was detected, this
skeleton does not yet prove person consistency for a body-only image without a
comparable visible face.

Reals maps Sightengine model output to provider-neutral moderation signals:
sexual explicit, sexual suggestive, violence/threat, gore and hate/extremism.
The configured thresholds are Reals product defaults, not Sightengine
recommendations. Reject thresholds take precedence over review thresholds;
otherwise configured review thresholds produce `NEEDS_REVIEW`; otherwise the
photo is `APPROVED`. The existing admin profile-photo review queue resolves
`NEEDS_REVIEW`. Automatic provider moderation does not create child-safety
reports, safety reports, blocks, penalties, bans or account lifecycle changes,
and raw provider scores/request IDs are not persisted yet.

Dev defaults `profile.photos.min-full-body-photos` to `1`, but shared
dev/staging-like deployments can set `PROFILE_MIN_FULL_BODY_PHOTOS=0` when
using Sightengine. Production temporarily defaults the same property to `0`
through `${PROFILE_MIN_FULL_BODY_PHOTOS:0}`. The `isFullBody` domain/API field
and configurable requirement remain in place; the production default is lowered
because there is not yet a real full-body semantic detector and Sightengine
analysis always leaves `isFullBody=false`. A future provider can restore the
production minimum to `1`.

Future real-provider work still needs decisions or implementation for liveness
capture/session lifecycle, live reference artifact handling, facial comparison
provider, score thresholds, retry policy, `NEEDS_REVIEW` workflow, provider
metadata, biometric/privacy/retention policy, reference-image retention or
immediate deletion, asynchronous callback/webhook handling if required, and
separate age assurance.

`PROFILE_AUTHENTICITY_VERIFICATION_API_KEY` is reserved for a future provider
and should stay empty until that provider is implemented.

## Production Startup Guardrails

When the `prod` execution profile is active, local startup validation rejects
configuration that is provably incompatible with the current production Reals
flow. These checks do not call external providers.

| Requirement | Startup behavior in `prod` |
| --- | --- |
| Photo analysis provider | `profile.photos.moderation.provider` must be `sightengine`. |
| Sightengine structure | `profile.photos.sightengine.endpoint` must be an absolute HTTPS URI; credentials must be nonblank; connect/read timeouts must be positive. |
| Activation moderation | `profile.photos.require-moderation-approval-for-activation` must be `true`. |
| App Check | Existing App Check validation requires `ENFORCED`, a numeric project number, at least one allowed Firebase App ID and an absolute HTTPS JWKS URI with a host. Non-production Firebase profiles validate the same provider fields when mode is `MONITOR` or `ENFORCED`. |
| Rate limiting | `security.rate-limit.enabled` must be `true`; configured token-bucket capacities and refill periods must be positive. |
| Media storage | S3-compatible storage must have a nonblank bucket and region, valid credential-mode structure, compatible `auto` region usage, `PRESIGNED` read URLs and a positive presigned URL duration. |

After successful `dev` or `prod` startup, the backend logs one safe runtime
summary: execution profile, photo moderation provider, moderation-approval
activation requirement, App Check mode, rate-limit enabled flag, push provider,
storage provider, storage credential mode, storage read URL mode, matchmaking
ranking mode and affinity ranking mode. Secrets, Firebase tokens, database
credentials, S3 secret keys, admin allowlists, registration tokens, full URLs
and object keys are not logged.

## Matchmaking Tuning

`matchmaking.candidate-pair-limit` controls the bounded partner-candidate window scored after one eligible anchor queue row has been claimed. Hard SQL filters, including exact mutual distance, run before this limit. Local and test profiles keep the window low for deterministic, cheap checks. Dev/prod use higher starting values and should be adjusted using queue size, job duration, partner-claim contention and match creation metrics.

`matchmaking.min-compatibility-score` has mode-specific semantics. In `LEGACY_EARLY_ACCEPT`, it applies to the combined legacy score: raw compatibility plus the bounded legacy reliability modifier. This preserves the pre-refactor behavior. In `PROBABILISTIC_WEIGHTED`, it applies only to raw compatibility before reliability similarity and Gumbel randomness are applied. `matchmaking.early-accept-compatibility-score` is used only by `LEGACY_EARLY_ACCEPT`; probabilistic mode ignores it and ranks every candidate that passes the raw compatibility minimum in a weighted permutation without replacement. See `docs/matchmaking-ranking.md` for formulas and calibration notes.

`matchmaking.ranking.affinity` is private affinity evidence for probabilistic ranking. `OFF` performs no answer loading or evaluation. `SHADOW` batch-loads answers for the bounded candidate window and records aggregate low-cardinality observations without changing order. `ACTIVE` adds the bounded affinity log-weight to `PROBABILISTIC_WEIGHTED` only and is rejected with `LEGACY_EARLY_ACCEPT`. The default rollback is `MATCHMAKING_RANKING_AFFINITY_MODE=OFF`.

`matchmaking.visual-advancement.max-per-window` and
`matchmaking.visual-advancement.window-hours` control the rolling cap on new
Visual Review advancements. Defaults are
`MATCHMAKING_VISUAL_ADVANCEMENT_MAX_PER_WINDOW=10` and
`MATCHMAKING_VISUAL_ADVANCEMENT_WINDOW_HOURS=24` in base, dev, prod and test
configuration. The cap counts persisted `VisualReview.createdAt` rows joined to
Match participants while `createdAt > now - window`; equality is available and
the Home retry timestamp is `oldestActiveCreatedAt + window`.

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

Post-deploy automated smoke checks should verify readiness and ping:

```http
GET /actuator/health/readiness
GET /api/ping
```

`/actuator/info` includes Docker image metadata when the application is started
from a CI-built image, but the endpoint is administrator-only in hosted
environments. Inspect image metadata manually with a fresh administrator bearer
token when needed:

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
the dev environment, pass the deployed backend base URL. The workflow validates
only public operational endpoints.

## Diagnostic Metrics

The backend keeps metrics restrictive by default:

```yaml
management:
  metrics:
    enable:
      all: false
      reals: true
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

Public unauthenticated Actuator access is limited to `/actuator/health` and
`/actuator/health/**`. `/actuator/info`, `/actuator/metrics` and
`/actuator/metrics/**` require the existing Firebase-backed `ROLE_ADMIN`.

Current custom application meters:

- `reals.home.load`: timer for full service execution of Home reads. Tags:
  `variant=full|pending`, `outcome=success|error`.
- `reals.chat.messages.read`: timer for authorized chat-message reads. Tags:
  `mode=initial|incremental`, `outcome=success|error`.
- `reals.chat.messages.returned`: distribution summary for successful returned
  message counts. Tags: `mode=initial|incremental`.
- `reals.scheduler.job.runs`: counter for scheduler run outcomes. Tags:
  `job=<bounded job class name>`, `outcome=success|partial_failure`.
- `reals.scheduler.job.duration`: timer for scheduler run duration. Tags:
  `job=<bounded job class name>`, `outcome=success|partial_failure`.
- `reals.scheduler.job.items`: distribution summary for scheduler work counts.
  Tags: `job=<bounded job class name>`,
  `result=processed|succeeded|skipped|failed`.
- `reals.scheduler.job.backlog_remaining`: gauge set to `1` when a bounded
  scheduler run fetched more eligible work than it processed and `0` otherwise.
  Tags: `job=<bounded job class name>`.
- `reals.matchmaking.run.limit_exhausted`: gauge set by `MatchmakingJob` to `1`
  when the latest instrumented `MatchmakingProcessorService` invocation consumed
  all configured `scheduler.matchmaking-job.max-pairs-per-run` attempts and `0`
  when it returned before exhausting that allowance. This is a bounded-run
  saturation signal, not an exact queue backlog count.
- `reals.matchmaking.affinity.evaluations`: counter for affinity-ranking
  candidate evaluations when affinity ranking is not `OFF`. Tags:
  `mode=shadow|active`, `evidence=present|none`,
  `direction=positive|negative|neutral`.
- `reals.matchmaking.affinity.shared_questions`,
  `reals.matchmaking.affinity.evidence_confidence`,
  `reals.matchmaking.affinity.factor` and
  `reals.matchmaking.affinity.absolute_rank_delta`: distribution summaries for
  bounded affinity-ranking diagnostics. Tags: `mode=shadow|active`,
  `evidence=present|none`, `direction=positive|negative|neutral`.
- `reals.engagement.capacity.evaluations`: counter for engagement-capacity
  admission decisions. Tags:
  `phase=availability|final_match_admission|queue_reconciliation`,
  `direction=below_base|above_base|neutral`,
  `outcome=allowed|blocked_match_cap|blocked_connection_cap`.
- `reals.engagement.capacity.effective_match_cap`,
  `reals.engagement.capacity.effective_connection_cap`: distribution summaries
  for effective caps. Tags:
  `phase=availability|final_match_admission|queue_reconciliation`,
  `direction=below_base|above_base|neutral`.
- `reals.engagement.capacity.absolute_score_distance`: distribution summary for
  effective-score distance from the reliability base score. Tags:
  `direction=below_base|above_base|neutral`.
- `reals.push.provider.commands`: counter for provider command outcomes. Tags:
  `type=<push notification type>`, `outcome=sent|not_sent|provider_exception`.
- `reals.push.deliveries`: counter for persisted push delivery outcomes. Tags:
  `type=<push notification type>`,
  `status=sent|failed|skipped_no_active_token|skipped_already_joined|skipped_user_preference`,
  `persistence=saved|duplicate`.
- `reals.push.persistence.failures`: counter for best-effort persistence
  failures after provider calls. Tags: `type=<push notification type>`,
  `phase=send_result|provider_failure`.
- `reals.push.invalid_tokens_disabled`: counter for invalid FCM tokens disabled
  after provider results. Tags: `type=<push notification type>`.
- `reals.media_cleanup.failed_tasks`: gauge for the durable
  `media_cleanup_tasks` rows currently in `FAILED` status, sampled during the
  latest `MediaCleanupJob` execution. This value is stored in memory for
  scraping; `/actuator/metrics` does not query PostgreSQL live.
- `reals.app_check.requests`: counter for App Check decisions when the filter
  runs. Tags: `mode=monitor|enforced`, `outcome=missing|valid|invalid|unavailable`,
  `endpoint_group=api|admin|legal|profile-photo|provision`, and bounded
  `exception` class or `none`.

These meters intentionally avoid user ids, chat ids, match ids, aggregate ids,
cursor ids, raw paths, object keys, tokens, JWT claims, HTTP status and raw
message count as tags. No Prometheus registry, OTLP exporter, distributed
tracing or production Hibernate statistics are configured in this block.
