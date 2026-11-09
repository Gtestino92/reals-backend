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

`ROLE_ADMIN` requires Google authentication, both UID and verified-email allowlists,
a verified allowlisted Web App Check App ID, and an unexpired, unrevoked PostgreSQL
MFA grant bound to the current Firebase authentication event and factor. Email-only
administrative authority has been removed. See [administrative authentication](admin-auth.md)
for exact enforcement, limits, rotation and operator setup. Actuator info/metrics and
dev administrative tooling are denied pending equivalent App Check support.

Administrative account bans are enforced inside the Firebase authentication
filter for existing `ACTIVE` backend users. A temporary ban blocks all protected
API routes while it is effective. A permanent ban blocks normal API access with
`403 ACCOUNT_PERMANENTLY_BANNED`.

The only permanent-ban exception is the authenticated appeal surface:

```text
GET  /api/me/ban/appeal
POST /api/me/ban/appeal
```

Those requests still require a valid Firebase ID token, preserve normal
`authOrigin` validation, remain under Firebase App Check when App Check is
enabled, and remain under pre- and post-authentication rate limiting. They are
not `permitAll` routes and are not excluded from the Firebase token filter.
For this exception the filter builds a normal `CurrentUserAuthContext` but
grants only `ROLE_USER`; it never grants `ROLE_ADMIN` even when the Firebase
email is allowlisted. Similar paths or methods are not exempt.

A pending permanent-ban appeal does not suspend or lift the ban. If an
administrator approves the appeal, the specific permanent penalty becomes
inactive and the user is eligible only for future activity, subject to any other
effective ban. Approval does not reconstruct contained chats, matches,
connections, scheduling state, matchmaking queue rows, or historical safety
report verdicts.

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
authentication and returns `202 Accepted` for every syntactically valid request
that passes rate limiting and the applicable App Check policy.
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
quotas, except for the opt-in password-reset policy below. Current defaults are `security.rate-limit.pre-auth-capacity=600`,
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

Buckets expire after idle time equal to the greater of 15 minutes and
`ceil(capacity / refillTokens) * refillPeriod`. Each entry uses its own policy;
short-window entries do not inherit the safety-report retention period. Each
filter cache remains capped at 100,000 entries. Refill is discrete: immediately
before a period boundary no tokens are added; at equality (and after) the elapsed
periods are credited up to capacity. Cache access renews idle retention, and
bucket consumption/refill remains synchronized.

The proposed dedicated pre-auth policy for exactly `POST /api/auth/password-reset`
is capacity 10, refill 10 every 3600 seconds per `remoteAddr`, independent of
email and authentication. `security.rate-limit.password-reset-enabled` defaults
to `false` in all profiles, including prod, until the proxy boundary is verified.
When disabled it falls back to the existing broad pre-auth policy (600/60s by
default); unrelated groups keep that policy regardless of the reset switch.
Rejected requests retain `429 RATE_LIMIT_EXCEEDED` and `Retry-After` in seconds.
App Check and Firebase authentication filter ordering are unchanged.

These are process-local token buckets, not strict durable daily/hourly quotas.
Restarts and capacity eviction can reset available quota; multiple replicas
multiply it. A burst can also straddle a discrete refill boundary. Distributed
or durable quota enforcement remains deferred.

### Client IP verification gap and activation prerequisite

Repository evidence (`docs/aws-prod-deployment.md`, `docs/dev-deployment.md`,
`ops/aws/deploy-backend.sh`) describes operator-managed Nginx in front of an
EC2 Docker backend, with default port binding `127.0.0.1:8080:8080` and an external
runtime env file. Helm values also describe an Nginx ingress, but do not prove
the effective EC2 configuration. The actual Nginx configuration, runtime env
file, and trusted proxy/container IP handling are not available in this repo.
No explicit Spring forwarded-header strategy or trusted proxy valve is defined
in the checked-in application configuration. Consequently `remoteAddr` cannot
be proven to identify individual production clients; it may identify a shared
proxy/Docker peer. No forwarded-header parsing or speculative proxy settings
were added.

Before setting `RATE_LIMIT_PASSWORD_RESET_ENABLED=true` in a hosted environment,
operators must verify the effective ingress path and container configuration:
only trusted infrastructure can reach the backend; the edge removes/replaces
client-supplied forwarding headers; the servlet container resolves client IPs
only through explicitly trusted proxy hops; two external clients produce distinct
`remoteAddr` values; and spoofing forwarding headers cannot change that identity.
This requires runtime evidence outside this change. Do not enable the stricter
policy while all clients may share the proxy IP. Direct local tests may enable
it explicitly. No infrastructure or deployment configuration is changed here.

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


