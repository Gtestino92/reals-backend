# Local Development

## Spring Profile

There is no shared default active profile. Choose exactly one execution profile
when starting the app locally:

```text
local-nodb
local-postgres
local-firebase
```

`local-firebase` uses real Firebase ID tokens and, in the current
Docker-oriented local setup, a PostgreSQL database:

```text
jdbc:postgresql://postgres:5432/reals
```

`local-firebase` also defaults matchmaking to `PROBABILISTIC_WEIGHTED` and
private affinity ranking to `SHADOW` for manual observation. Set
`MATCHMAKING_RANKING_AFFINITY_MODE=OFF` to disable local affinity evaluation.

## Run Locally

The project is set up to run from IntelliJ IDEA. Maven CLI may not be installed on the target machine, so do not assume `mvn` is available unless confirmed.

The app starts on:

```text
http://localhost:8080
```

Sanity check:

```http
GET http://localhost:8080/api/ping
```

Expected response:

```json
{"status":"ok"}
```

## Testing From Another Device Or Network

The local docs and Bruno collection default to `localhost`, which only works
from the same machine that runs the backend. When another device needs to call
the local backend, use an address that is reachable from that device.

Same LAN or same Wi-Fi:

```text
http://<developer-machine-lan-ip>:8080
```

Example:

```text
http://192.168.0.5:8080
```

Then update the client base URL:

- Bruno: set `baseUrl` in the ignored local environment file, for example
  `bruno/reals-backend-happy-path/environments/local.bru`.
- Android physical device: point the app's backend URL at the same LAN IP and
  port.
- Android Emulator on the same host: keep using emulator-specific host routing
  such as `10.0.2.2` where documented below.

Windows/macOS/Linux firewall rules must allow inbound traffic to the backend
port. With Docker Compose, the backend is already published as `8080:8080`, so
the host port is `8080`.

Media URLs need the same treatment. If the client renders media from local
MinIO, `STORAGE_S3_PRESIGNED_URL_ENDPOINT` must be reachable by that client:

```text
STORAGE_S3_PRESIGNED_URL_ENDPOINT=http://<developer-machine-lan-ip>:9000
```

If this stays as `http://localhost:9000`, another phone or computer will try to
load MinIO from itself instead of from the backend developer machine. With
Docker Compose, MinIO is already published as `9000:9000`.

Different network or different router:

- Prefer a VPN or a tunnel such as Cloudflare Tunnel/ngrok for short manual
  testing.
- Port forwarding can work, but it exposes local services to the internet and
  should be temporary and tightly controlled.
- Do not expose `local-nodb`, `local-postgres`, or other local-dev tooling to
  the public internet. Those profiles expose test helpers such as
  `/api/local-dev/**`.
- If the backend needs to be reachable for repeated Android testing outside the
  developer LAN, prefer a real shared `dev` deployment instead of a local
  machine behind a router.

Check reachability from the client network before debugging application logic:

```http
GET http://<reachable-host>:8080/api/ping
GET http://<reachable-host>:8080/actuator/health/readiness
```

## H2 Console

The H2 console is only exposed by the `local-nodb` execution profile.

URL:

```text
http://localhost:8080/h2-console
```

The H2 console is enabled through `spring.h2.console.*` in `local-nodb`. Spring
Security explicitly denies `/h2-console/**` in every other execution profile.
Use `local-nodb` when you specifically want the H2 file database path.

Connection:

```text
JDBC URL: jdbc:h2:file:./data/realsdb
Username: sa
Password: empty
```

The local H2 datasource URL includes PostgreSQL compatibility mode:

```text
jdbc:h2:file:./data/realsdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
```

## Local Firebase Auth

The default `local-firebase` profile verifies real Firebase ID tokens locally.
It is configured for Docker-based local testing with PostgreSQL, disables dev
auto-auth and enables Firebase token verification.

The local Firebase service-account JSON is expected at:

```text
./secrets/reals-backend-firebase-credentials-dev.json
```

The `secrets/` directory is ignored by Git and must never be committed.

When running the app from the host instead of inside Docker, override the
datasource host because `postgres` is the Docker Compose service name:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reals
```

If the host-run app uses Docker Compose MinIO, also use a host-reachable S3
endpoint:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=http://localhost:9000
STORAGE_S3_PRESIGNED_URL_ENDPOINT=http://localhost:9000
STORAGE_S3_BUCKET=reals-media
```

