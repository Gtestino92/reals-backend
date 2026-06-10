# Testing

Automated tests live under:

```text
src/test/kotlin/com/reals/backend/integration
```

The suite uses Spring Boot integration tests with the `test` profile and H2 in-memory.
PostgreSQL-specific behavior that H2 cannot model, such as row claiming with
`FOR UPDATE SKIP LOCKED`, is covered by focused Testcontainers tests under the
same suite. Those tests require Docker and are skipped when Docker is not
available.

Structure:

- `integration/support`: shared fixtures and base classes. `BaseIT` is the common service-level base; `ControllerIT` adds `MockMvc` and HTTP authentication helpers.
- `integration/service`: service-level integration tests. These load the Spring context and execute real services, repositories, JPA mappings and transactions against H2, then assert persisted state.
- `integration/controller`: HTTP/controller integration tests. These use `MockMvc` to validate routing, JSON request/response shape, status codes, exception mapping, security/current-user resolution and controller wiring without duplicating every business flow.

## Why Integration Tests First

Unit tests are still useful for pure logic, for example compatibility scoring or future trust-score calculations. They are less useful for the current end-to-end user flow because the highest-risk bugs have appeared at boundaries:

- entity/schema mismatch
- transaction and state transition coupling
- lock creation/release
- repository queries
- service orchestration across match, chat, visual review, connection and scheduling

For those cases, service-level integration tests catch more realistic regressions than mocks. Controller integration tests are intentionally smaller and focus on the HTTP contract.

## Current Coverage

`HappyPathIntegrationTest` covers:

- happy path from profile creation to closed connection

`UserFlowGuardrailIntegrationTest` covers:

- profile activation photo requirements
- draft profile queue rejection
- duplicate chat decision rejection
- visual approval requiring partner-message read receipt
- non-participant chat message rejection
- non-participant scheduling proposal rejection
- own-proposal acceptance rejection
- configured scheduling proposal-list maximum

`UserFlowAlternateOutcomeIntegrationTest` covers:

- chat rejection state and lock release
- visual rejection without connection creation
- incompatible queued users producing no match
- matchmaking candidate-pair filtering, candidate limit behavior and FIFO tie-breaking
- second-chat slot auto-confirmation across ordered proposal lists
- scheduling preference tie-breaks and explicit round rejection
- scheduling failure after max rounds

`ChatExitIntegrationTest` covers:

- mutual first-chat cancellation without penalties
- safety cancellation and reported-user penalty
- unilateral second-chat cancellation penalty behavior

`UserSoftDeleteIntegrationTest` covers:

- soft deletion state, Firebase disable/revocation integration boundary and recovery window behavior
- active engagement closure and lock release
- reactivation without reopening previous matches/connections

`ProfilePhotoFileControllerIntegrationTest` covers:

- multipart upload and replace flows
- storage-backed read URLs
- object deletion when a stored photo is deleted or replaced
- file validation errors and ownership checks

`SchedulerFlowIntegrationTest` covers:

- matchmaking job processing from queue to first chat
- inactive chat detection
- first-chat timeout expiration
- visual phase expiration
- scheduling timeout
- scheduled second-chat availability before activation
- second-chat activation on user entry or first message

Controller integration tests cover representative HTTP contract checks for:

- `ProfileController` and `MeController`: authenticated current-user resolution and profile creation JSON.
- `MatchController`: chat decision response, conflict mapping and personal-message write.
- `ConnectionController`: proposal submission, negotiation confirmation and proposal validation errors.
- `ChatController`: sending/listing messages, non-participant rejection and mutual cancellation over HTTP.
- `ProfilePhotoController`: multipart profile-photo upload/replace and stable photo error codes.

Bruno also includes manual HTTP collections that are convenient to run against the local application:

- `01 Happy Path`: successful user flow through second chat.
- `02 Not Happy Paths`: technical negative checks and guardrails, mostly expected 4xx responses.
- `03 Alternate Outcomes`: valid business outcomes that stop before a successful second chat, such as first-chat rejection, visual rejection, scheduling failure after max rounds and incompatible queued users.
- `04 Timeout Outcomes`: local-only manual checks for deadline-driven jobs. These use `/api/local-dev/timeouts/...` to move deadlines into the past and `/api/local-dev/jobs/.../run` to trigger the real jobs deterministically.

The `/api/local-dev/...` endpoints are only exposed for `local`, `local-nodb` and `local-postgres` profiles. They must not be enabled in cloud dev or production.

## Running Tests

From a shell with Java configured:

```text
.\mvnw.cmd test
```

Use `.\mvnw test` on Unix-like shells.
The PostgreSQL concurrency coverage uses Testcontainers, so Docker must be
running if you want that test to execute locally.

GitHub Actions also runs `./mvnw clean test` on pull requests and pushes to
`master` or `development`.

## CI Gates

Pull requests to `development` or `master` run:

- Maven tests.
- Docker Compose config validation.
- Backend Docker image build validation without publishing.
- Trivy image scan. Pull requests and pushes for `development` fail on fixed
  `CRITICAL` vulnerabilities. Pull requests and pushes for `master` fail on
  fixed `HIGH` or `CRITICAL` vulnerabilities. The scan table is also published
  to the GitHub Actions job summary before the job is failed.
- Dependency review for high-severity dependency changes.
- CodeQL default setup from GitHub code scanning.

Pushes to `development` or `master` run the same validation and then publish
the backend image to GHCR. The image publishing job does not run for pull
requests.

## Smoke Checks

The `Smoke check` GitHub Actions workflow is manual and is intended for a
deployed environment. Provide the backend base URL and it checks:

- `GET /actuator/health/readiness`
- `GET /actuator/info`
- `GET /api/ping`

It does not deploy anything and does not require application credentials.
Optional inputs `expected_image_tag` and `expected_image_revision` validate the
image metadata exposed by `/actuator/info`, so the same workflow can be wired
into deploy automation later.
