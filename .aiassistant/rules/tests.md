---
apply: always
---

# Tests

- There are currently no committed automated tests under `src/test/kotlin`.
- If adding automated tests, prefer focused service-level tests for business rules.
- High-value coverage areas: state transitions, invalid transitions, active engagement limits, matchmaking queue behavior, scheduling confirmation/failure, profile activation rules and penalties.
- If tests cannot be run locally, say so explicitly and describe the verification that was performed.
