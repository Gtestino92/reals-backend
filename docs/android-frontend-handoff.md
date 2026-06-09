# Android Frontend Handoff

This document is for starting a new native Android project that consumes the
Reals backend. It intentionally does not duplicate the full API contract; use
`docs/openapi.yaml` for endpoints, schemas and enums.

## Files To Pass To The Android Project

Pass these files as the backend handoff package:

- `docs/openapi.yaml`: formal API contract for endpoints, request/response schemas, enums and bearer auth.
- `docs/api.md`: human-readable endpoint summary and stable error-code list.
- `docs/android-frontend-handoff.md`: Android-specific integration notes.
- `docs/user-flow.md`: product flow from profile creation through connection lifecycle.
- `docs/state-machine.md`: allowed backend state transitions.
- `docs/domain.md`: entities, enums and domain invariants.
- `docs/local-development.md`: how to run the backend locally.

Optional but useful later:

- `docs/testing.md`: CI/test strategy and smoke-check context.
- `docs/dev-deployment.md`: image/deploy shape once a dev runtime exists.

## Backend State Relevant To Android

- Backend is a stateless HTTP API.
- Auth is Firebase ID token as `Authorization: Bearer <id-token>`.
- There is no cloud dev URL yet, so Android development starts against a local backend.
- Chats are REST-only for now; do not assume WebSockets or push notifications.
- Profile photos support multipart file upload. Returned photo `url` values are renderable read URLs and may be time-limited presigned MinIO/S3/R2 URLs.
- Account deletion is recoverable during the backend recovery window. Reactivation is explicit through `POST /api/me/reactivation`; a deleted account should not be silently recreated through provisioning.
- Backend is state-machine driven. The UI should render actions from backend state instead of inventing local transitions.

## Local Base URLs

| Client runtime | Backend URL |
| --- | --- |
| Android Emulator | `http://10.0.2.2:8080` |
| Physical device on same LAN | `http://<developer-machine-lan-ip>:8080` |
| Same machine HTTP client | `http://localhost:8080` |

Do not use `localhost` from the Android Emulator; it points to the emulator
itself, not the developer machine.

## Recommended Android Stack

- Native Android with Jetpack Compose.
- Kotlin.
- Firebase Authentication Android SDK.
- Retrofit + kotlinx.serialization, or Ktor Client. Pick one and keep DTOs explicit.
- ViewModel + coroutines + StateFlow.
- DataStore for small local settings.
- Navigation Compose.

## Bootstrap From Zero

Create the Android project first, then integrate the backend in small vertical
slices:

1. Install Android Studio.
2. Create a new project with `Empty Activity`, Kotlin and Jetpack Compose.
3. Use package name `com.reals.app` unless there is a stronger naming decision.
4. Use min SDK 26 as a reasonable starting point.
5. Add a temporary debug screen/button that calls `GET /api/ping`.
6. Run the backend locally and call it from the Android Emulator through `http://10.0.2.2:8080`.
7. If local HTTP is blocked, enable cleartext traffic only for debug builds.
8. Add Firebase Auth and confirm the app can obtain a Firebase ID token.
9. Add the backend API client and call `POST /api/me/provision`.
10. Add `GET /api/me/profile` and route based on profile existence/status.

First milestone:

```text
Firebase login -> POST /api/me/provision -> GET /api/me/profile -> show profile state
```

Avoid OpenAPI client generation until this first milestone works manually. It is
more important to prove auth, local networking and backend error handling first.

## API Client Rules

- Add `Authorization: Bearer <firebase-id-token>` to authenticated API calls.
- Do not send tokens to `/api/ping`, `/actuator/health/readiness` or `/actuator/info`.
- Refresh Firebase ID tokens through the Firebase SDK before retrying a `401`.
- Treat backend timestamps as ISO-8601 strings at the API edge.
- Treat UUIDs as strings at the API edge.
- Treat enum values as exact uppercase backend strings from `docs/openapi.yaml`.
- Handle `409 Conflict` as domain state feedback, not as a generic network error.
- Do not persist profile-photo read URLs as permanent media identifiers. They may expire; refresh them by fetching the profile/photos again.
- Use `GET /api/me/home` as the current-state discovery endpoint for home/navigation after profile activation.

## First Authenticated Flow

After Firebase login:

1. Obtain Firebase ID token.
2. Call `POST /api/me/provision`.
3. Store the returned backend `UserResponse.id` in session state.
4. Call `GET /api/me/profile`.
5. If `404`, route to profile creation.
6. If profile exists but `status != ACTIVE`, route to completion/activation.
7. If profile is `ACTIVE`, route to matchmaking/home.

If provisioning returns `ACCOUNT_PENDING_DELETION`, route to a recovery screen
instead of profile creation. The user can call `POST /api/me/reactivation` while
the recovery window is open. Reactivation restores the account, keeps profile
data/photos, leaves the profile in `DRAFT` and does not reopen previous chats or
connections.

## Suggested First App Areas

Build thin vertical slices in this order:

1. Auth/provision gate.
2. Profile create/edit.
3. Photo manager with file picker, multipart upload, replace and delete.
4. Profile activation and match filters.
5. Matchmaking queue with location permission and `GET /api/me/home` polling.
6. First chat with REST polling/manual refresh.
7. First-chat decision.
8. Visual review.
9. Scheduling.
10. Second chat.

## Suggested Android Package Shape

```text
app/
  data/
    api/
      RealsApi.kt
      AuthTokenProvider.kt
      ApiError.kt
    dto/
      ProfileDtos.kt
      MatchDtos.kt
      ChatDtos.kt
      ConnectionDtos.kt
    repository/
      AuthRepository.kt
      ProfileRepository.kt
      MatchmakingRepository.kt
      ChatRepository.kt
      ConnectionRepository.kt
  domain/
    model/
    usecase/
  ui/
    auth/
    profile/
    matchmaking/
    chat/
    visual/
    scheduling/
```

Keep API DTOs separate from UI/domain models. Use `docs/openapi.yaml` as the
source of truth when writing DTOs or generating a client.

## Open Questions For Frontend Planning

- Remote testing: no deployed dev URL exists yet.
- Chat refresh: decide polling interval and manual refresh behavior.
- Client generation: decide whether to generate Retrofit/Ktor models from OpenAPI or maintain handwritten DTOs initially.
- Photo preprocessing: decide client-side crop/compression/EXIF handling before upload.
- Firebase email verification: backend currently trusts Firebase-authenticated users even if the email is not verified; enforcement is tracked as technical debt.

## Local MinIO Photo URLs

For Android Emulator testing, the backend API base URL is:

```text
http://10.0.2.2:8080
```

If the backend runs inside Docker Compose and returns MinIO presigned URLs, those
URLs must also use a host reachable from the emulator. Set the backend runtime
override to:

```text
S3_PRESIGNED_URL_ENDPOINT=http://10.0.2.2:9000
```

For same-machine browser/Bruno testing, `http://localhost:9000` is fine. The
value is environment-specific; do not hardcode it in the Android app.
