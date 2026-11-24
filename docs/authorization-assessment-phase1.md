# Backend authorization assessment, phase 1

## Scope and method

All 383 distinct tests selected for this assessment passed across the initial runs and one targeted rerun. The tested controls denied unrelated users access to message contents, scheduling data and media references. Source review identified one low-severity error-message privacy issue. Post-block audio retention remains an open policy question.

Assessed the local snapshot of `origin/development` at `c280bd03913236c93086ceeb35ec11e45c2556ef` on `security/pentest-authorization-phase1`. A and B are synthetic participants in the same interaction. C is a separately persisted, authenticated user outside it. Fixtures are created per test. The assessment covers selected object reads, disclosure decisions, lifecycle gates and administrative operations.

Controller integration tests use Spring MockMvc, the application's security chain, services and repositories. `ControllerIT.authenticatedAs` supplies a Spring Security principal. The test profile creates a fresh in-memory H2 database per application context and disables scheduling. Most cases run in rollback transactions. The new media tests replace storage with a mock and check whether URL resolution was invoked. The local Firebase profile expects a service account and real token verification, so it was unsuitable here. The attached frontend environment file was unnecessary. [Local development](local-development.md#local-firebase-auth) · [Existing security debt, item 8](security-technical-debt.md#8-backend-object-level-authorization-bolaidor-tests)

## Authorization map

| Boundary | Controller, service and persistence path |
| --- | --- |
| Identity and roles | `FirebaseTokenFilter` verifies the token, looks up the local user by Firebase UID and applies account/provider restrictions. `CurrentUserIdArgumentResolver` takes the internal UUID from the security context. App Check attests the app. Ordinary object requests require the user role and an additional resource check. |
| Chat details and message pages | `ChatController` passes the principal and chat ID to the service. `ChatAccessService` loads the chat and its match and compares the principal with both match participant IDs. Both page variants in `ChatMessageService` validate membership and readability before querying `ChatMessageRepository` by chat. An incremental cursor must belong to that chat. `ChatLifecycleService.validateChatReadable` rejects a persisted CLOSED chat. |
| Audio metadata | Message serialization calls `ChatController.audioReadUrl` with the stored bucket and key only after the read succeeds. Creation also has participant, pair-block, lifecycle and audio-policy checks. The storage service knows the object locator. The application owns the identity decision. |
| Connections and scheduling | `ConnectionController` gates detail, negotiation and proposal reads with `ConnectionService.findByIdForUserOrThrow`, which loads the connection and checks its A/B IDs. The downstream negotiation and proposal repositories filter by connection ID. Their read helpers accept no requester. Scheduling writes pass the principal into `SchedulingService`. Acceptance locks the aggregate, binds the proposal to the connection and checks participant, block, phase, round, deadline and proposer before confirmation. |
| Match and visual review | `MatchController` uses `MatchService.findByIdForUserOrThrow` for ordinary match detail. `VisualReviewService` delegates disclosure to `VisualResourceAccessPolicy`. It checks match membership, either-direction block via `UserBlockRepository.existsBetweenUsers`, and the review for that match. During review the interval is `availableAt <= now < expiresAt`. After approval it also requires a linked connection, membership in it and an allowed active connection state. |
| Photos | `ProfileController` resolves the owner's profile from the principal. Replace/delete compare the selected photo's profile ID with that profile. Deletion locks the photo row and returns `PROFILE_PHOTO_NOT_FOUND` on mismatch. Partner disclosure derives the other user's profile after the visual gate. `ProfilePhotoService.getExternallyVisiblePhotoViews` queries by profile, filters to APPROVED and then resolves URLs. |
| Administrative resources | `SecurityConfig` requires the admin role for `/api/admin/**`. On privileged Firebase requests, `FirebaseTokenFilter` consults `AdminAccessService`. Eligibility binds an active unbanned local account to an allowlisted UID, verified email, Google provider and web app. Grant validation reads the hashed grant and checks its identity/context, revocation and expiry. Downstream diagnostics aggregate queue and lock rows, moderation reads/locks photo rows, reports load evidence, and appeals load or lock penalties. These privileged queries rely on the route boundary. |

Source entry points: [security chain](../src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt), [chat access](../src/main/kotlin/com/reals/backend/service/ChatAccessService.kt), [connection routes](../src/main/kotlin/com/reals/backend/controller/ConnectionController.kt), [visual policy](../src/main/kotlin/com/reals/backend/service/VisualResourceAccessPolicy.kt), [admin grants](../src/main/kotlin/com/reals/backend/adminauth/AdminAccessService.kt).