### Local Firebase Email Verification Helper

`local-firebase` includes an authenticated helper for local Android/Firebase
testing with fictitious email addresses:

```http
POST /api/me/local-dev/email-verification
Authorization: Bearer <Firebase ID token>
```

It returns `204 No Content` and has no request body. The endpoint is not under
`/api/local-dev/**` because that namespace is intentionally unauthenticated in
local profiles. It stays inside `/api/me/**`, so the normal Firebase token
filter, `ROLE_USER` authorization and pre/post-auth rate limits apply.

Exposure is double-gated:

- Spring profile: `local-firebase`.
- Property: `local-dev.firebase.email-auto-verification-enabled=true`.
- Environment variable: `LOCAL_DEV_FIREBASE_EMAIL_AUTO_VERIFICATION_ENABLED`
  defaults to `true` only in `application-local-firebase.yml`.

The endpoint is absent from hosted `dev`, `prod`, `local-nodb`, `local-postgres`
and ordinary `test` runs. Shared remote development continues requiring real
Firebase email verification.

Call it only after `POST /api/me/provision` has created or loaded the backend
user. The helper derives the Firebase UID from the authenticated principal and
updates that same Firebase Auth user through Firebase Admin with
`emailVerified=true`. It does not change the Firebase email, PostgreSQL user,
profile status, profile-photo rows, custom claims or any backend profile state.
It does not activate the profile and does not bypass the verified-email checks
on profile-photo upload, profile-photo replacement or profile activation.

After a `204`, the client must reload the current Firebase user and force a new
ID token before calling the normal upload and activation endpoints:

```text
Firebase sign-in or sign-up
→ POST /api/me/provision
→ POST /api/me/local-dev/email-verification
→ Firebase user reload
→ forced Firebase ID-token refresh
→ normal profile-photo upload/replacement
→ normal profile activation
```

In Bruno, sign in again with the existing Firebase sign-in request after the
helper returns `204`; the backend response intentionally does not return or
mutate an ID token. Manual PostgreSQL profile activation is no longer the
recommended local workflow for Firebase-backed Android testing.

## Local Auto-Auth

With `local-nodb`, no authorization header is needed. `DevAutoAuthFilter` injects:

```text
userId: 00000000-0000-0000-0000-000000000001
role: ROLE_USER
```

This filter is scoped to the local profile.

## Local PostgreSQL

Use `local-postgres` when you want to test the production-style database path
locally. This profile uses PostgreSQL, enables Flyway and validates the JPA
model against the migrated schema.

Start PostgreSQL:

```powershell
docker compose up -d postgres
```

Run the app with:

```text
SPRING_PROFILES_ACTIVE=local-postgres
```

Default connection:

```text
JDBC URL: jdbc:postgresql://localhost:5432/reals
Username: reals
Password: reals
```

This profile uses the same dev auto-auth behavior as `local-nodb`, so existing
Bruno local flows can run without Firebase tokens. It disables automatic
schedulers; use the dev job endpoints for deterministic manual testing.

Useful database commands:

```powershell
docker compose logs -f postgres
docker compose down
docker compose down -v
```

Use `docker compose down -v` only when you want to delete the local PostgreSQL
data volume and force Flyway to recreate the schema from scratch.

## Local Docker App

Build and run the backend plus PostgreSQL with Docker Compose:

```powershell
docker compose up -d --build backend
```

The `backend` service runs with:

```text
SPRING_PROFILES_ACTIVE=local-firebase
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reals
```

Inside Docker, the database host is `postgres`, not `localhost`, because
`localhost` would point to the backend container itself.

Check the backend:

```powershell
curl http://localhost:8080/api/ping
```

View logs:

```powershell
docker compose logs -f backend
```

Stop only the backend:

```powershell
docker compose stop backend
```

Stop backend and database without deleting the database volume:

```powershell
docker compose down
```

### Local Docker App With Firebase

The default `docker-compose.yml` already runs the backend with
`local-firebase`, PostgreSQL and real Firebase token verification.

The Firebase service-account JSON must exist locally at:

```text
./secrets/reals-backend-firebase-credentials-dev.json
```

The `secrets/` directory is ignored by Git and must never be committed.

Build and run the Firebase-backed Docker app:

```powershell
docker compose up -d --build backend
```

This Compose setup runs the backend with:

```text
SPRING_PROFILES_ACTIVE=local-firebase
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reals
firebase.service-account-path=./secrets/reals-backend-firebase-credentials-dev.json
```

