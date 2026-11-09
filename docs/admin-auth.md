# Administrative authentication and operator runbook

Backend only. There is one administrator. No enrollment, recovery, QR or secret REST API exists.
Normal USER access and auth-origin policy are unchanged: EMAIL_PASSWORD accounts accept both
password and Google sign-in. No Firebase custom claims are used.

## Authorization and trust boundaries

Only the Firebase token filter can add effective ROLE_ADMIN. It requires a currently ACTIVE
local user with an exact UID match, both configured UID and verified token-email allowlists,
google.com sign-in, a verified allowlisted Firebase Web App ID, and a valid stored grant.
Effective bans block issuance and use. Local stored email is never a fallback.
Admin auth and requests carrying grants bypass the successful Firebase revocation cache:
Firebase verifyIdToken(token, true) executes on every request and failures deny access.
Infrastructure failures never create authority. Ordinary token verification retains its cache.

App Check is verified before Firebase authentication. Only the verifier's request attribute
is read, never a header claiming an App ID or raw JWT claims. In DISABLED mode admin access
is impossible. In MONITOR mode only successfully verified tokens can satisfy admin policy;
missing, invalid or unavailable verification cannot. Production still requires ENFORCED mode.
The general App Check allowed-app-ids must include the Web App ID as well as the Android IDs.
BACKOFFICE_ALLOWED_WEB_APP_IDS accepts only Firebase `1:<number>:web:<id>` identifiers.
A Web App ID identifies an application, not one computer or physical device.

