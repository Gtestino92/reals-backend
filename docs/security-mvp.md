# MVP Security Hygiene

This document captures the minimum security hygiene expected for Reals MVP
backend, docs and Bruno usage. It is not a substitute for production hardening.

## Secrets In Source Control

Do not commit real secrets or credentials.

Never commit:

- Firebase Web API keys.
- Firebase test user passwords.
- Firebase ID tokens.
- Firebase service-account JSON.
- Deployment secrets.
- S3/R2/MinIO access keys or secret keys for shared environments.

Tracked Bruno templates must use placeholders only, for example:

- `firebase_api_key: paste-firebase-web-api-key-here`
- `firebase_email: paste-firebase-email-here`
- `firebase_password: paste-firebase-password-here`
- `firebase_id_token: paste-token-here`
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

## Sensitive Log Policy

Do not log:

- Firebase ID tokens.
- `Authorization` headers.
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
