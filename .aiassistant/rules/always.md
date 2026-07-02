---
apply: always
---

# Always

- Answer in Spanish unless the user asks otherwise.
- Treat `AGENTS.md` and `docs/` as the source of truth for project context.
- Preserve the explicit state-machine architecture.
- Prefer existing Kotlin/Spring patterns over new abstractions.
- Keep changes scoped to the requested task.
- Do not introduce product behavior that is listed as not implemented in `docs/technical-debt-mvp` and `docs/technical-debt-prod.md`.
- Do not assume Maven CLI is available; prefer IntelliJ run/build unless the user confirms CLI usage.
- Do not bypass services for state transitions.
- Do not mutate domain state directly from controllers, schedulers or repositories.
- Work branches created or renamed by agents must use the `feature/` prefix unless the user explicitly asks for another branch type.
