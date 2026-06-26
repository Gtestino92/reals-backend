# TECH_DEBT_MVP

This file lists technical debt, cleanup tasks and product decisions that should be resolved for a first usable MVP/beta version of Reals.

MVP scope here means: enough to run the core product flow end-to-end with controlled users, a dev/staging backend, Android APK distribution, and known temporary shortcuts clearly documented.

Do not implement these implicitly while working on unrelated tasks.

---

## 1. Product decisions to close before MVP

### 1.1 First-chat guided questions

Decision pending:
- Whether first-chat guided questions/conversation starters are required for MVP.
- Whether the question set belongs fully to the frontend, fully to the backend, or backend-provided with frontend rendering.

MVP recommendation:
- Keep the first implementation simple.
- Prefer backend-owned predefined question IDs/texts if questions affect product analytics or future experimentation.
- Prefer frontend-owned static copy only if the set is temporary and not important for backend decisions.

Acceptance criteria:
- First chat can start with a predictable prompt or question.
- Users can continue chatting without being blocked by the question mechanic.
- The decision is documented so future chats do not fork behavior across app versions.


## 2. Backend MVP cleanup

### 2.2 Profile photo validation MVP shortcut

Status: implemented for multipart upload and replace-file flows.

For MVP, image validation is intentionally permissive.

MVP upload behavior:
1. Backend performs technical validation:
   - file exists;
   - file is not empty;
   - file size is within configured limits;
   - content type is allowed;
   - storage succeeds;
   - file can be decoded as an image when JVM runtime support is available for that format;
   - dimensions are within configured technical bounds.
2. If technical validation fails:
   - reject the upload;
   - do not create a usable profile photo.
3. If technical validation passes:
   - store the photo;
   - set `validation_status = VALIDATED`;
   - set `isPersonPhoto = true`;
   - set `isFullBody = true`.

MVP meaning:
- `VALIDATED` means the photo passed technical upload validation and may be used in the visible profile.
- `VALIDATED` does not mean real moderation happened.
- `isPersonPhoto` and `isFullBody` are temporary permissive defaults.

Acceptance criteria:
- Android multipart upload does not need to send temporary semantic flags.
- Profile activation can be tested end-to-end.
- The shortcut remains documented and easy to remove later.

### 2.2.1 Future profile photo ordering endpoint

MVP decision:
- Multipart profile photo upload remains slot-based and requires `position`.
- Do not implement drag-and-drop reordering as part of the MVP validation shortcut.

Future cleanup:
- Add a dedicated reorder endpoint, for example `PUT /api/me/profile/photos/order`, when the product needs real drag-and-drop ordering.
- The endpoint should accept ordered `photoIds`, validate that every photo belongs to the current user's profile, reject duplicate or missing photos, and reassign positions atomically.

Acceptance criteria:
- Upload and replace-file flows remain unchanged for Android.
- Reordering is handled by the dedicated endpoint rather than overloading upload semantics.

### 2.2.2 Post-MVP media pipeline options

MVP decision:
- Keep profile photo uploads backend-mediated.
- Keep original images only; no generated thumbnails or previews.

Post-MVP options:
- Consider direct-to-storage upload using presigned write URLs if backend bandwidth becomes a concern.
- Add generated thumbnails/previews when profile photo load performance needs it.

Acceptance criteria:
- These options remain separate from the MVP R2 setup.
- Endpoint contracts stay stable until Android and backend agree on a new media flow.

### 2.3 Multipart photo upload is the only MVP flow

Status: resolved.

Decision:
- Multipart file upload is the only supported MVP profile photo mutation flow.
- Legacy URL-based profile photo create/replace endpoints were removed.
- Android/frontend must use real file upload for profile photos.
- Pre-MVP local/dev databases containing old `EXTERNAL_URL` profile photo rows should be reset or migrated manually; external URL photos are no longer supported.

Acceptance criteria:
- `POST /api/me/profile/photos` accepts multipart `file` and `position`.
- `PUT /api/me/profile/photos/{photoId}/file` accepts multipart `file`.
- URL/mock/non-file profile photo creation and replacement are not supported by the backend.
- Profile activation can be tested end-to-end with uploaded files.

