# Hosted DEV administrator tooling

Select your existing Bruno environment `dev`, or any environment based on
`bruno/environments/dev.template.bru`. Set the trusted DEV HTTPS `baseUrl`,
`firebase_api_key`, `firebase_project_number`, `firebase_admin_web_app_id`,
`firebase_admin_expected_uid` and `firebase_admin_email`. Use the Web App ID
in both backend allowlists. Remove legacy admin credentials and request-level
overrides from your local collection. Use literal configuration values.

## Start Google sign-in

Use a trusted workstation with a browser and a Google OAuth **Desktop app**
client JSON accepted by the DEV Firebase Google provider. Keep the client file
outside Git. From the repository root, with a Python virtual environment active:

```sh
python -m pip install "google-auth-oauthlib>=1.2.2,<2"
python scripts/bruno_dev_google_login.py "/path/to/dev-desktop-client.json"
```

Leave the helper running. Each login opens Google's account selector and uses
a fresh loopback callback, state and PKCE. It requests online access and keeps
credentials in memory. Configure Bruno to bypass proxies for 127.0.0.1.
Bruno Desktop 4.3.0 saves credentials from its built-in OAuth authorization
code flow to its Electron OAuth store. [Bruno OAuth implementation](https://github.com/usebruno/bruno/blob/v4.3.0/packages/bruno-electron/src/utils/oauth2.js)

## Request order

Send requests individually. Running the whole folder executes DEV mutations.

1. **00b Exchange Firebase App Check Debug Token** prompts for the registered
   Web App debug secret. The script reports when App Check is ready.
2. **00 Google Admin Sign In** opens the browser. Select the existing verified
   administrator. Bruno exchanges the Google credential with Firebase and checks
   the resulting UID, email and Google provider.
3. Optionally send **05 Admin Status**. Expect `configured: true` and
   `grantValid: false` before TOTP.
4. **06 Verify Admin TOTP** prompts for the current authenticator code. Run it
   within the Google freshness window (five minutes by default). **05** should
   now return `grantValid: true`. **60 Get User Reliability** is a read-only
   check with a DEV fixture user. **02** reads your identity.
5. **99 Revoke Admin Grant** revokes the current grant and clears the folder's
   runtime credentials on HTTP 204. Stop the helper with Ctrl+C.

Repeat **00b** for expired App Check. For an expired grant or Firebase session,
repeat **00 → 06** with a fresh code. Honor `Retry-After` on lockout. Revoke
an active grant before starting another session. Avoid saving responses or
exporting timelines containing credentials.

The previous password flow returned a password-provider session. Commit
`dfbef8d` restored Google-only access, Web App Check and TOTP and replaced
password sign-in with external Firebase token lookup. The current exchange
uses the Firebase SDK's existing-account reauthentication flag
`autoCreate: false`. Google marks this flag deprecated in the REST schema.
The backend independently verifies administrator eligibility, Google context,
allowed Web App ID and grant. [Firebase SDK reauthentication](https://github.com/firebase/firebase-js-sdk/blob/main/packages/auth/src/core/credentials/oauth.ts) and [Identity Platform exchange](https://docs.cloud.google.com/identity-platform/docs/reference/rest/v1/accounts/signInWithIdp).

## Scopes and downstream requests

Configuration and fixture selectors belong to the selected environment. Session
credentials use dedicated `devAdmin*` runtime keys. In 4.3.0, `getEnvVar`
reads the environment and `getVar` reads runtime. Interpolation resolves
global, collection, environment, folder, request, OAuth, runtime and prompt
values in ascending precedence. Keep `devAdmin*` out of configuration scopes.
[Bruno interpolation](https://github.com/usebruno/bruno/blob/v4.3.0/packages/bruno-electron/src/ipc/network/interpolate-vars.js)

Requests 10–11 control matchmaking, 20–34 execute jobs, 39–52 change fixture
deadlines, and 70–72 list or moderate profile photos. All use the same session.
03 and 04 intentionally probe missing and invalid App Check in ENFORCED mode.
