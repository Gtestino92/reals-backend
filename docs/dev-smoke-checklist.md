# DEV External Provider Smoke Checklist

Use this checklist before production deployment to prove the hosted `dev`
environment can exercise the real external providers that production depends
on. These are manual smoke tests against DEV only. Do not use production
Firebase projects, users, buckets, Sightengine credentials, device tokens or
data.

## Provider Matrix

| Integration | Local | Dev default | Dev smoke | Prod |
| --- | --- | --- | --- | --- |
| Sightengine | `profile.photos.moderation.provider=none` no-op path | no-op path | real opt-in with `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine` | required real Sightengine |
| Firebase Auth | `local-firebase` real Admin SDK or local auto-auth in non-Firebase local profiles | real Firebase Admin token verification | real Firebase Admin token verification | real Firebase Admin token verification |
| App Check | disabled by default in `local-firebase` | disabled by default | `MONITOR` or `ENFORCED` with real project number and dev Firebase App IDs | `ENFORCED` required |
| FCM | no-op outside `local-firebase`; Firebase sender in `local-firebase` | Firebase Admin Messaging | Firebase Admin Messaging to a real Android device | Firebase Admin Messaging |
| Storage | local MinIO in Docker for `local-firebase` | configured S3-compatible DEV bucket | real DEV S3-compatible bucket | private PROD S3-compatible bucket |
| Firebase account deletion | no-op only when Firebase Admin is not configured | real Firebase Auth deletion during finalization | disposable real DEV Firebase user | real Firebase Auth deletion during finalization |

## Provider Isolation

- Use a separate Firebase DEV project, Firebase Admin credential, App Check app
  registration, FCM sender project and Android `dev` app from production.
- Use a separate DEV S3-compatible bucket or at least an isolated DEV prefix;
  do not point DEV at production media.
- Prefer separate DEV Sightengine credentials when available. If the provider
  account is shared, keep DEV uploads limited to disposable test media.
- Store all secrets in the runtime secret store or `/etc/reals/backend.env`.
  Never commit credentials or paste reusable tokens into repository files.

## Backend Startup

Preconditions:

- `SPRING_PROFILES_ACTIVE=dev`.
- PostgreSQL, Firebase Admin credentials and S3-compatible storage are
  configured for DEV resources.
- Optional Sightengine smoke uses:

```text
PROFILE_PHOTO_MODERATION_PROVIDER=sightengine
SIGHTENGINE_API_USER=<dev-sightengine-api-user>
SIGHTENGINE_API_SECRET=<dev-sightengine-api-secret>
PROFILE_PHOTO_SIGHTENGINE_ENDPOINT=https://api.sightengine.com/1.0/check.json
```

Actions:

1. Deploy the backend to hosted DEV.
2. Confirm `/actuator/health/readiness` returns `{"status":"UP"}`.
3. Inspect startup logs.

Expected backend result:

- Flyway migration succeeds and Hibernate validation passes.
- The safe startup summary shows `executionProfile=dev`, the selected photo
  moderation provider, App Check mode, `pushProvider=firebase`,
  `storageProvider=s3-compatible` and S3 read URL mode.
- No Firebase private key, Sightengine secret, bearer token, App Check token,
  S3 secret or FCM registration token appears in logs.

Pass evidence:

- Deployment revision, readiness response and startup summary log line.

## Firebase Authentication

Preconditions:

- Android DEV app points at the DEV backend and Firebase DEV project.
- The tester has a real Firebase DEV user.

Actions:

1. Sign in from Android and obtain a Firebase ID token.
2. Call `POST /api/me/provision`.
3. Call `GET /api/me` or `GET /api/me/home/status`.
4. Repeat one request with an expired, malformed or intentionally wrong bearer
   token.

Expected backend result:

- Valid token resolves through `FirebaseTokenFilter` and returns a provisioned
  backend user.
- Invalid or expired token returns `401 INVALID_TOKEN`.
- No fake auth bypass is available in DEV.

Expected provider result:

- Firebase Admin verifies the real DEV ID token and revocation state.

Pass evidence:

- Successful authenticated response for the real user and a failed invalid-token
  response.

## App Check

Preconditions:

- DEV App Check smoke is configured with:

```text
FIREBASE_APP_CHECK_MODE=ENFORCED
FIREBASE_PROJECT_NUMBER=<numeric-dev-project-number>
FIREBASE_APP_CHECK_ALLOWED_APP_IDS=<dev-firebase-android-app-id>
```

