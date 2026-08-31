# AWS Dev Tooling

Bruno requests for hosted `dev` tooling endpoints under `/api/local-dev/**`.

## Setup

1. Copy `bruno/environments/dev.template.bru` to `bruno/environments/dev.local.bru`.
2. Fill `firebase_api_key` and `firebase_admin_password`.
3. Keep `firebase_admin_email` as `gtestino1992@gmail.com` unless the dev allowlist changes.
4. Select the copied environment in Bruno.
5. Run:
   - `00 Admin Firebase Sign In`
   - `01 Provision Admin User`
   - `02 Get Admin Me`

The signed-in Firebase user must be active in the backend, have a verified Firebase email, and be listed in `BACKOFFICE_ADMIN_EMAILS`; otherwise `/api/local-dev/**` returns `403`.

## Common Requests

- `10 Process Matchmaking`: `POST /api/local-dev/matchmaking/process?maxPairsPerRun={{maxPairsPerRun}}`.
- `11 Reset Pair History`: `POST /api/local-dev/pair-history/reset`.
- `20` through `32`: trigger scheduler-equivalent jobs manually.
- `40` through `51`: move specific deadlines into the past for deterministic timeout checks.
- `60 Get User Reliability`: inspect reliability score details for `{{devUserId}}`.
- `70 List Profile Photo Review Queue`: list `/api/admin/profile-photos/review` and save the first review item.
- `71 Approve Profile Photo Review` / `72 Reject Profile Photo Review`: resolve the saved review item using its `photoVersion`.

Do not commit real passwords, ID tokens, refresh tokens or copied dev environment files.
