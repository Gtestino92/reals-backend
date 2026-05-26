---
apply: always
---

# Backend

- Controllers must parse requests, call services and map responses.
- Business rules and state transitions belong in services.
- Repositories should remain persistence-focused Spring Data JPA interfaces.
- Schedulers must call services and avoid duplicated transition logic.
- Use `@CurrentUserId` when the action is for the authenticated user.
- Use constructor injection.
- Use `@Transactional` on mutating services.
- Validate current state before changing state enums.
- Keep active engagement counting based on `ActiveEngagementLock`.
- Store enums as strings and timestamps as `OffsetDateTime`.
