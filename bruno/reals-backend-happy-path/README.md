# Reals Backend Happy Path

Bruno collection for local `local-nodb` manual testing.

## How To Use

1. Start the backend locally on `http://localhost:8080`.
2. Open this folder as a Bruno collection:

   ```text
   bruno/reals-backend-happy-path
   ```

3. Select the `local` environment.
4. Run one folder at a time, always in request order.

The first request generates unique test emails and a future second-chat slot. The next two requests create real `users` rows and store `userAId` and `userBId` in the active Bruno environment. The following requests use `X-Dev-User-Id` to impersonate each local user.

## Folders

- `01 Happy Path`: complete successful path through second chat and closed connection.
- `02 Not Happy Paths`: HTTP-level guardrails and invalid operations that should return 4xx responses.
- `03 Alternate Outcomes`: valid business flows that do not end in a successful second chat.
- `04 Timeout Outcomes`: local/dev-only deadline and job-trigger flows for time-based outcomes.

## Happy Path Covered Flow

- create two compatible profiles
- create two local test users
- add local minimum photos
- activate both profiles
- enqueue both users
- process matchmaking
- start and approve first chat
- approve visual review
- create connection
- confirm the second-chat slot with matching proposals
- start second chat
- close connection

This collection assumes local photo requirements from `application-local-nodb.yml`: 4 required photos, at least 1 person photo and at least 1 full-body photo.
