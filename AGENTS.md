# Repository Agent Rules

These instructions apply to the entire repository. Follow user instructions and
any more-specific nested `AGENTS.md` files first.

## 1. Authority And Repository Inspection

- Treat current code and canonical repository docs as the source of truth.
- Do not infer architecture only from prompts, reports, handoffs, or memory.
- Use `development` as the comparison base unless the user names another base.
- Before concrete changes, verify branch, fetch refs, inspect `git status
  --short`, compare with the base, and inspect actual affected files.
- Inspect affected tests, relevant workflows, and canonical docs before changing
  behavior, contracts, configuration, lifecycle, or local tooling.
- Search all callers and related state-transition entry points before changing
  shared behavior; prefer `rg` and `rg --files`.

## 2. Git And Publication Safety

- Do not create commits unless the user explicitly asks after review or
  validation.
- Do not push to any remote branch unless the user explicitly asks for that
  exact push.
- Never push directly to `development` unless explicitly asked to push to
  `development`.
- Do not merge, deploy, open a PR, or modify cloud infrastructure unless
  explicitly requested.
- Do not report a commit, push, merge, deployment, PR, or clean working tree
  unless it was actually observed.
- Final reports must describe the currently observed repository state.

## 3. Scope And Architecture

- Prefer the smallest coherent change that solves the requested problem.
- Follow existing controller, service, repository, DTO, mapper, configuration,
  and test patterns.
- Keep domain transition logic in services, not controllers, unless an existing
  path clearly uses another pattern.
- Avoid broad refactors and parallel abstractions that duplicate responsibility.
- Preserve unrelated first-chat, authentication, legal, profile, matchmaking,
  scheduling, safety, and account-lifecycle behavior.
- Avoid changing public contracts, navigation-equivalent backend flows, database
  schemas, or global architecture unless required.
- Stop and explain before making a substantially broader change than requested.

## 4. State-Machine Analysis Before Implementation

- For statuses, lifecycle phases, expiration, scheduling, claims, approvals,
  cancellation, cleanup, or terminal outcomes, determine the transition model
  before coding.
- Identify owner aggregate, valid source states, valid target states, terminal
  states, transition precedence, authoritative timestamps, and exact boundary
  semantics.
- Identify the idempotency key, uniqueness mechanism, or replay guard.
- Identify every HTTP write, message write, scheduler, local tool, or cleanup
  path capable of causing the same transition.
- Identify scheduler/manual-write interaction and read-only cleanup behavior.
- Keep a transition/race checklist in working notes even when no file is added.
- Before applying a requested action, evaluate under the authoritative lock
  whether an earlier lifecycle transition is already due.
- A requested action must not bypass an already-due terminal outcome.

## 5. Exact Time-Boundary Rules

- Use explicit comparison semantics for every deadline.
- State whether equality belongs to the allowed side or the expired side.
- Default lifecycle rules to allowed only while `now < deadline` and due or
  expired when `now >= deadline`.
- Test immediately before the boundary, exact equality, and immediately after.
- Use the authoritative backend clock or an injected/explicit test clock.
- Do not use arbitrary sleeps in tests.
- Do not derive a phase deadline from a timestamp belonging to an earlier phase.
- Clamp or derive clocks from the correct phase start when earlier data may
  exist.

## 6. Canonical Lifecycle Calculations

- Keep each lifecycle rule consistent across write validation, status
  eligibility, scheduler candidate selection, locked scheduler revalidation,
  cleanup, tests, and documentation.
- Prefer one focused helper or policy for calculations shared inside a layer.
- Repository candidate queries may be broad enough to find possible work.
- Final transition execution must always be revalidated under lock.
- Query predicates and locked validation must not use contradictory deadline
  formulas.

## 7. Transaction Boundaries And HTTP Errors

- Never mutate state that must commit and then throw an unchecked domain
  exception from the same transaction merely to produce an HTTP error.
- If a request-triggered operation commits a lifecycle outcome that makes the
  requested action invalid, return a typed success/rejection result.
- Let the transactional service commit the lifecycle outcome; map rejected
  results to HTTP/domain errors only after the transactional call returns.
- Do not use broad `@Transactional(noRollbackFor = [...])` for domain runtime
  exceptions.
- Do not rely on self-invoked `REQUIRES_NEW`; Spring proxy semantics do not
  apply to self-invocation.
- Do not catch an exception inside a transactional integration test and assume
  preceding mutations will commit.
- Failures discovered before mutation may use normal domain exceptions.

## 8. Lock Ordering And Concurrency

- Identify the aggregate lock for every stateful transition.
- Use one deterministic lock order across HTTP writes, scheduled jobs, message
  writes, request expiry, and cleanup.
- Never introduce the reverse lock order in another path.
- Lock before terminal eligibility decisions and re-read mutable state after
  obtaining the lock.
- Reason about request versus scheduler, response versus expiration, message
  versus inactivity, duplicate request, repeated scheduler, and competing
  terminal-transition races.
- Prefer database constraints as the final concurrency backstop where practical.
- Document lock order in code or canonical architecture docs when not obvious.

## 9. Idempotency And Side Effects

- Lifecycle transitions and scheduler retries must be replay-safe.
- Duplicate HTTP requests must not extend deadlines or create duplicate rows
  unless explicitly required.
- Reliability, audit, notification, and Home-invalidation side effects must
  occur at most once per logical event.
- Reuse existing uniqueness and idempotency mechanisms.
- Terminal transitions must preserve their original terminal reason during
  read-only cleanup.
- Repeated processing of a terminal aggregate should become a no-op.

