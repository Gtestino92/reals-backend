# Reals Backend Documentation

This directory is the canonical documentation set for the project.

## Files

- `architecture.md`: backend structure, layers and infrastructure boundaries.
- `domain.md`: entities, enums, relationships and core invariants.
- `state-machine.md`: allowed state transitions and lock behavior.
- `user-flow.md`: end-to-end backend flow from profile creation to connection closure.
- `local-development.md`: local profile, H2, auth and run notes.
- `dev-deployment.md`: first dev deployment shape, GHCR image, PostgreSQL and Firebase runtime variables.
- `configuration.md`: profile and environment variable reference.
- `testing.md`: automated test strategy and commands.
- `api.md`: current HTTP endpoints exposed by controllers.
- `openapi.yaml`: formal OpenAPI contract for API clients and tooling.
- `technical-debt.md`: known pending decisions and intentionally unimplemented behavior.
- `local-h2-fixes.md`: local H2 repair snippets for old development schemas.

`AGENTS.md` at the repository root is the primary instruction file for AI coding agents. `.aiassistant/rules/` contains JetBrains AI Assistant rules derived from the same source of truth.