## Scenario matrix and evidence

HTTP statuses below are MockMvc assertions. PASS is scoped to the stated assertion, including its fixture and role. A status-only denial establishes rejection. Separate entries identify checks on response contents, signer calls or database state. Each case is repeatable through the named integration test class and the commands below.

| Resource and precondition | Expected and observed | Result and evidence |
| --- | --- | --- |
| GET `/api/chats/{id}` and GET/POST `/api/chats/{id}/messages`, active A/B chat | Participants exchange and list messages. C gets 403 on chat and message reads. A cursor from another chat gets 409 `CHAT_NOT_AVAILABLE`. | PASS · `ChatControllerIntegrationTest`. Error-body metadata is addressed below. |
| GET `/api/connections/{id}`, A/B scheduling connection | C gets 403 on the existing connection. | PASS · `ConnectionControllerIntegrationTest`. |
| GET `/api/connections/{id}/negotiation` and `/proposals`, A submitted a slot | B gets 200 with this connection and proposal. C gets 403 on both, without the fixture proposal ID or a top-level round number. | PASS · `AuthorizationBoundaryIntegrationTest`. |
| POST `/api/connections/{id}/proposals` and `/proposals/{proposalId}/acceptance` | A's valid list gets 201. B can accept a partner proposal. A blocked scheduling write is rejected by the service with no proposal or reliability event and the negotiation still pending. | PASS · `ConnectionControllerIntegrationTest`, `UserBlockBack3IntegrationTest`. Post-block state assertions are service-level. |
| GET `/api/matches/{id}` and `/visual-profile` | C gets 403 even when visual review is open. A gets 409 before reveal, before delayed availability, at expiry or after approved connection closure. Valid review returns partner content. | PASS · `MatchControllerIntegrationTest`, `ProfileQuestionVisualProfileIntegrationTest`, `VisualReviewIntegrationTest`. Exact time boundaries use service tests. |
| GET `/api/me/profile/photos` and partner visual profile | Owner can read their uploaded photo. The partner sees only approved photos. For pending and rejected photos the signer receives no request. | PASS · `ProfilePhotoFileControllerIntegrationTest`, `MatchControllerIntegrationTest`. |
| POST `/api/matches/{id}/block` then GET `/visual-profile` by B | B gets a photo reference before A's block. Block creation returns 201. B then receives 409 `USER_PAIR_BLOCKED`, without photos or a storage call. A blocked partner-message read also leaves the read timestamp unset. | PASS · `AuthorizationBoundaryIntegrationTest`, `MatchControllerIntegrationTest`. |
| GET `/api/chats/{id}/messages`, A's audio in an active second chat | B gets 200 with the fixture message and audio reference. C gets 403. Once the fixture chat row is CLOSED, B gets 409 `CHAT_NOT_AVAILABLE`. Neither denied read invokes storage. Participants can still read text in the tested EXPIRED read-only retention state. | PASS · `AuthorizationBoundaryIntegrationTest`, `ChatControllerIntegrationTest`. The CLOSED audio case sets the state directly. |
| GET `/api/admin/diagnostics/matchmaking`, GET `/api/admin/profile-photos/review`, POST `/api/admin/profile-photos/{id}/moderation` | Ordinary authenticated user gets 403. Positive admin-role fixtures retrieve diagnostics, review photos and resolve moderation. | PASS · `AdminDiagnosticsControllerIntegrationTest`, `AdminProfilePhotoModerationControllerIntegrationTest`. |
| GET administrative safety reports, pending penalty appeals, matchmaking diagnostics and photo review queue | An eligible synthetic identity without an MFA grant receives 403 on all four routes. A valid grant yields 200 on each. Password-provider reuse and revoked-grant reuse on diagnostics receive 403. | PASS · `AdminMfaPostgresIT`. Production security filters, test verification adapters, a fresh PostgreSQL 16 Testcontainer and real grant persistence. |

## Findings and recommended remediation