- Android DEV app obtains a legitimate DEV App Check token from the currently
  configured Firebase App Check provider.

Actions:

1. Call `GET /api/me/home/status` with both `Authorization: Bearer <id-token>`
   and `X-Firebase-AppCheck: <app-check-token>`.
2. Repeat the same request without `X-Firebase-AppCheck`.
3. Repeat with a malformed App Check token.

Expected backend result:

- Valid token request succeeds.
- Missing token returns `401 MISSING_APP_CHECK_TOKEN` when mode is `ENFORCED`.
- Invalid token returns `401 INVALID_APP_CHECK_TOKEN` when mode is `ENFORCED`.

Expected provider result:

- The backend verifies the App Check JWT against Firebase App Check JWKS and the
  configured project/app allowlist.

Pass evidence:

- One accepted request, two rejected requests, and `reals.app_check.requests`
  counters or logs showing expected outcomes.

## S3-Compatible Storage

Preconditions:

- Hosted DEV uses a real DEV bucket. For native AWS S3, prefer:

```text
STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN
STORAGE_S3_REGION=<aws-region>
STORAGE_S3_BUCKET=<dev-bucket-name>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=false
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

- For R2 or hosted MinIO, use the matching `STORAGE_S3_ENDPOINT`,
  `STORAGE_S3_PRESIGNED_URL_ENDPOINT`, static credentials and private bucket
  configuration.

Actions:

1. Upload a valid profile photo through `POST /api/me/profile/photos`.
2. Confirm the upload response contains a renderable `url`.
3. Call `GET /api/me/profile/photos` and verify it returns a fresh read URL.
4. Replace the photo through `PUT /api/me/profile/photos/{photoId}/file`.
5. Delete the photo through `DELETE /api/me/profile/photos/{photoId}`.
6. If cleanup is asynchronous for the old object, run the existing DEV admin
   job trigger `POST /api/local-dev/jobs/media-cleanup/run` as a DEV admin.

Expected backend result:

- Upload, read URL generation, replacement and deletion succeed through normal
  application endpoints.
- Persisted API responses expose renderable URLs, not internal bucket secrets.

Expected provider result:

- New objects appear under `users/<userId>/profile-photos/`.
- Replacement creates a new object and the old object cleanup path completes.
- Delete removes or idempotently tolerates absence of the target object.

Pass evidence:

- API responses, backend logs without secret leakage and storage-console object
  state for the DEV bucket.

## Sightengine

Preconditions:

- DEV is explicitly opted in with `PROFILE_PHOTO_MODERATION_PROVIDER=sightengine`.
- `SIGHTENGINE_API_USER`, `SIGHTENGINE_API_SECRET`, endpoint and positive
  timeouts are configured.
- `PROFILE_MIN_FULL_BODY_PHOTOS=0` may be useful because Sightengine currently
  does not set `isFullBody=true`.
- Use disposable safe/review test images. Do not add unsafe binary content to
  this repository.

Actions:

1. Restart hosted DEV and confirm the startup summary shows
   `photoModerationProvider=sightengine`.
2. Upload an expected-safe profile photo through `POST /api/me/profile/photos`.
3. Verify the response and `GET /api/me/profile/photos` state.
4. Upload a controlled image expected to cross a configured moderation review
   or rejection threshold, if appropriate for the provider account and test
   policy.
5. Temporarily misconfigure credentials only in a disposable DEV deployment if
   provider-failure behavior must be checked.

Expected backend result:

- Safe upload reaches the normal `APPROVED` state when Sightengine returns low
  moderation scores and at least one real face.
- Review/rejection test reaches `NEEDS_REVIEW`, `REJECTED`, or the configured
  upload failure behavior according to the current thresholds.
- No no-op compatibility marker is used; unlike the no-op path, Sightengine
  success persists provider-derived `isPersonPhoto` and currently leaves
  `isFullBody=false`.

Expected provider result:

- Sightengine receives one request per technically valid upload/replacement.
- Provider errors do not log credentials or raw secrets.

Pass evidence:

- DEV startup summary, upload response/state, and safe operational logs showing
  no fallback to the no-op configuration.

## FCM

Preconditions:

- Firebase Admin credentials are for the DEV Firebase project.
- Android DEV app is installed on a real registered Android device.
- Notification permissions are granted and the app can obtain an FCM token.

Actions:

1. Sign in and call `PUT /api/me/push-tokens` with the Android FCM token.
2. Create a normal Reals flow that sends a push. The shortest current options
   are a visual-review reminder or second-chat reminder/start notification.
3. For scheduler-driven reminders, create the domain state normally and, as a
   DEV admin, run the relevant existing job trigger such as
   `POST /api/local-dev/jobs/visual-review-reminder/run`,
   `POST /api/local-dev/jobs/second-chat-reminder/run`, or
   `POST /api/local-dev/jobs/second-chat-start-notification/run`.
4. Confirm the device receives the notification.

Expected backend result:

- Device token is stored/enabled for the authenticated user.
- Push delivery persistence records a sent provider result or a clear provider
  failure.
- Automated tests cover invalid-token handling for `UNREGISTERED` and Firebase
  Messaging `INVALID_ARGUMENT`, including disabling only invalid devices.

Expected provider result:

- Firebase Admin Messaging returns a provider message ID for a valid token.

Pass evidence:

- `PUT /api/me/push-tokens` success, delivery row/log with provider message ID,
  and physical device receipt.

## Safety/Admin

Preconditions:

- `BACKOFFICE_ADMIN_EMAILS` contains the verified Firebase email for the DEV
  admin user.
- Test users are disposable and have active engagements where containment needs
  to be observed.

Actions:

1. Confirm admin authentication by calling
   `GET /api/admin/safety-reports/pending`.
2. From a normal user, create a non-blocking report through
   `POST /api/safety/reports`, then separately verify either `blockUser: true`
   or `POST /api/matches/{matchId}/block`; confirm report containment and
   permanent block behavior independently.
3. As admin, create an ADMIN report through `POST /api/admin/safety-reports`
   and verify it remains `PENDING`.
4. Dismiss a pending report through
   `POST /api/admin/safety-reports/{reportId}/dismissal`; verify no sanction.
5. Confirm a temporary ban through
   `POST /api/admin/safety-reports/{reportId}/penalty` with
   `{"type":"TEMPORARY_BAN","durationHours":24,...}`.
6. Verify the banned user receives `403 ACCOUNT_TEMPORARILY_BANNED`, active
   engagements are contained, and old closed engagements stay closed after
   temporary expiry.
7. Confirm a permanent ban on another disposable user and verify
   `403 ACCOUNT_PERMANENTLY_BANNED`.

Expected backend result:

- `/api/admin/**` is available in DEV and requires `ROLE_ADMIN`.
- ADMIN report creation and dismissal do not ban or contain the reported user.
- Confirmed temporary/permanent penalties deny account access. Permanent bans
  fully contain active engagements; temporary bans remove matchmaking queue
  entries and selectively contain only non-viable active engagements.

Pass evidence:

- Admin endpoint responses, penalty response, denied auth response and closed
  engagement state.

## Firebase Account Deletion

Preconditions:

- Use a disposable Firebase DEV account and corresponding backend user.
- Do not run this smoke against production Firebase users.

Actions:

1. Sign in and provision the disposable account.
2. Create at least one active engagement if containment is being verified.
3. Call `DELETE /api/me`.
4. For finalization smoke, either wait until `deletionFinalizesAt` or use the
   existing DEV admin/manual flow intentionally available for deletion
   finalization testing.
5. Verify Firebase Auth user deletion in the Firebase DEV console or Admin API.
6. Attempt Firebase login/token refresh and backend access again.

Expected backend result:

- User becomes `DELETED` during the recovery window.
- Active engagements are contained through `UserOperationalContainmentService`.
- Finalization removes the local Firebase UID after external deletion is
  confirmed or Firebase reports the user already absent.
- Backend access no longer succeeds for the deleted/finalized account except
  the documented recoverable-deletion endpoints before finalization.

Expected provider result:

- Firebase Auth DEV user is actually deleted at finalization.

Pass evidence:

- Backend user lifecycle state, contained engagement state, Firebase console/API
  absence and failed subsequent auth/access attempt.

## Non-Goals For This Smoke

- Do not add or use provider-specific debug endpoints.
- Do not run automated tests against Sightengine, Firebase, FCM or S3.
- Do not deploy production infrastructure, production CI/CD, DNS, TLS,
  production backups, alerting or rollback automation from this branch.