### 2.4 Matchmaking job configuration clarity

`MatchmakingJob` exists and delegates to `MatchmakingProcessorService`.

MVP cleanup:
- Add explicit application config entries for:
  - `scheduler.matchmaking-job.fixed-delay`;
  - `scheduler.matchmaking-job.max-pairs-per-run`.
- Confirm that the intended MVP backend environment has schedulers enabled.
- Confirm that local/dev environments can keep schedulers disabled when manual job execution is preferred.

Acceptance criteria:
- Matchmaking behavior does not rely only on annotation defaults.
- Dev/staging behavior is explicit.
- Queue processing can be tuned without code changes.

### 2.5 Keep current single-worker matchmaking model

MVP decision:
- Keep `MatchmakingJob` protected by ShedLock.
- Keep one active matchmaking processor at a time.
- Keep PostgreSQL `FOR UPDATE SKIP LOCKED`.
- Do not introduce Redis, Kafka, external queues or parallel matchmaking workers before MVP.

Reason:
- Simpler and safer.
- Easier to debug.
- Avoids premature infrastructure complexity.

MVP checks:
- Queue rows do not get stuck during manual tests.
- Active engagement locks are released when flows end.
- Basic diagnostics exist for queue size and active locks.

### 2.6 Lifecycle jobs remain idempotent

MVP requirement:
- Scheduled jobs should be safe to retry.
- A failed record should not permanently block unrelated records.
- Job summaries should include processed/succeeded/skipped/failed counts.
- Unexpected job failures should log stack traces, not only `ex.message`.

Acceptance criteria:
- Re-running a job does not create duplicate chats, penalties, locks or state transitions.
- Stale or duplicate states are treated as no-op where possible.

### 2.7 Explicit terminal state documentation

Current non-blocking issue:
- Some records can remain in states that look non-terminal but are historical.
- Example: a `Match` can remain `VISUAL_APPROVED` after its derived `Connection` is already `CLOSED`.
- Example: a `ScheduleNegotiation` can remain `CONFIRMED` after the second-chat lifecycle ended.

MVP decision:
- Not a functional blocker if records are not visible in Home and no longer hold active locks.
- Document this clearly for debugging.

Acceptance criteria:
- Operational queries distinguish active/actionable state from historical successful state.
- Team does not misinterpret historical states as dangling active work.

---

## 3. Android/frontend MVP cleanup

### 3.1 Complete RootViewModel refactor

Status:
- In progress / assumed before further frontend hardening.

Goal:
- Keep root view model as orchestration only.
- Move feature-specific behavior into focused handlers/coordinators.

Expected areas:
- Session/account.
- Profile.
- Home/matchmaking.
- First chat.
- Visual approval.
- Scheduling.
- Second chat.

Acceptance criteria:
- No behavior regression in login/provision/profile/home/chat/scheduling.
- Home auto-routing still works.
- Polling does not overwrite visible user errors.
- Hidden/dismissed Home interactions are still pruned correctly.

### 3.2 Hide manual location fallback outside local/dev

MVP requirement:
- Hide manual latitude/longitude fallback in production builds.
- Keep it only in local/debug/dev builds or behind a feature flag.
- Production user flow should use device location permission and current device location.

Acceptance criteria:
- Production UI does not show manual coordinate entry.
- Local/dev builds can still test coordinates manually.
- Matchmaking location submission remains stable.

### 3.3 Configure real dev/prod API URLs

MVP requirement:
- Configure real dev/staging backend URL for installable APK testing.
- Keep local emulator URL for local flavor.
- Avoid placeholder URLs in release-like builds.

Acceptance criteria:
- `localDebug` points to local backend/emulator-compatible URL.
- `devDebug` / `devRelease` point to a real dev backend.
- `prodRelease` does not point to placeholder URLs.
- Release builds do not allow cleartext traffic unless explicitly intended.

### 3.4 Visual approval review gating

Decision pending:
- Whether the user must scroll/view all photos before approval.

MVP recommendation:
- If this remains a product rule, add local UI gating:
  - disable Approve until the condition is met;
  - keep Reject available if desired;
  - show clear copy explaining the requirement.

Acceptance criteria:
- User cannot instantly approve without required review if the rule is active.
- UI explains disabled actions.

