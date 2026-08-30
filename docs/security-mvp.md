# MVP Security Hygiene

This document captures the minimum security hygiene expected for Reals MVP
backend, docs and Bruno usage. It is not a substitute for production hardening.

## Secrets In Source Control

Do not commit real secrets or credentials.

Never commit:

- Firebase Web API keys.
- Firebase test user passwords.
- Firebase ID tokens.
- Firebase App Check debug provider secrets or App Check tokens.
- Firebase service-account JSON.
- Deployment secrets.
- S3/R2/MinIO access keys or secret keys for shared environments.

Tracked Bruno templates must use placeholders only, for example:

- `firebase_api_key: paste-firebase-web-api-key-here`
- `firebase_email: paste-firebase-email-here`
- `firebase_password: paste-firebase-password-here`
- `firebase_id_token: paste-token-here`
- `google_oauth_id_token: paste-google-oauth-id-token-here`
- `firebase_google_id_token: paste-google-firebase-id-token-here`
- `firebase_google_uid: paste-google-firebase-uid-here`
- `firebase_google_email: paste-google-firebase-email-here`
- `firebase_counterpart_email: paste-counterpart-email-here`
- `firebase_counterpart_password: paste-counterpart-password-here`
- `firebase_counterpart_id_token: paste-token-here`

Real values belong only in ignored local files such as
`bruno/reals-backend-happy-path/environments/local.bru`, local secret files under
`secrets/`, or deployment secret stores.

If a real key, token, password or service-account credential was ever committed,
rotate it outside the code change.

## Firebase Dev/Staging Validation

The backend supports Firebase service-account configuration through:

- `FIREBASE_SERVICE_ACCOUNT_PATH`
- `FIREBASE_SERVICE_ACCOUNT_JSON`
- `FIREBASE_SERVICE_ACCOUNT_BASE64`

Dev/staging validation should be performed against the deployed backend before
MVP APK distribution:

1. Start the backend with Firebase service-account configuration supplied by the
   deployment environment, not source control.
2. Confirm no service-account JSON is committed to the repository.
3. Sign in from Android using Firebase email/password auth and obtain a Firebase
   ID token.
4. Call the backend with `Authorization: Bearer <token>`.
5. Confirm `POST /api/me/provision` works against the deployed dev/staging
   backend.
6. Confirm invalid, expired or malformed tokens return a stable `401` response.
7. Validate deleted-account behavior with a real Firebase token.
8. Validate reactivation inside the configured recovery window.
9. Validate finalized deletion remains blocked after the recovery window.

These checks require real deployment secrets and real Firebase tokens, so they
remain operational validation unless explicitly executed in that environment.

## Firebase Auth Revocation Cache

Firebase Authentication still runs on every protected request. The backend
validates the ID token locally on every request, including signature,
expiration, issuer and standard Firebase Admin token validity. Successful
revocation/disabled-user checks are cached briefly by a SHA-256 hash of the
exact bearer token, not the raw token.

`security.firebase-auth.revocation-cache-ttl` defaults to `PT60S`. An external
Firebase token revocation or disabled-user change can therefore take up to that
TTL to affect a token that was successfully checked recently. Failed,
malformed, expired, revoked or disabled-token results are not cached. Local
deleted-account enforcement remains immediate because the backend resolves the
local Reals user and checks `UserStatus.DELETED` on every request.

## Firebase App Check Boundary

Firebase App Check is an application-attestation boundary for Android-facing
API traffic. It is separate from Firebase Authentication: App Check identifies
an accepted Firebase App ID, while Firebase Auth still identifies the user and
still performs ID-token verification; successful revocation checks are cached
briefly as described above.

Android sends App Check through exactly one header:

```http
X-Firebase-AppCheck: <token>
```

The backend does not accept App Check tokens in query parameters, cookies or
request bodies. On successful verification, the filter may attach only the
validated Firebase App ID as a request attribute; it never becomes a user
principal, role or authorization decision.

When enabled, App Check applies to `/api/**`, including provisioning,
authenticated user/profile/photo endpoints, legal catalog endpoints and
`/api/me/local-dev/email-verification`. Exclusions are `OPTIONS`, `/api/ping`,
`/api/local-dev/**`, `/actuator/health`, `/actuator/health/**`,
`/actuator/info`, `/actuator/metrics`, `/actuator/metrics/**` and
`/h2-console/**`. The `/api/local-dev/**` exclusion is only for system/developer
tooling and does not cover `/api/me/local-dev/email-verification`.

