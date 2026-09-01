# AWS Dev Tooling

Bruno requests for hosted `dev` setup, `/api/local-dev/**` tooling endpoints, and admin moderation helpers.

## Setup

1. Copy `bruno/environments/dev.template.bru` to `bruno/environments/dev.local.bru`.
2. Fill the existing `firebase_api_key`, `firebase_admin_email`, and `firebase_admin_password` values.
3. Fill `firebase_project_number`, `firebase_app_id`, and `firebase_app_check_debug_token`.
   - `firebase_project_number` is the Firebase numeric project number.
   - `firebase_app_id` is the Firebase Android DEV App ID, such as `1:...:android:...`; it is not the Android package name.
   - `firebase_app_check_debug_token` is the already registered debug token secret. It is sensitive and must never be committed.
4. Select the copied environment in Bruno.
5. Run:
   - `00b Exchange Firebase App Check Debug Token` to store `firebase_app_check_token`.
   - `00 Admin Firebase Sign In` to store `firebase_admin_id_token`.
   - `01 Provision Admin User`.
   - `02 Get Admin Me`.

`firebase_app_check_token` is a short-lived App Check JWT produced by Firebase's debug-token exchange endpoint. If it expires, rerun `00b Exchange Firebase App Check Debug Token`; do not generate or register a new debug token secret only because the JWT expired.

The signed-in Firebase user must be active in the backend, have a verified Firebase email, and be listed in `BACKOFFICE_ADMIN_EMAILS`; otherwise `/api/local-dev/**` returns `403`.

## Common Requests

- `10 Process Matchmaking`: `POST /api/local-dev/matchmaking/process?maxPairsPerRun={{maxPairsPerRun}}`.
- `11 Reset Pair History`: `POST /api/local-dev/pair-history/reset`.
- `20` through `32`: trigger scheduler-equivalent jobs manually.
- `40` through `51`: move specific deadlines into the past for deterministic timeout checks.
- `60 Get User Reliability`: inspect reliability score details for `{{devUserId}}`.
- `70 List Profile Photo Review Queue`: list `/api/admin/profile-photos/review` and save the first review item.
- `71 Approve Profile Photo Review` / `72 Reject Profile Photo Review`: resolve the saved review item using its `photoVersion`.

Do not commit real passwords, ID tokens, refresh tokens, App Check debug token secrets, App Check JWTs, or copied dev environment files.
