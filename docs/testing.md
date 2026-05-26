# Testing

Automated tests live under:

```text
src/test/kotlin
```

The current suite uses Spring Boot integration tests with the `test` profile and H2 in-memory. This is intentional: core behavior depends on services, repositories, JPA mappings, transactions and schema shape.

## Why Integration Tests First

Unit tests are still useful for pure logic, for example compatibility scoring or future trust-score calculations. They are less useful for the current end-to-end user flow because the highest-risk bugs have appeared at boundaries:

- entity/schema mismatch
- transaction and state transition coupling
- lock creation/release
- repository queries
- service orchestration across match, chat, visual review, connection and scheduling

For those cases, service-level integration tests catch more realistic regressions than mocks.

## Current Coverage

`UserFlowIntegrationTest` covers:

- happy path from profile creation to closed connection
- profile activation photo requirements
- draft profile queue rejection
- duplicate chat decision rejection
- chat rejection state and lock release
- non-participant chat message rejection
- non-participant scheduling proposal rejection
- own-proposal acceptance rejection
- exact second-chat slot auto-confirmation with both proposals accepted

Bruno also includes manual HTTP collections that are convenient to run against the local application:

- `01 Happy Path`: successful user flow through second chat.
- `02 Not Happy Paths`: technical negative checks and guardrails, mostly expected 4xx responses.
- `03 Alternate Outcomes`: valid business outcomes that stop before a successful second chat, such as first-chat rejection, visual rejection, scheduling failure after max rounds and incompatible queued users.
- `04 Timeout Outcomes`: local/dev-only manual checks for deadline-driven jobs. These use `/api/dev/timeouts/...` to move deadlines into the past and `/api/dev/jobs/.../run` to trigger the real jobs deterministically.

The `/api/dev/...` endpoints are only exposed for `local`, `local-nodb` and `dev` profiles. They must not be enabled in production.

## Running Tests

From a shell with Maven and Java configured:

```text
mvn test
```

On this machine, Maven was run through IntelliJ IDEA's bundled Maven with `JAVA_HOME` pointing at the IntelliJ JBR.