Stable App Check failures use the normal JSON error shape:

- `401 MISSING_APP_CHECK_TOKEN`
- `401 INVALID_APP_CHECK_TOKEN`
- `503 APP_CHECK_VERIFICATION_UNAVAILABLE`

Verification requires a Firebase App Check JWT signed by Firebase keys from
`https://firebaseappcheck.googleapis.com/v1/jwks`, `alg=RS256`, `typ=JWT`, the
issuer `https://firebaseappcheck.googleapis.com/{firebaseProjectNumber}`, a
non-expired token, audience `projects/{firebaseProjectNumber}`, a nonblank
subject and a subject present in the configured Firebase App ID allowlist. The
allowlist contains Firebase App IDs, not package names.

JWKS retrieval uses the JWT library's remote JWK handling and caches keys
instead of fetching them for every request. Firebase key rotation is therefore
handled through JWKS refresh rather than hardcoded public keys.

Replay protection and limited-use App Check tokens are intentionally deferred
from this MVP. App Check reduces abuse from unofficial clients, but it does not
replace Firebase Authentication, authorization, rate limiting, domain
validation, TLS, moderation or operational monitoring.

## Firebase Authorization Boundaries

`ROLE_USER` is granted to an existing active backend user found by verified
Firebase UID. Firebase email verification is not required for ordinary linked
user sign-in.

Reals keeps exactly one canonical Firebase user per backend account. The
Firebase UID remains the external identity key; the backend does not exchange
Google OAuth tokens, store Google access tokens, use Google client secrets,
create a separate Google-user table, or use Firebase custom claims for auth
origin.

Each Firebase-linked account has an immutable backend-owned `authOrigin` once
set by the first successful Reals provisioning flow:

- `EMAIL_PASSWORD`: the account was first successfully provisioned into Reals
  through the email/password flow backed by Firebase password authentication.
- `GOOGLE`: the account was first successfully provisioned into Reals through
  the Google flow backed by Firebase Google authentication.

`authOrigin` is not recomputed from Firebase provider ordering and is not
Firebase's current provider list. Firebase may authenticate or provide an
identity, but provisioning in this backend means the successful creation or
linking of that identity into the Reals account model through
`POST /api/me/provision`. Reauthentication, reactivation, subsequent Firebase
provider linking, provider unlinking, or Firebase provider metadata changes
must not change a non-null origin. Existing Firebase-linked users from before
Google support are migrated to `EMAIL_PASSWORD`; backend-only legacy rows with
no Firebase UID may remain null until first linked.

The backend reads `firebase.sign_in_provider` only from the verified Firebase ID
token. Supported values are `password` and `google.com`; missing or unsupported
providers fail closed on real Firebase-token paths. The filter enforces:

- `EMAIL_PASSWORD` origin accepts `password` and `google.com` tokens for the
  same Firebase UID.
- `GOOGLE` origin accepts only `google.com` tokens.
- `GOOGLE` origin with a `password` token returns `401 AUTH_METHOD_NOT_ALLOWED`,
  including for recoverably deleted accounts.

`ROLE_ADMIN` is granted only when all conditions are true:

- the verified Firebase UID resolves to a backend user;
- that backend user is `ACTIVE`;
- the backend user's persisted `firebaseUid` exactly matches the token UID;
- the Firebase token email is verified;
- the normalized Firebase token email is present in `BACKOFFICE_ADMIN_EMAILS`.

The allowlist decision uses only the Firebase token email. The backend user's
persisted local email is not a fallback for administrator authority, and an
unprovisioned Firebase principal never receives `ROLE_ADMIN`.

Provisioning links an existing legacy backend row by email only when the
Firebase token reports `emailVerified=true`. An unverified Firebase email can
still create a brand-new backend user and can still load an already-linked
backend user by Firebase UID. Existing linked users do not have their persisted
email overwritten from an unverified Firebase token email.

If the same normalized email is already bound to a different non-null Firebase
UID, Reals refuses the provisioning attempt with
`EMAIL_ALREADY_LINKED_TO_DIFFERENT_FIREBASE_USER`. It never silently rebinds or
merges distinct Firebase users. Firebase environments used by Reals must be
configured for the single-account-per-email model, but the backend still
defends against unexpected same-email/different-UID collisions.