The service-account file is mounted read-only inside the backend container.
Automatic schedulers are disabled by the `local-firebase` profile so local
manual testing stays deterministic.

Check the backend:

```powershell
curl http://localhost:8080/api/ping
curl http://localhost:8080/actuator/health/readiness
```

Stop backend and database without deleting the database volume:

```powershell
docker compose down
```

### Local MinIO Media

The Docker Compose setup also runs MinIO for application media uploads:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=http://minio:9000
STORAGE_S3_PRESIGNED_URL_ENDPOINT=http://localhost:9000
STORAGE_S3_BUCKET=reals-media
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

The backend uploads objects through the internal Docker hostname `minio`. It
stores object keys in `profile_photos.storage_key` and
`chat_messages.audio_object_key`. It stores buckets in `profile_photos.storage_bucket`
and `chat_messages.audio_bucket`. It generates browser-facing presigned read
URLs from the persisted bucket and object key when returning media responses.
New uploads use the currently configured media bucket. The local media bucket is
`reals-media`. The backend uses one active bucket for
`users/<userId>/profile-photos/<objectId>.<extension>` and
`chats/<chatId>/messages/<messageId>.m4a`. Buckets remain private locally.
Frontend clients should render the returned `url` directly. They must not
persist it as a permanent object URL because it expires.

For Android Emulator rendering, `localhost` points to the emulator itself. Use a
local runtime override instead:

```text
STORAGE_S3_PRESIGNED_URL_ENDPOINT=http://10.0.2.2:9000
```

Keep that override local. It is developer-machine specific and should not be
committed to `docker-compose.yml`.

Bruno tracked environment templates must contain placeholders only. Put real
Firebase API keys, test-user passwords and tokens in ignored local environment
files such as:

```text
bruno/reals-backend-happy-path/environments/local.bru
```

## Local Jobs

Local profiles disable automatic scheduled execution:

```yaml
scheduler.enabled: false
```

Use the local dev endpoints for deterministic manual testing:

```http
POST /api/local-dev/jobs/{job}/run
```

`/api/local-dev/**` endpoints are profile-gated tooling for controlled
Bruno/manual verification. The path name is retained for compatibility. They
execute system-level mutations or jobs and must never be called by the Android
application.

In `local-nodb`, `local-postgres` and `local-firebase`, these endpoints are
available without authentication. In hosted `dev`, the same paths are
registered but require `Authorization: Bearer <Firebase ID token>` for an
active user with `ROLE_ADMIN`; that role is assigned through the existing
`BACKOFFICE_ADMIN_EMAILS` allowlist. In `prod`, the real `Dev*` controllers are
not registered and Spring Security explicitly denies `/api/local-dev/**`, even
if a local-dev handler is accidentally registered.

The Bruno collection includes direct triggers under:

```text
bruno/reals-backend-happy-path/10 - Local Dev Jobs
```

Delayed visual review availability can be bypassed for an already-created
pending review during local manual testing:

```http
POST /api/local-dev/matches/{matchId}/visual-review/make-available-now
```

The helper keeps the match in `VISUAL_PHASE`, preserves `createdAt`, sets
`availableAt` to server now, rebases `expiresAt` from server now using the
configured visual-phase duration and invalidates both users' Home state. It does
not create missing reviews, record reliability events or change visual
decisions.

Local-only user provisioning for Bruno/dev flows is available at:

```http
POST /api/local-dev/users
```

This endpoint exists only on local execution profiles and is not part of the production API contract.

The local matchmaking processor endpoint is also exposed in `local-firebase`
for Firebase/Android manual flows:

```http
POST /api/local-dev/matchmaking/process?maxPairsPerRun=10
POST /api/local-dev/pair-history/reset
```

Supported local job triggers:

```http
POST /api/local-dev/jobs/scheduling-activation/run
POST /api/local-dev/jobs/second-chat-reminder/run
POST /api/local-dev/jobs/visual-review-reminder/run
POST /api/local-dev/jobs/second-chat-lifecycle/run
POST /api/local-dev/jobs/chat-timeout/run
POST /api/local-dev/jobs/visual-phase-expiration/run
POST /api/local-dev/jobs/match-expiration/run
POST /api/local-dev/jobs/scheduling-timeout/run
POST /api/local-dev/jobs/inactivity-check/run
POST /api/local-dev/jobs/penalty-expiration/run
POST /api/local-dev/jobs/user-reliability-cleanup/run
POST /api/local-dev/jobs/account-deletion-finalization/run
POST /api/local-dev/jobs/media-cleanup/run
```

