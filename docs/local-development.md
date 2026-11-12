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

## Localhost, ADB Reverse And Explicit LAN Access

Compose publishes only IPv4 loopback by default:

| Service | Host binding |
| --- | --- |
| Spring Boot | `127.0.0.1:8080` |
| PostgreSQL | `127.0.0.1:5432` |
| MinIO API | `127.0.0.1:9000` |
| MinIO administration console | `127.0.0.1:9001` |

`localhost` refers to the machine running the client. On the development host,
use `http://localhost:8080` (or explicitly `http://127.0.0.1:8080`). If a tool
resolves localhost only to IPv6 `::1`, select `127.0.0.1`; no IPv6 port is
published. Container-to-container addresses remain `postgres:5432` and
`http://minio:9000`; do not change them to localhost.

### Web And Chrome Android Through ADB Reverse

The existing host-run Vite `/api` proxy to `http://localhost:8080` continues to
work. Firebase Authentication still verifies real tokens; no auth settings or
Firebase credentials change with these bindings.

For Chrome on a USB-connected Android device, keep the default Compose
bindings and forward the development ports through ADB:

```powershell
adb reverse tcp:5173 tcp:5173
adb reverse tcp:8080 tcp:8080
adb reverse tcp:9000 tcp:9000
adb reverse --list
```

Start Vite as usual on the host and open `http://localhost:5173` in phone Chrome.
The browser calls the Vite proxy; media URLs using `127.0.0.1:9000` reach host
MinIO through the reverse mapping. The 8080 mapping also supports a native
Android debug build configured for `http://127.0.0.1:8080`. No LAN publication
is needed. ADB requires an authorized device with USB debugging; mappings must
be restored after disconnect/restart. Remove them when finished with
`adb reverse --remove tcp:5173` (and likewise for 8080 and 9000).

### Native Android On The Same LAN (Explicit Opt-In)

Use only a trusted development LAN. `local-firebase` exposes development-only
endpoints, including unauthenticated `/api/local-dev/**` mutations and an
authenticated email-verification helper. It must never be deployed as a public
environment, even though it verifies real Firebase ID tokens. Do not publish
local profiles using router port forwarding or public tunnels; use a separately
secured shared `dev` deployment for remote testing.

In PowerShell, substitute the host's actual IPv4 LAN address (not the phone IP):

```powershell
$env:REALS_LOCAL_API_BIND_ADDRESS = '192.168.0.5'
$env:STORAGE_S3_PRESIGNED_URL_ENDPOINT = 'http://192.168.0.5:9000'
docker compose config --quiet
docker compose up -d --no-deps backend minio
```

This assumes the stack has already been built/initialized with the normal
`docker compose up -d --build backend` workflow. The opt-in publishes only
8080 and 9000 on that specific host interface. PostgreSQL 5432 and the MinIO
console 9001 stay on `127.0.0.1`; do not open firewall rules for those ports.
Do not use `0.0.0.0` as a convenience default. Allow inbound TCP 8080/9000 only
from the test device/trusted subnet in the Windows Private network firewall
profile. Do not disable the firewall. A changed DHCP address requires updating
both values and recreating backend/MinIO.

Point the native Android debug backend URL to `http://192.168.0.5:8080` and
use its existing development cleartext-network configuration. The presigner
must use `http://192.168.0.5:9000` before generating URLs. Never replace the
host, port, path or query in a returned presigned URL: the Host and request are
part of the signature. Internal uploads still use `http://minio:9000`.
Fetch fresh media responses after changing endpoints; old URLs keep their old
host and expiry. A single presigner endpoint serves all clients, so switching
to LAN mode also makes host clients use that LAN media address. With a specific
LAN interface binding, use the LAN backend address on the host too; a Vite
proxy targeting localhost needs the default mode. Use ADB reverse when testing
Web and Android together through localhost.

Return to the default mode in the same shell (also remove any opt-in persisted
in an ignored backend `.env` or shell profile):

```powershell
Remove-Item Env:REALS_LOCAL_API_BIND_ADDRESS -ErrorAction SilentlyContinue
Remove-Item Env:STORAGE_S3_PRESIGNED_URL_ENDPOINT -ErrorAction SilentlyContinue
docker compose config --quiet
docker compose up -d --no-deps backend minio
```

