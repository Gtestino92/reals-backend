# Web Integration Handoff

Backend only; no Web code or Firebase Console changes were made.

Use the existing Firebase account, Google sign-in and recent reauthentication (<5 minutes).
EMAIL_PASSWORD-origin accounts can continue using both providers, but only Google can use admin access.
Initialize Firebase Web App Check separately; send a real verified token whose Web App ID is in
both backend App Check and BACKOFFICE_ALLOWED_WEB_APP_IDS allowlists. Android IDs cannot authorize
administration. A Web App ID does not identify one physical device. No bypass exists for unfinished UX.

Send `Authorization: Bearer <firebase-id-token>` and `X-Firebase-AppCheck: <app-check-token>`
on every admin-auth and privileged request. Send `X-Reals-Admin-Grant: <grant>` on privileged calls.

| Endpoint | Body | Result |
| --- | --- | --- |
| GET /api/admin-auth/status | None; optional grant header | 200 {configured:boolean, grantValid:boolean} |
| POST /api/admin-auth/verify | {"code":"012345"} (six-digit string) | 200 {grant:string, expiresAt:ISO-8601 UTC} |
| POST /api/admin-auth/revoke | None; current grant header | 204 empty; idempotent |

Keep grants exclusively in memory. Do not place them in URLs, cookies, local/session storage,
analytics or logs. Default lifetime is 15 minutes absolute; expiry equality is invalid. Refreshing
Firebase ID tokens neither renews nor extends grants. Bindings include Firebase auth_time, provider,
UID, local user, verified Web App ID and factor. A changed auth context needs a new TOTP. Reauthentication
is needed when issuance authentication age reaches five minutes. A consumed time step cannot be reused.

Before logout, explicitly call revoke, then discard the grant even if the network fails. Logout
alone does not revoke the backend grant; expiration remains a safeguard. After expiry/revocation,
clear memory, reauthenticate with Google if needed and request a fresh TOTP. No enrollment/recovery UI.
All MFA/grant responses are Cache-Control: no-store. Do not cache them in a service worker.

Errors use {code,error,message}: existing authentication 401 codes (missing bearer, INVALID_TOKEN,
unsupported provider/auth method), and App Check 401 MISSING_APP_CHECK_TOKEN/INVALID_APP_CHECK_TOKEN
or 503 APP_CHECK_VERIFICATION_UNAVAILABLE. A security-chain 401 may be AUTHENTICATION_REQUIRED.
403 ADMIN_ACCESS_DENIED means identity/app/auth context is ineligible; ADMIN_MFA_NOT_CONFIGURED
requires operator action. Privileged 403 codes include ADMIN_MFA_REQUIRED, ADMIN_GRANT_EXPIRED,
ADMIN_GRANT_REVOKED. Verify 400 ADMIN_MFA_INVALID_CODE includes replay and malformed codes; do not
retry automatically. 429 ADMIN_MFA_RATE_LIMITED includes integer Retry-After seconds; honor it.
Network throttling can return RATE_LIMIT_EXCEEDED. Existing deletion/ban restrictions still apply.
Status returns grantValid=false for unusable grants and never materializes state.

Actuator info/metrics and dev admin tooling are denied pending equivalent verified App Check support.
Deferred Web tasks: App Check provider/setup and token forwarding, Google UX and reauthentication,
TOTP form, status/error UI, memory-only grant handling, expiry/revocation/logout UX and real browser
validation. Full contract: [API](commons/api.md#administrative-authentication), [OpenAPI](commons/openapi.yaml).

Administrative verification infrastructure failures also return 503 ADMIN_AUTHENTICATION_UNAVAILABLE, without raw exception details; retry with backoff.
