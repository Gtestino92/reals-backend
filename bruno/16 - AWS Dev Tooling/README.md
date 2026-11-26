# Hosted DEV administrator tooling

In Railway DEV, use the dev execution profile and enable both
`DEV_REMOTE_TOOLING_ENABLED=true` and `DEV_REMOTE_TOOLING_PASSWORD_AUTH_ENABLED=true`.
The password option defaults to false. Use the existing backend administrator UID,
verified email, Web App ID allowlists and TOTP configuration. [Deployment settings](../../docs/dev-deployment.md#railway-dev-remote-tooling)

In the DEV Firebase console, enable **Authentication / Sign-in method / Email/Password**.
Check that the allowlisted UID already owns the verified email and a password credential.
If the account currently has only Google, its owner must explicitly link an
EmailAuthProvider credential while signed in to that same UID through a trusted
Firebase client. Verify the UID afterward. Bruno only calls signInWithPassword.
[Firebase provider linking](https://firebase.google.com/docs/auth/web/account-linking)

## Request order

Select the existing Bruno environment `dev` or another personal environment based on
`dev.template.bru`. Set the trusted HTTPS `baseUrl`, `firebase_api_key`,
`firebase_project_number`, `firebase_admin_web_app_id`, `firebase_admin_email`
and `firebase_admin_expected_uid`. In the desktop environment editor mark
`firebase_admin_password` and `firebase_app_check_debug_token` as **Secret** before
entering their values. Use the debug secret registered for the allowlisted Web app.
Keep credentials out of request files, exported responses and shared timelines.

Send individual requests. Running the whole folder includes DEV mutations.

1. **00b Exchange Firebase App Check Debug Token** obtains the Web App credential.
2. **00 Firebase Admin Password Sign In** obtains a fresh Firebase ID token.
3. **06 Verify Admin TOTP** prompts for the current authenticator code. Optional **05 Admin Status** shows grant validity before and after verification.
4. **60 Get User Reliability** reads a configured fixture. Requests **10-52** run matchmaking, jobs or deadline changes. **70-72** are ordinary backoffice moderation and require Google backoffice authentication; a password tooling grant cannot run them. Ordinary user endpoints such as **02** still enforce account origin.
5. **99 Revoke Admin Grant** revokes the grant while the identity and App Check are valid, then clears the local runtime session. **98 Clear Local Admin Session** clears local credentials before a credential-free backend ping even if authentication has expired. It does not revoke an outstanding server grant.

Repeat 00b for expired App Check, or 00 and 06 for a fresh password session and
grant. A new grant requires recent authentication (default age strictly under
300 seconds) and a fresh TOTP code. Honor Retry-After on lockout.
Session credentials stay in dedicated `devAdmin*` runtime variables. Configuration
and fixture IDs belong in the selected environment. Changing backend URL, Firebase
project, API key, Web App ID, UID or email prevents reuse. Revoke a live grant
before switching environments.