## Persistent account provisioning quota

`POST /api/me/provision` can enforce a persistent network-origin quota for new
`users` rows: 3 in a rolling 24 hours AND 5 in a rolling 7 days by default.
It is disabled in every profile by default, independently of the request token
buckets. Existing UID provisioning, login, verified legacy linking, reactivation,
conflicts and rolled-back creation do not consume it. Firebase identities are
never deleted or disabled because of this quota.

Only servlet `request.remoteAddr` is used. This feature never parses
`X-Forwarded-For`, `Forwarded` or `X-Real-IP`. IPv4 uses all 32 bits; IPv4-mapped
IPv6 is treated as IPv4; IPv6 uses the first 64 bits. Numeric spelling variants
normalize to the same binary origin. Hostnames and scoped addresses are rejected.
A stable HMAC-SHA256 of a family marker and origin bytes is stored; raw addresses,
UIDs, emails and account IDs are absent from quota events and metric labels.
HMAC pseudonymizes the origin; it is not irreversible anonymity if the key leaks.

Transactions lock UID then HMAC origin using PostgreSQL transaction-scoped
advisory locks before counting/inserting. UID/email uniqueness remains the final
account-creation backstop. Account and event commit/rollback together. The backend
uses PostgreSQL `clock_timestamp()` after acquiring the origin lock. An event
counts only when `created_at > now - window`: at exact equality it expires.
Retries re-read committed state in a new transaction and cannot double-charge.
Cleanup deletes only events at or before the weekly cutoff, ordered by timestamp
and ID, in bounded batches using `FOR UPDATE SKIP LOCKED`. Deleting a user does
not remove quota history. No GET endpoint writes quota state.

A denied new provisioning returns HTTP 429 with stable code
`ACCOUNT_PROVISIONING_LIMIT_EXCEEDED`, a generic message and `Retry-After` in
whole seconds (rounded up). The delay is the maximum of the expiry times needed
to make each window's count strictly below its capacity, including when a lowered
capacity requires several events to expire. It does not identify other accounts
or expose which window was exhausted. New activity can change availability.
Clients must retain the Firebase session/account, display a retryable provisioning
state and retry this endpoint after the delay with a valid Firebase token. They
must not treat Firebase registration as proof of a provisioned Reals account or
loop immediate retries. Web/Android behavior still requires independent review.
Successful response bodies and existing 200/201 semantics are preserved.

Before production activation, verify the actual public ingress to Nginx/Docker
and the servlet container, not just a local MockMvc request:

1. Identify every proxy hop and ensure direct backend access is restricted.
2. Verify ingress strips/replaces client forwarding headers and configure the
   container to trust only known ingress hops through an independently reviewed
   infrastructure change. No proxy configuration is introduced by this feature.
3. From two independently known external IPs, verify distinct correct servlet
   client addresses (not a shared proxy/container address). Repeat through the
   actual TLS/production route with spoofed forwarding headers and confirm that
   the resolved address cannot be changed by the caller. Check IPv4, IPv6 and
   IPv4-mapped behavior where supported. Avoid persistent raw-IP debug logging.
4. Supply a cryptographically random Base64 secret of at least 32 bytes, identical
   across instances, and test invalid/missing-secret startup rejection when enabled.
5. Verify Flyway V48, shared PostgreSQL connectivity, clock synchronization,
   scheduling/cleanup health, 429 headers through ingress and client retry UX in
   staging before enabling `ACCOUNT_PROVISIONING_LIMIT_ENABLED`.

All instances must use identical capacities, windows, key and enabled state.
Mixed enabled/disabled deployments allow uncounted creations. Changing the key
starts fresh identifiers/counters. Increasing windows after cleanup cannot recover
deleted history; plan such changes with a staged retention horizon. Disabling
stops event collection and automatic cleanup; retained rows should be removed
through a separately reviewed retention operation if the feature is retired.
Shared Wi-Fi, VPNs, mobile CGNAT and IPv6 /64s can group unrelated users; IPv6
prefix rotation and changing networks can bypass the quota. This MVP does not
establish one account per person and does not replace ban-evasion detection.