`POST /api/auth/password-reset` is public with respect to Firebase bearer
authentication and returns `202 Accepted` for every syntactically valid request.
It remains under App Check when App Check is enforced and under the pre-auth
rate limiter. The response never reveals account existence, auth origin,
deletion state or Firebase delivery outcome. The backend sends a Firebase
password reset only for `EMAIL_PASSWORD` accounts with a Firebase UID that are
`ACTIVE`, or `DELETED` while `now < deletionFinalizesAt`. At exact equality
(`now >= deletionFinalizesAt`) reset delivery stops. `GOOGLE` origin accounts
are passwordless in Reals and never receive Reals password reset delivery.
`passwordManagementAllowed` is derived from this immutable Reals origin, not
from later Firebase provider-link metadata.

`POST /api/me/local-dev/email-verification` is a local-only Firebase Admin
helper for the `local-firebase` profile and must remain absent from hosted
`dev` and `prod`. It requires a provisioned authenticated backend user with
`ROLE_USER`, derives the Firebase UID only from the authenticated principal, and
sets that Firebase Auth account's `emailVerified=true`. It does not persist
email verification in PostgreSQL, activate profiles, weaken upload or activation
guards, accept arbitrary UIDs/emails, issue tokens or add custom claims.

## Rate Limiting Boundaries

Rate limiting is in-memory and single-instance only. Buckets are backed by
Caffeine and are not shared across app replicas. Infrastructure-level WAF,
gateway or distributed limiting remains a deployment concern.
The `prod` profile refuses to start when `security.rate-limit.enabled=false`.

The pre-authentication limiter runs before Firebase token verification and keys
only by endpoint group plus `request.remoteAddr`:

- `pre-auth:{endpoint-group}:ip:{client-ip}`

It never uses bearer token text, bearer-token hashes or unverified Firebase
claims. It uses dedicated broad quotas, not endpoint-specific user/business
quotas. Current defaults are `security.rate-limit.pre-auth-capacity=600`,
`security.rate-limit.pre-auth-refill-tokens=600` and
`security.rate-limit.pre-auth-refill-period-seconds=60`.

Pre-auth limiting applies to protected API traffic and to protected Actuator
authentication surfaces: `/actuator/info`, `/actuator/metrics` and
`/actuator/metrics/**`. Health endpoints remain public and excluded.

The post-authentication limiter runs after authentication and keys by endpoint
group plus a stable authenticated identity:

- `post-auth:{endpoint-group}:user:{backend-user-id}`
- `post-auth:{endpoint-group}:firebase:{firebase-uid}`
- `post-auth:{endpoint-group}:local-dev:{dev-user-id}`

Production deployments must configure the trusted reverse proxy or servlet
container so `request.remoteAddr` is the real client IP only when forwarded by
trusted infrastructure. The application filter does not parse or trust arbitrary
`X-Forwarded-For`, `Forwarded`, `X-Real-IP` or similar request headers.

## Visual Content Boundaries

Visual-profile and personal-message reads/writes are allowed only after the
requesting user is validated as a match participant and the participant pair is
not blocked.

Allowed match states:

- `VISUAL_PHASE`: the visual review must exist, `expiresAt` must be non-null,
  and `expiresAt` must still be in the future according to server time.
- `VISUAL_APPROVED`: the visual review must exist, a connection for the match
  must exist, the requester must belong to that connection, and the connection
  must be in an active non-closed state.

Denied match states include `CHAT_ACTIVE`, `CHAT_REJECTED`,
`VISUAL_REJECTED` and `EXPIRED`. Denied partner-message reads do not update
read timestamps.

## Sensitive Log Policy

Do not log:

- Firebase ID tokens.
- Firebase App Check tokens.
- `Authorization` headers.
- JWT signatures or complete JWT claims.
- App Check debug provider secrets.
- Private media URLs or presigned URLs.
- Raw request bodies containing chat or personal-message content.
- Passwords or test credentials.
- Firebase service-account JSON or credential file contents.

Prefer safe metadata in logs:

- request ID;
- entity IDs where appropriate;
- status or state;
- aggregate counts;
- stable error codes;
- operation names.

Keep full exception stack traces for server-side failures, but do not include
sensitive user-provided content in the log message itself.

## CSRF And Stateless Bearer Auth

CSRF is disabled only because the Reals API is stateless and authenticated with
explicit `Authorization: Bearer ...` tokens. The backend disables form login,
HTTP Basic and server sessions for the API security chain.

Revisit CSRF before introducing:

- cookie authentication;
- form login;
- browser-managed sessions;
- credentials automatically attached by browsers.