## 10. Read Endpoints And Status Contracts

- Keep GET and status endpoints side-effect free unless explicitly designed
  otherwise.
- Polling must not materialize entities, join users, award scores, or resolve
  lifecycle transitions.
- Status eligibility must use the current authoritative backend time.
- Due-but-not-yet-persisted transitions must disable already-invalid actions.
- Read responses must not claim that a transition was persisted when it was not.
- Do not trust clients to announce expiry or terminal state.

## 11. Database Migrations

- Use a new Flyway migration for schema changes.
- Never edit a migration that may already have been applied.
- Inspect the latest migration number before creating a migration.
- Add appropriate foreign keys, uniqueness constraints, and indexes.
- Use database constraints for invariants that must survive concurrent
  application instances.
- Consider existing data and nullability before adding non-null columns.
- Keep persistence enums and migration values synchronized.
- Update fixtures and local deterministic helpers when schema changes require
  it.

## 12. API And Compatibility

- Prefer additive contract changes when practical.
- Preserve existing response fields unless removal is explicitly approved.
- Preserve unknown-enum compatibility where existing contracts use it.
- Use server-authoritative timestamps for client countdowns.
- Add focused error codes for distinct conflicts; do not overload unrelated
  domain errors.
- Keep controllers thin and inspect affected consumers, DTO mappings, OpenAPI,
  and docs before contract changes.

## 13. Documentation And Local Tooling

- When behavior or contracts change, inspect affected canonical docs and local
  tooling.
- Update architecture, configuration, API, OpenAPI, domain, state-machine,
  user-flow, reliability, lifecycle/manual test, local-development, and Bruno
  sources as applicable.
- Documentation must describe exact boundary and terminal semantics.
- Do not edit every document for every task; update only affected canonical
  sources.

## 14. Testing Strategy

- Never run unfiltered `./mvnw test`.
- Never run `./mvnw clean test`.
- Never run `clean` merely as routine validation.
- Run only exact affected test classes or focused test methods.
- If complete-suite execution is genuinely required, stop, explain why, and ask
  the user to run it or leave it to CI.
- Cover success, rejection, temporal boundaries, idempotent replay, duplicate
  calls, stale candidates, scheduler replay, competing transitions, and
  unrelated behavior when relevant.

## 15. Transaction-Commit Integration Tests

- Spring transactional integration tests can observe uncommitted state.
- Catching a runtime exception inside a test transaction does not prove that
  preceding mutations will commit.
- When an endpoint must persist a lifecycle transition and then return a
  conflict, cross the real transaction boundary.
- Use a pattern equivalent to `@Transactional(propagation =
  Propagation.NOT_SUPPORTED)`.
- Create committed fixture setup through `TransactionTemplate`.
- Then perform the HTTP request, assert the conflict, and verify database state
  in a new transaction.
- Do not require this pattern when no mutation must survive the error.

## 16. Scheduler Rules

- Lifecycle jobs must process bounded batches with deterministic ordering.
- Revalidate every candidate transactionally and skip stale candidates.
- Tolerate repeated execution and multiple application instances.
- Preserve one terminal winner and explicit precedence between processors.
- Avoid loading all due rows into memory.
- Do not treat control actions as conversational or business activity unless the
  relevant feature explicitly defines them that way.

## 17. Text Encoding And User-Visible Strings

- Treat every repository text file as UTF-8.
- Preserve valid Unicode characters directly, including Spanish accents, `ñ`,
  `ü`, `¿`, and `¡`.
- Never introduce mojibake such as `Ã¡`, `Ã©`, `Ã­`, `Ã³`, `Ãº`, `Ã±`,
  `Â¿`, `Â¡`, malformed smart quotes, or the Unicode replacement character
  `�`.
- When editing files on Windows, use tools and APIs that read and write UTF-8
  explicitly. Do not use shell redirection or file-writing commands whose
  encoding depends on the platform default.
- Do not encode valid Spanish text as Latin-1, Windows-1252, escaped byte
  sequences, or already-corrupted UTF-8 text.
- Do not convert an entire file's encoding, line endings, formatting, or
  unrelated contents merely to edit one string.
- Before completion, inspect every newly added or modified user-visible string
  in the final diff and confirm that accented characters appear correctly in the
  source file.
- Search added lines for common mojibake markers using an equivalent command to
  `git diff --unified=0 | rg "^\+.*(Ã|Â|â€|�)"`.
- Inspect and correct every match unless the malformed text is intentionally
  present in an encoding-specific test fixture or documentation example.
- Do not perform global replacement of suspected mojibake without determining
  the intended original text.

## 18. Validation Before Completion

- Run `./mvnw -DskipTests compile` when code or configuration changes require
  compilation validation.
- Run focused exact tests appropriate to the change.
- Run `git diff --check`.
- Inspect added and modified text for malformed Unicode or mojibake.
- Inspect `git status --short`, `git diff --stat`, and the final diff for
  accidental unrelated changes.
- Inspect changed migration and contract files when present.
- Confirm current branch and comparison base before final reporting.
- Do not claim validation passed unless its output was actually observed.

## 19. Final Report

- Report current branch and comparison base.
- Report root cause or design rationale.
- Report files changed.
- Report migration and contract changes, or state that none were made.
- Report transaction and lock approach for lifecycle changes.
- Report exact validation commands and observed results.
- Report tests not run and remaining manual scenarios.
- Include `git status --short` and `git diff --stat`.
- Explicitly confirm that no commit, push, merge, deploy, or PR was performed
  unless the user requested it.