### 3.5 Keep polling as temporary frontend/backend strategy

MVP decision:
- Home, chat, scheduling and second-chat availability may continue using polling.
- Push/realtime infrastructure is not required for MVP.

MVP checks:
- Polling intervals are not unnecessarily aggressive.
- Polling endpoints are stable and idempotent.
- Chat message fetching supports repeated polling during test sessions.
- Payloads remain bounded enough for MVP usage.

### 3.6 Report visual profile or profile photo

MVP safety recommendation:
- Automatic image moderation is not required for MVP.
- However, users should eventually be able to report objectionable visual profile content outside chat.

MVP decision:
- If not implemented before MVP, document it as a known safety gap.
- Chat safety reports remain the initial safety mechanism.

Future frontend surfaces:
- Visual approval screen.
- Partner profile screen.
- Future photo viewer.

Acceptance criteria for first implementation:
- User can report problematic visual content without needing to send a chat message.
- Reporting does not leave the user stuck in the interaction.
- UI clarifies whether the report also rejects/cancels the flow.

---

## 4. MVP safety and security minimum

### 4.1 Secrets must remain out of source control

MVP requirement:
- Never commit real Firebase Web API keys, Firebase test user passwords, ID tokens or service-account credentials.
- Bruno tracked environments should keep placeholders.
- Real values belong only in local ignored files or deployment secrets.

### 4.2 Firebase/JWT operational validation for dev/staging

MVP requirement:
- Validate Firebase service-account configuration in the intended dev/staging backend environment.
- Validate Android Firebase Auth login/provisioning against deployed backend.
- Validate deleted-account and reactivation behavior against real Firebase tokens.

### 4.3 Basic sensitive log policy

MVP requirement:
- Do not log:
  - Firebase ID tokens;
  - Authorization headers;
  - private media URLs;
  - raw request bodies containing chat/personal message content;
  - passwords or test credentials.

Production hardening can go further, but these should already be avoided in MVP.

### 4.4 CSRF remains disabled only under stateless bearer-token auth

Current decision:
- CSRF protection is intentionally disabled while the API is stateless and authenticated through explicit `Authorization: Bearer ...` tokens.
- Revisit before introducing cookie auth, form login, browser-managed sessions or credentials automatically attached by browsers.

---

## 5. MVP infrastructure/dev environment

### 5.1 First external dev deploy target

MVP need:
- A backend environment reachable from a physical Android device and installable APK.

Decision pending:
- Choose first external development deploy target.

Candidates:
- Render.
- Fly.io.
- Railway.
- Google Cloud Run.
- AWS App Runner.
- ECS Fargate.
- Managed PostgreSQL provider such as Neon, Supabase, Render PostgreSQL, Railway PostgreSQL or AWS RDS.

MVP recommendation:
- Prefer simple container platform + managed PostgreSQL before Kubernetes.

### 5.2 Dev deployment model

Before distributing APKs beyond local machine:
- Define runtime platform.
- Define managed PostgreSQL instance.
- Define Firebase service-account secret.
- Define environment variables.
- Define health check path.
- Define rollback strategy.
- Define which GHCR tag dev tracks.

### 5.3 Smoke check workflow

MVP task:
- Wire the manual `Smoke check` GitHub Actions workflow into the eventual dev deploy pipeline once the dev runtime platform exists.

Acceptance criteria:
- Smoke check runs against the deployed backend.
- `/actuator/health` and `/actuator/info` are aligned with deployed image metadata.

---

## 6. Explicitly deferred from MVP

The following are intentionally not MVP blockers:

- Real-time chat via WebSocket or SSE.
- Additional push notification event coverage beyond `VISUAL_REVIEW_AVAILABLE`.
- Google Sign-In / social auth providers.
- Reveal quotas.
- Advanced compatibility scoring.
- ML-based matching.
- Popularity, attractiveness or ELO-style ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full manual moderation workflow.
- Identity verification provider integration.
- Canonical country/city reference dataset.
- Geohash/spatial indexing.
- CDN/cache strategy for media.
- Application-level message encryption.
- Parallel matchmaking workers.
- Kubernetes/Helm/Terraform unless the chosen platform requires them.
