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

### 1.2 Visual-review personal message visibility

Status: closed for backend MVP contract.

MVP rule:
- A user may submit one optional personal message during visual review.
- The partner message can be fetched during visual review through `GET /api/matches/{matchId}/personal-messages/partner`.
- Fetching the partner message marks it as read when a partner message exists.
- If the partner submitted a personal message, the authenticated user must fetch/read it before either approving or rejecting.
- `GET /api/matches/{matchId}/visual-profile` exposes `partnerPersonalMessageSubmitted`, `partnerPersonalMessageRead`, and `decisionRequiresPartnerPersonalMessageRead` so Android can disable or explain visual decisions deterministically.
- Any visual decision before reading an existing partner message returns HTTP 409 with code `VISUAL_REVIEW_PARTNER_MESSAGE_NOT_READ`.

Remaining frontend requirement:
- Android should use the metadata to explain why approval is disabled or blocked.

### 1.3 Geolocation entry point

Decision pending:
- Final UX point where geolocation enters the user flow.

MVP recommendation:
- Use current search location when entering matchmaking/search.
- Keep profile `city`/`country` as user-facing profile fields.
- Do not require canonical city/country validation before MVP.
- Keep `accuracyMeters` captured and validated, but do not make imprecise accuracy a blocker unless a clear product rule is added.

Acceptance criteria:
- User can enter matchmaking with a valid location.
- Backend receives latitude, longitude and accuracy where required.
- Manual/dev location fallback is not exposed in production UI.

---

## 2. Backend MVP cleanup

### 2.1 Stable frontend-facing error codes

Continue converting high-frequency generic failures into stable domain error codes where they affect Android flow.

Prioritize:
- profile activation failures;
- photo upload failures;
- active penalty / blocked matchmaking;
- active match limit;
- active connection limit;
- unavailable/expired chat;
- scheduling invalid states;
- account deletion/reactivation states.

Acceptance criteria:
- Android can map expected failures to user-facing copy.
- Avoid exposing raw `IllegalArgumentException`, `IllegalStateException` or internal exception messages where the frontend needs deterministic behavior.

### 2.2 Profile photo validation MVP shortcut

For MVP, image validation is intentionally permissive.

MVP upload behavior:
1. Backend performs technical validation:
   - file exists;
   - file is not empty;
   - file size is within configured limits;
   - content type is allowed;
   - storage succeeds;
   - ideally, file can be decoded as an image and has acceptable dimensions.
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

### 2.3 Multipart photo upload is the official MVP flow

Current decision:
- Multipart upload is the official app flow.
- Legacy URL-based photo endpoints may remain temporarily for local/dev tests, but should not be used by production Android UI.

Acceptance criteria:
- Android production UI only exposes real file upload.
- Mock/URL photo actions are hidden or restricted to local/dev builds.
- Legacy endpoints are documented as transitional.

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
