# Hosted DEV administrator tooling

Select your existing Bruno environment `dev`, or any environment based on
`bruno/environments/dev.template.bru`. Set the trusted DEV HTTPS `baseUrl`,
`firebase_api_key`, `firebase_project_number`, `firebase_admin_web_app_id`,
`firebase_admin_expected_uid` and `firebase_admin_email`. Use the Web App ID
in both backend allowlists. Remove legacy admin credentials and request-level
overrides from your local collection. Use literal configuration values.

## Start Google sign-in

1. In Google Cloud, select the project backing DEV Firebase. In **Google Auth Platform / Branding and Audience**, configure the consent screen. For an external app in Testing, add the existing administrator as a test user. In **Clients / Create client / Desktop app**, create or select the desktop client and download its JSON outside Git. The helper uses Google's system-browser loopback redirect with an ephemeral port. [Google desktop OAuth setup](https://developers.google.com/identity/protocols/oauth2/native-app)
2. In DEV **Firebase Authentication / Sign-in method / Google**, enable the provider. Keep its existing Web client configuration. For a Desktop client from another project, add its full OAuth client ID under **Safelist client IDs from external projects** and save. If Firebase rejects the ID-token audience, verify the exact Desktop client ID in the Google provider's allowed client IDs. The Web App ID used for App Check is a separate identifier. Verify that the expected Firebase UID already has this Google account linked and the verified email matches the backend allowlist. [Firebase client safelisting](https://support.google.com/firebase/answer/6401008?hl=en) and [Identity Platform Google provider](https://docs.cloud.google.com/identity-platform/docs/web/google)
3. In PowerShell at the repository root, use the downloaded JSON path in the last command. The virtual environment lives outside the checkout.

```powershell
$venv = Join-Path $env:LOCALAPPDATA "reals-bruno-auth-venv"
py -3 -m venv $venv
$python = Join-Path $venv "Scripts/python.exe"
& $python -m pip install "google-auth-oauthlib>=1.2.2,<2"
& $python scripts/bruno_dev_google_login.py "$env:USERPROFILE/Downloads/dev-desktop-client.json"
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
   runtime credentials on HTTP 204. It can send an expired grant while Firebase
   and App Check are valid. If only App Check expired, repeat 00b in the original
   configuration and retry 99. A failed revoke leaves the grant available for retry.
6. **98 Clear Local Admin Session** discards local credentials even with expired
   authentication or a different environment selected. Its pre-request script
   clears the session before a credential-free loopback acknowledgement. If the
   helper is stopped, the acknowledgement can show a connection error. Local
   cleanup does not revoke a server grant. An outstanding grant expires at its
   server deadline. Stop the helper with Ctrl+C.

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

The older [Google discovery contract](https://www.googleapis.com/discovery/v1/apis/identitytoolkit/v3/rest)
explicitly specifies existing-user-only behavior for this flag. The current SDK still
sends it for OAuth reauthentication. The exchange has no sign-up fallback. A UID
check or an `isNewUser` check after exchange alone cannot prevent creation.
Local tests verify the outgoing flag and rejection paths. Hosted enforcement for
an unknown Google identity was not exercised.

## Scopes and downstream requests

Configuration and fixture selectors belong to the selected environment. Session
ownership covers the backend URL, API key, project number, Web App ID, expected UID and email. A configuration change blocks reuse. Starting 00 or 00b under a new configuration discards the old local session. Revoke first if still possible. Session credentials use dedicated `devAdmin*` runtime keys. In 4.3.0, `getEnvVar`
reads the environment and `getVar` reads runtime. Interpolation resolves
global, collection, environment, folder, request, OAuth, runtime and prompt
values in ascending precedence. Keep `devAdmin*` out of configuration scopes.
[Bruno interpolation](https://github.com/usebruno/bruno/blob/v4.3.0/packages/bruno-electron/src/ipc/network/interpolate-vars.js)

Requests 10–11 control matchmaking, 20–34 execute jobs, 39–52 change fixture
deadlines, and 70–72 list or moderate profile photos. All use the same session.
03 and 04 intentionally probe missing and invalid App Check in ENFORCED mode.
