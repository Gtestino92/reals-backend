# Hosted Dev Tooling

This folder keeps its existing path for Bruno references. It exercises hosted
`dev` tooling at `https://reals-backend-dev.up.railway.app` and includes
profile photo moderation requests. See the [Railway Dev setup](../../docs/dev-deployment.md#railway-dev-remote-tooling).

## Personal Bruno environment

1. Copy `bruno/environments/dev.template.bru` to the ignored
   `bruno/environments/dev.local.bru` and select it in Bruno.
2. Set `baseUrl`, `firebase_api_key`, `firebase_project_number`,
   `firebase_admin_expected_uid` and `firebase_admin_email` for the Dev project.
3. Set `firebase_admin_web_app_id` to the Firebase Web App ID listed in both
   backend App Check allowlists. Set `firebase_app_check_debug_token` to an
   existing debug secret registered for that Web app. Keep it in a local Bruno
   secret variable.
4. Complete a fresh Google sign-in through a trusted Firebase client for the
   existing administrator. Obtain the Firebase ID token through that client's
   supported credential handoff, such as Firebase Auth's `User.getIdToken()`
   in a trusted Dev client. Supply it as the local Bruno secret
   `firebase_admin_google_id_token`.

A Google OAuth ID token or access token comes from Google. A Firebase ID token
comes from Firebase Auth after sign-in and is the bearer credential accepted by
Reals. The generic IdP exchange under `06-google-auth` can sign up an unknown
identity. This admin workflow uses Firebase's read-only account lookup and
checks the expected UID, Google provider and verified email before using the
session. The backend independently verifies the Google session, account and
Web App Check identity. [Firebase Auth REST API](https://firebase.google.com/docs/reference/rest/auth)

## Request order

Send these requests individually in the Bruno desktop app. The TOTP request
uses a one-time input prompt. Running the whole folder would include mutations.

1. **00b Exchange Firebase App Check Debug Token** saves the short-lived
   `firebase_app_check_token`. Repeat this exchange when the JWT expires.
2. **00 Validate Firebase Google ID Token** saves `firebase_admin_id_token` and
   `firebase_admin_uid` after the read-only lookup.
3. **05 Admin Status** should return `configured: true` and
   `grantValid: false` for a fresh session.
4. **06 Verify Admin TOTP** prompts for the current authenticator code. Run it
   within the configured Google sign-in freshness window, five minutes by
   default. Bruno captures `firebase_admin_grant` in memory.
5. **05 Admin Status** again should return `grantValid: true`. **01 Provision
   Admin User** is optional for verifying idempotent provisioning of the
   existing account. **02 Get Admin Me** confirms its backend identity.
6. **60 Get User Reliability** is a read-only tooling check with a Dev fixture
   user ID. For the job checks, send **10 Process Matchmaking** and then
   **20 Run Scheduling Activation Job** against prepared Dev fixtures.
7. **99 Revoke Admin Grant** at the end of the session. Rerun **05 Admin
   Status** and expect `grantValid: false`.

**03 App Check Missing Token** and **04 App Check Invalid Token** assert
the existing `/api/me` errors in `ENFORCED` mode. Hosted privileged requests
also require verified App Check in `MONITOR` mode.

Grants expire at the server's `expiresAt` and are bound to the current Google
authentication time and Web App ID. Complete a fresh Google sign-in and repeat
steps 2 through 5 after a session change or when a new grant is needed. Use a fresh
authenticator code and honor `Retry-After` on lockout. Keep the TOTP secret in
the authenticator and Railway secret configuration. Avoid exporting request
history or sharing sensitive response bodies.

## Other requests

- **11** resets pair history for the supplied users.
- **21 through 34** trigger other jobs. **39 through 52** adjust fixture deadlines for visual
  review, scheduling, chat, second-chat resolution and penalty testing.
- **70 through 72** list and resolve pending profile photo moderation. Use the captured
  review item and its `photoVersion`.

The collection captures credentials in runtime variables. Keep all real
credentials out of tracked files.