**Low severity, error-message privacy (source evidence).** The chat participant guard constructs a denial message containing the parent match ID. The global MVC handler copies the exception message into the client error body. The match ID is distinct from the chat ID supplied to the endpoint. Other participant guards also embed identifiers. The HTTP assertions confirm rejection but do not assert the precise diagnostic text. The potential disclosure concerns relationship metadata. The reviewed tests establish no unauthorized access to conversation content and no unauthorized state change. Proposed change for separate review: return a stable public message for `ACCESS_DENIED` and assert that the response omits internal relationship identifiers. [Chat guard](../src/main/kotlin/com/reals/backend/service/ChatAccessService.kt) · [MVC error handler](../src/main/kotlin/com/reals/backend/controller/GlobalExceptionHandler.kt)

## Inconclusive cases and later coverage

- **Post-block history and audio reissuance.** Block containment can set the chat to CANCELLED and its connection to CLOSED. The message-read policy checks the chat's own CLOSED status. Existing block coverage preserves participant access to chat details. The CLOSED audio fixture above does not establish the policy for new references to old audio after a block. Clarify the retention contract separately for text, audio issuance and previously issued links, then add a normal block-transition regression for each supported outcome.
- **Post-closure scheduling history.** Detail and scheduling reads check participant identity. The reviewed read path has no active-state requirement. Confirm which fields former participants should retain, then assert the response shape after closure.
- **Nested proposal mutations.** A foreign chat cursor has regression coverage. Cross-connection proposal acceptance has a source-level parent check. A paired acceptance/denial regression with independent post-request reads of both negotiations would strengthen evidence for persistence isolation.
- **Owner photo writes and concurrency.** Positive owner replace/delete tests and the source ownership check cover different parts of the path. Add an explicit foreign-photo denial with the photo, storage and cleanup queue checked after the request. Revocation concurrent with serialization and storage-provider expiry/caching remain unverified.
- **Infrastructure boundary.** MockMvc uses in-process requests. The new tests verify application permission to obtain media references. No object bytes were fetched and no assertion depends on an issued URL being identity-bound. Hosted configuration, live identity providers and deployed network paths are outside this assessment.

## Reproduction and validation

Run in the backend checkout using JDK 21, the cached Maven distribution/dependencies and a local Docker engine for the PostgreSQL suite. Use a test shell without application credential or deployment overrides. The class selection below includes both local-profile exposure classes defined in one Kotlin file.

```powershell
$classes=(rg --no-filename --only-matching '^class [A-Za-z0-9]+Test\b' src/test/kotlin/com/reals/backend/config/security) -replace '^class ',''
$selected=($classes -join ',') + ',AdminDiagnosticsControllerIntegrationTest,AdminSafetyReportControllerIntegrationTest,AdminProfilePhotoModerationControllerIntegrationTest,ChatControllerIntegrationTest,ConnectionControllerIntegrationTest,ProfileQuestionVisualProfileIntegrationTest,VisualReviewIntegrationTest,ProfilePhotoFileControllerIntegrationTest,ChatAudioServiceTest'
.\mvnw.cmd -o "-Dtest=$selected" test
.\mvnw.cmd -o "-Dtest=AuthorizationBoundaryIntegrationTest,MatchControllerIntegrationTest,UserBlockBack3IntegrationTest,AdminTotpTest,AdminMfaPostgresIT,PenaltyAppealControllerIntegrationTest" test
.\mvnw.cmd -o '-Dapi.version=1.44' '-Dtest=AdminMfaPostgresIT,UserBlockBack3IntegrationTest,MatchControllerIntegrationTest' test
python scripts/check_bruno_request_vars.py
git diff --check
```

| Validation | Observed result |
| --- | --- |
| Security-package tests and nine controller/service classes | 309 passed, zero skipped. |
| Six additional classes, first attempt | Surefire reported 56 entries, one failure and two errors. All three new tests passed. An existing duplicate personal-message test got 409 on its first write (expected 204). An existing block-history test stopped during visual fixture setup. Both encountered visual availability checks. The PostgreSQL class stopped at container startup because Docker rejected client API 1.32. No PostgreSQL authorization assertion ran on this attempt. |
| Three affected classes, targeted rerun | 32 match controller tests, 10 block tests and 19 PostgreSQL admin MFA tests passed, zero skipped. The process-local Docker API override addressed the container start error. The two visual fixture failures did not recur; their root cause remains unverified. |
| Static validation | The Maven test lifecycle compiled application and test sources. The CI Bruno variable guard passed. Both additions passed UTF-8, mojibake and whitespace checks. |

The new regressions live in [AuthorizationBoundaryIntegrationTest](../src/test/kotlin/com/reals/backend/integration/controller/AuthorizationBoundaryIntegrationTest.kt). All changes are assessment documentation or test code.