To move a confirmed second-chat time into deterministic attendance windows:

```http
POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now
POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-late-window-now
POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-before-hard-cutoff
POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-past-hard-cutoff
```

Then call `POST /api/connections/{connectionId}/second-chat/join` as each
participant to materialize the chat and record attendance. Use `GET
/api/connections/{connectionId}/second-chat/status` to inspect attendance and
pending no-show claims, and `POST
/api/connections/{connectionId}/second-chat/no-show-claims` to start the local
60-second partner no-show countdown.

Second-chat conversation lifecycle can be tested manually without waiting:

```http
POST /api/local-dev/timeouts/chats/{chatId}/second-chat-conversation-started-past
POST /api/local-dev/timeouts/chats/{chatId}/latest-message-before-inactivity-claim
POST /api/local-dev/timeouts/chats/{chatId}/latest-message-before-conversation-started
POST /api/local-dev/timeouts/chats/{chatId}/latest-message-claimable
POST /api/local-dev/timeouts/chats/{chatId}/latest-message-before-automatic-inactivity
POST /api/local-dev/timeouts/chats/{chatId}/latest-message-automatic-inactivity-due
POST /api/local-dev/timeouts/second-chat-resolution-requests/{requestId}/expire-now
POST /api/local-dev/timeouts/second-chat-resolution-requests/{requestId}/completion-cooldown-active
POST /api/local-dev/timeouts/second-chat-resolution-requests/{requestId}/completion-cooldown-expired
POST /api/local-dev/jobs/second-chat-lifecycle/run
POST /api/local-dev/timeouts/chats/{chatId}/expire-now
POST /api/local-dev/timeouts/chats/{chatId}/read-only-expire-now
```

Use the production endpoints to create/respond to completion requests and
inactivity claims, then use the local helpers only to move server-owned clocks.
`latest-message-before-conversation-started` reproduces a waiting message sent
before the second participant joined; status and lifecycle deadlines should then
use `conversationStartedAt` as the effective inactivity clock.
Read-only second chats in `FINISHED`, `ABANDONED` or `EXPIRED` remain readable
until `read-only-expire-now` plus the lifecycle job closes them.

Recoverable account deletion finalization can be triggered manually with:

```http
POST /api/local-dev/jobs/account-deletion-finalization/run
```

## Local Profile Photo Rules

Local/test profile overrides:

- max photos: `9`
- required photos: `4`
- min person photos: `1`
- min full-body photos: `1`

Default application rules are stricter:

- max photos: `9`
- required photos: `9`
- min person photos: `3`
- min full-body photos: `1`

## Flyway And Schema

Local H2 profiles disable Flyway and use Hibernate `ddl-auto: update`.

Local `local-postgres` enables Flyway and uses Hibernate `ddl-auto: validate`.

Production-like schema changes should be represented with migrations under:

```text
src/main/resources/db/migration
```

Current migrations:

```text
V1__init.sql
V2__profile_dynamic_match_filters.sql
V3__add_user_soft_delete.sql
V4__profile_photo_storage_validation.sql
V5__account_deletion_recovery_window.sql
V6__defer_scheduling_activation.sql
V7__second_chat_read_only_retention.sql
V8__connection_home_dismissals.sql
V9__safety_reports_and_penalty_types.sql
V10__push_notifications.sql
V11__drop_profile_photo_url.sql
V12__chat_end_reason_and_user_blocks.sql
V13__safety_report_contexts.sql
V14__profile_photo_moderation_status.sql
V15__profile_identity_verification_status.sql
V16__audit_events_and_safety_report_evidence.sql
V17__admin_safety_report_source.sql
V18__user_home_status.sql
V19__user_reliability_events.sql
V20__first_chat_guidance.sql
V21__profile_looking_for_genders.sql
V22__user_legal_document_actions.sql
V23__legal_document_action_content_sha256.sql
V24__profile_authenticity_verification.sql
V25__profile_authenticity_verified_status_invariant.sql
V26__profile_country_code.sql
V27__matchmaking_candidate_query_indexes.sql
V28__visual_review_reminder_eligibility.sql
V29__media_cleanup_tasks.sql
V30__chat_message_session_sent_at_id_index.sql
```