All /api/admin/** routes use the same boundary, including moderation, appeals and diagnostics.
Actuator info/metrics and dev administrative /api/local-dev/** are explicitly denied because
those paths are excluded from App Check. Production local tooling remains denied. Existing
local-only tooling stays local-only and never obtains ADMIN. No frontend accommodation bypass exists.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| BACKOFFICE_ADMIN_UIDS | empty | Exactly one current Firebase UID; comma syntax, at most one entry |
| BACKOFFICE_ADMIN_EMAILS | empty | Exactly one verified token email, case normalized |
| BACKOFFICE_ALLOWED_WEB_APP_IDS | empty | Explicit Firebase Web App IDs |
| ADMIN_MFA_TOTP_SECRET | empty | Canonical uppercase unpadded Base32, 20?80 decoded bytes; generate randomly |
| ADMIN_MFA_SECRET_VERSION | empty | Required with secret; 1?64 letters, digits, dot, underscore or hyphen |
| ADMIN_MFA_GRANT_TTL_SECONDS | 900 | Absolute lifetime, 1?900 seconds |
| ADMIN_MFA_MAX_FAILED_ATTEMPTS | 5 | 1?20 failures per window |
| ADMIN_MFA_FAILURE_WINDOW_SECONDS | 600 | Fixed attempt window, 1?86400 seconds |
| ADMIN_MFA_LOCKOUT_SECONDS | 900 | Temporary lockout, 1?86400 seconds |
| ADMIN_MFA_MAX_AUTH_AGE_SECONDS | 300 | Google auth age for issuance, 1?300 seconds |

Empty allowlists or secret disable administration without changing USER behavior. Invalid
nonempty secret, version, numeric limits, multiple identities or non-Web IDs fail startup.
Secrets are not data classes and are never included in validation messages.

## Offline initial setup

1. In Firebase Authentication, locate the existing administrator account and copy its current
   UID from the account details (or use an authorized Admin SDK lookup). Verify that UID matches
   users.firebase_uid for the existing active Reals account. Do not change authOrigin or provision
   an administrator implicitly. Verify the email and link Google to that same Firebase identity.
2. Configure both BACKOFFICE allowlists through protected server configuration. Use no real
   identity values in source control. Register/configure Web App Check separately in Firebase;
   allow the verified Web App ID in both general and backoffice App ID settings. This task does
   not change Firebase Console or infrastructure.
3. On a trusted offline terminal, with terminal recording disabled, generate a secret explicitly:
   `python -c "import secrets,base64; print(base64.b32encode(secrets.token_bytes(20)).decode())"`.
   This operator command prints sensitive material: never use CI, logs, shared shell history,
   screenshots or online QR generators. No helper runs at application startup or during tests.
4. In Google Authenticator choose Add account / Enter a setup key, enter the secret and choose
   Time based. Parameters are HMAC-SHA1, six digits, 30 seconds. No QR URI is needed.
5. Inject the same secret and a new unique factor version through the deployment's existing
   protected secret configuration, without committing files. Keep clocks synchronized.
6. With recent Google authentication (<300 seconds), send a verified Web App Check token and
   call status then verify. Store the returned grant only in Web memory. Test an admin request,
   revoke it, then confirm access is rejected. A second use of that code must fail.

## Replay, failures, grants and races

java-otp 1.0.0 performs RFC 6238 generation (SHA1, six digits, 30 seconds); comparison uses
MessageDigest.isEqual. Allowed steps are current-1/current/current+1. A match must be greater
than last_accepted_step; a successful future step consumes that step and all earlier steps.
The clock is injected. Format failures also consume the persistent failed-attempt budget.
Five failures within [windowStart, windowStart+600s) lock for 900 seconds by default.
At lockout equality verification is allowed; failure-window equality starts a new window.
At auth_time+300s issuance is rejected. Future auth_time is rejected.

All issuance/revocation writes lock users first, then access MFA state, then grants. The user
row serializes initial state creation across instances. TOTP consumption and grant insertion
commit atomically. Failures return typed results, commit, and only then become HTTP errors.
A repeated successful request cannot mint another grant with the consumed step. Revoke cannot
unrevoke a grant; new issuance requires a fresh step. Reads do not create state or consume codes.
A request already authorized concurrently with revocation may complete; subsequent checks deny.
Revoke at/after expiry is a no-op, preserving the already-due expiry outcome.

Tokens use 32 SecureRandom bytes, URL-safe Base64 without padding; only SHA-256 hashes persist.
Grants bind local user, UID, GOOGLE provider, verified auth_time, Web App ID and factor fingerprint.
ID-token refresh preserves auth_time and never extends the absolute grant deadline. Valid only
while now < expiresAt; equality expires. Provider, identity, ban, app or factor changes deny use.
The factor fingerprint hashes the configured version and high-entropy secret; no TOTP secret
or provisioning material is stored in PostgreSQL. Operators must never use human-chosen secrets.
Hourly cleanup deletes at most 100 expired rows ordered by expiry and hash. It acquires no user
lock and never restores access. Audit history follows the existing audit retention policy.

Pre-auth network-origin throttling adds 10 attempts per 600 seconds for verify, using the existing
remoteAddr-based limiter. It is defense in depth only; verify trusted proxy/IP attribution separately.
PostgreSQL attempts/lockouts/replay state survive restarts and are shared by backend instances.

## Recovery, revocation and rotation

Browser logout alone does not revoke a grant. Call revoke explicitly. For an emergency, stop
all backend instances or remove the administrator UID/email allowlist and roll out consistently
before recovery. Revoke outstanding grants using an authorized PostgreSQL transaction:

```sql
BEGIN;
SELECT id FROM users WHERE id = :admin_user_id FOR UPDATE;
WITH revoked AS (
    UPDATE admin_access_grants SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id = :admin_user_id AND revoked_at IS NULL
      AND expires_at > CURRENT_TIMESTAMP RETURNING user_id
)
INSERT INTO audit_events (id, event_type, aggregate_type, aggregate_id, target_user_id, metadata_json, created_at)
SELECT gen_random_uuid(), 'ADMIN_GRANT_REVOKED', 'USER', :admin_user_id,
       :admin_user_id, '{"operator":true}', CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM revoked);
COMMIT;
```

Do not delete MFA state or reset the accepted step to regain access. Generate a NEW random secret
and NEW unique version offline; register the new authenticator; deploy the same configuration to
all instances while administration is disabled. Old-config instances must not serve admin traffic
alongside new ones. Both secret and version changes invalidate all old grants immediately on
instances using the new configuration. On first eligible verification after rotation, state records
the new factor fingerprint and audits ADMIN_MFA_FACTOR_CHANGED, retaining failure/lockout state
and setting the replay floor to max(previous floor, current step+1). Wait until a later unused step
and any lockout ends before verifying. Reverting to an old secret/version is forbidden.

Lost authenticator recovery is the same operator-controlled rotation process. Account identity
changes require allowlist replacement and revocation before enabling the new identity. Bad
configuration is repaired through protected configuration and restart; never disable App Check,
patch ROLE_ADMIN or clear attempt state to troubleshoot. Allowlist removal disables new and old
grants. Empty secret disables administration. Preserve the state table across backups/restores;
a stale restore requires offline rotation before re-enabling administration.

The SQL procedure adds one existing-table audit event only when grants changed; retain the operator
change record in the operational audit system as well. Factor changes are recorded through AuditEventService
on synchronization, without secrets. Application success, grant issuance, first failure per window,
threshold lockout and first revocation use existing audit_events. Repeated lockout/revoke calls do
not flood audit history. No OTP, token/hash, headers, email, IP or request bodies are event metadata.

## Validation and local testing

Run exact AdminTotpTest, FirebaseTokenFilterTest, FirebaseTokenAuthenticationVerifierTest and
AdminMfaPostgresIT classes. PostgreSQL integration uses Testcontainers, committed fixtures,
NOT_SUPPORTED test transactions and independent request/service transactions. It installs mock
Firebase and App Check verifiers only inside a test configuration; production contains no fake
header, test grant, debug secret or local MFA bypass. For manual local-firebase testing, use a
separate Firebase project with actual Web App Check verification and test credentials. Do not
weaken production enforcement. Real Firebase revocation, Web attestation and operator rollout
remain manual checks requiring those external environments.

Library reference: [java-otp API and defaults](https://github.com/jchambers/java-otp).

## Focused validation command

Executed on this feature branch (125 tests, zero failures/errors/skips, including 11 PostgreSQL tests):

```powershell
./mvnw.cmd '-Dapi.version=1.44' '-Dtest=AdminTotpTest,FirebaseTokenFilterTest,FirebaseTokenAuthenticationVerifierTest,AdminMfaPostgresIT,DevAdminToolingControllerExposureDevTest,FirebaseAppCheckFilterTest,FirebaseAppCheckFilterOrderTest,RateLimitFilterTest,AdminSafetyReportControllerIntegrationTest,AdminProfilePhotoModerationControllerIntegrationTest,AdminDiagnosticsControllerIntegrationTest,PenaltyAppealControllerIntegrationTest,AuditEventServiceIntegrationTest,SafetyAuditSideEffectsIntegrationTest' test
```

The Docker API flag is local to this test process. Unit/controller regressions use the existing
test profile; AdminMfaPostgresIT uses real PostgreSQL 16 with Flyway and Hibernate validation.
This does not prove a real Firebase/Web App Check deployment or a rolling multi-instance secret
rollout. Those external checks and authenticator setup remain operator/manual work.

After the final expiry-precedence and stale-cleanup refinements, the exact PostgreSQL class
was rerun successfully (11 tests, zero failures/errors/skips):

```powershell
./mvnw.cmd -DskipTests compile
./mvnw.cmd '-Dapi.version=1.44' '-Dtest=AdminMfaPostgresIT' test
git diff --check
```

Compilation succeeded. OpenAPI YAML and all internal references resolved; added/modified text
passed the UTF-8/mojibake inspection. The unrestricted Maven suite was not run.
