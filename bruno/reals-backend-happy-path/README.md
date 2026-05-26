# Reals Backend Happy Path

Bruno collection for local `local-nodb` manual testing.

## How To Use

1. Start the backend locally on `http://localhost:8080`.
2. Open this folder as a Bruno collection:

   ```text
   bruno/reals-backend-happy-path
   ```

3. Select the `local` environment.
4. Run the requests in `01 Happy Path` in order.

The first request generates unique test emails and a future second-chat slot. The next two requests create real `users` rows and store `userAId` and `userBId` in the active Bruno environment. The following requests use `X-Dev-User-Id` to impersonate each local user.

## Covered Flow

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