Environment variables affect Compose interpolation; no additional override file
is required. Empty/unset variables use the defaults in `docker-compose.yml`.
Never put Firebase secrets into these values or into tracked files.

### Applying And Verifying The Bindings On Windows Docker Desktop

Editing Compose does not change existing containers. When ready for a brief
local interruption, apply the default bindings to an existing initialized stack:

```powershell
docker compose config --quiet
docker compose up -d --no-deps backend postgres minio
docker compose ps
curl.exe --fail http://127.0.0.1:8080/api/ping
curl.exe --fail http://127.0.0.1:8080/actuator/health/readiness
curl.exe --fail http://127.0.0.1:9000/minio/health/live
```

Expect ping `{"status":"ok"}`, readiness success and MinIO HTTP 200. Check
`docker compose ps`/`docker inspect` for all four loopback bindings and no
`0.0.0.0` or `::` publications. Verify host PostgreSQL access with the existing
local database client and open `http://127.0.0.1:9001` for the console. Existing
named PostgreSQL and MinIO volumes are reused; do not run `down -v`, remove
volumes or reset the database to apply networking changes.

From a second LAN device, confirm all four host ports are inaccessible in
default mode. In explicit LAN mode, verify ping/readiness on the LAN address,
Firebase sign-in/provisioning and media upload/read in native Android; confirm
5432/9001 remain inaccessible. Test a newly generated presigned URL unchanged
and confirm successful media delivery without signature errors. Repeat Chrome
Android through ADB reverse in default mode and verify Web API/media access.
Docker Desktop forwarding, Windows firewall rules, Wi-Fi client isolation,
VPNs and device configuration require these real-device checks; Compose config
validation alone cannot prove network isolation or Android connectivity.

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

For real administrative Google + TOTP testing, configure the ignored root `.env`
with `BACKOFFICE_ADMIN_UIDS`, `BACKOFFICE_ADMIN_EMAILS`,
`BACKOFFICE_ALLOWED_WEB_APP_IDS`, `FIREBASE_PROJECT_NUMBER` and
`FIREBASE_APP_CHECK_ALLOWED_APP_IDS`. Include the Web App ID in both App ID
allowlists and retain the Android IDs in the general allowlist. Use the Firebase
project number, not its textual project ID. Never commit actual identities or
credentials. `.env` is also excluded from the Docker build context.

Set `FIREBASE_APP_CHECK_MODE=MONITOR` for initial local integration. Administrative
requests still require successfully verified Web App Check in this mode; missing
or invalid attestation cannot authorize administration. Configure the Web provider
in Firebase separately. Production requires `ENFORCED`.

The operator must generate/register the TOTP factor offline using
[the admin runbook](admin-auth.md#offline-initial-setup), then privately populate
`ADMIN_MFA_TOTP_SECRET` and a new `ADMIN_MFA_SECRET_VERSION` in `.env`. Empty values
leave administration disabled. Do not generate secrets in build commands or paste
them into command arguments, logs, source files or tickets.

Compose uses `.env` for interpolation; it does not automatically inject every key
into the container. The backend environment explicitly forwards these variables.
Shell variables take precedence over `.env`; clear unintended overrides privately.
Validate without printing the resolved configuration, then rebuild/recreate only
the backend (with PostgreSQL and MinIO already running):

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build --force-recreate --no-deps backend
```

A container restart alone does not reload environment variables. Recreating the
backend retains the PostgreSQL volume and persisted MFA replay/lockout state.
Check readiness and Flyway V49 before testing the
[Web verification sequence](web-admin-integration-handoff.md). Keep Firebase tokens,
TOTP codes and returned grants out of terminal recordings and shared output; hold
grants only in Web memory.

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

Set it in the shell before recreating the backend, using the Compose
`STORAGE_S3_PRESIGNED_URL_ENDPOINT` interpolation described above. Keep it
local. Verify emulator host routing on Docker Desktop; ADB reverse is an
alternative that retains the default loopback endpoint.

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
registered but denied pending equivalent verified App Check support. See
[administrative authentication](admin-auth.md) for isolated local testing without production bypasses.
In `prod`, the real `Dev*` controllers are
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
