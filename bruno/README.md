# Reals Backend Happy Path

Bruno collection for local `local-nodb` or `local-postgres` manual testing.

## How To Use

1. Start the backend locally on `http://localhost:8080`.
2. Open this folder as a Bruno collection:

   ```text
   bruno/reals-backend-happy-path
   ```

3. Copy `environments/local.template.bru` to `environments/local.bru`.
4. Select the `local` environment.
5. Run one folder at a time, always in request order.

The first request generates unique test emails and a future half-hour second-chat slot. The next two requests create real `users` rows and store `userAId` and `userBId` in the active Bruno environment. The following requests use `X-Dev-User-Id` to impersonate each local user.

## Folders

- `01 Happy Path`: complete successful path through second chat and closed connection.
- `02 Not Happy Paths`: HTTP-level guardrails and invalid operations that should return 4xx responses.
- `03 Alternate Outcomes`: valid business flows that do not end in a successful second chat.
- `04 Timeout Outcomes`: local-only deadline and job-trigger flows for time-based outcomes.
- `05 Firebase Auth`: optional email/password Firebase smoke flow. Fill `firebase_api_key`, `firebase_email` and `firebase_password` in `environments/local.bru` before running it. In `local-firebase`, run `01b Local Email Verification` after provisioning if the test account uses a fictitious email, then run `00 Sign In Firebase` again to refresh `firebase_id_token` before photo upload or profile activation. `local.bru` is ignored by Git; never commit real Firebase values.
- `06 Google Auth`: optional minimal Google-origin smoke flow. It requires an externally obtained valid Firebase ID token whose `firebase.sign_in_provider` is `google.com`; set `firebase_google_id_token` and, optionally, `firebase_google_email`. It does not automate OAuth and must not use Firebase password sign-in.
- `10 Local Dev Jobs`: local-only manual triggers for periodic jobs, including user reliability cleanup.
- `12 User Reliability Debug`: local/dev-only Firebase sign-in/provision setup and read requests for `GET /api/local-dev/user-reliability/{userId}`.
- `14 Second Chat Attendance Debug`: local-only second-chat attendance, no-show and conversation-lifecycle helpers, including forced join windows, explicit joins, no-show claims, mutual-completion requests, inactivity claims, forced request expiry, status inspection and lifecycle-job execution.
- `16 AWS Dev Tooling`: hosted dev Firebase admin sign-in/provision plus authenticated `/api/local-dev/**` triggers for matchmaking, jobs, timeout mutations and reliability inspection.

Hosted `dev` keeps the `/api/local-dev/**` path for compatibility, but requires
`Authorization: Bearer {{firebase_admin_id_token}}` from an active Firebase user
whose email is listed in `BACKOFFICE_ADMIN_EMAILS`. Local profiles keep
`auth: none` for these tooling requests.

## Happy Path Covered Flow

- create two compatible profiles
- create two local test users
- add local minimum photos
- activate both profiles
- enqueue both users
- process matchmaking
- start and approve first chat
- approve visual review
- create pending connection
- force scheduling availability and run the local scheduling activation job
- confirm the second-chat slot with matching ordered proposal lists
- force the confirmed start time and trigger the scheduled second-chat availability job locally
- explicitly join the available second chat with both users, which records attendance and activates it
- exercise mutual second-chat completion, partner inactivity, read-only terminal states and lifecycle cleanup

This collection assumes local photo requirements from `application-local-nodb.yml` or `application-local-postgres.yml`: 4 required photos, at least 1 person photo and at least 1 full-body photo.
