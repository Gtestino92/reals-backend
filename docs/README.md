# Reals Backend Documentation

This directory is the canonical documentation set for the project. Architecture,
configuration, security, data-retention and commons files describe current
behavior. Technical-debt files describe remaining known work and decisions; they
should not preserve completed work as history.

## Files

- `architecture.md`: backend structure, layers and infrastructure boundaries.
- `configuration.md`: profile and environment variable reference.
- `data-retention.md`: account, media and safety-data retention behavior.
- `dev-deployment.md`: current AWS dev deployment shape, GHCR image, PostgreSQL, Firebase and runtime checks.
- `aws-dev-deployment.md`: manual AWS dev deployment workflow, OIDC/SSM setup, rollback behavior and future production design.
- `lifecycle-job-manual-test-plan.md`: manual checks for lifecycle jobs.
- `local-development.md`: local profiles, H2/PostgreSQL, auth and run notes.
- `matchmaking-ranking.md`: matching ranking modes and calibration notes.
- `operational-state-model.md`: persisted operational state and lifecycle notes.
- `security-mvp.md`: MVP security posture and explicit limitations.
- `storage-r2-configuration.md`: S3-compatible setup for shared/dev/prod-like application media storage, including R2 and hosted MinIO.
- `technical-debt-mvp.md`: remaining known backend work that blocks controlled MVP/beta usage.
- `technical-debt-prod.md`: remaining production-readiness work and production/future-product decisions.
- `testing.md`: automated test strategy and commands.
- `user-reliability-score.md`: reliability-score model and disabled-by-default status.

## Commons

- `commons/api.md`: current HTTP endpoints exposed by controllers.
- `commons/domain.md`: entities, enums, relationships and core invariants.
- `commons/openapi.yaml`: formal OpenAPI contract for API clients and tooling.
- `commons/state-machine.md`: allowed state transitions and lock behavior.
- `commons/user-flow.md`: end-to-end backend flow from profile creation to connection closure.

`AGENTS.md` at the repository root is the primary instruction file for AI coding agents. `.aiassistant/rules/` contains JetBrains AI Assistant rules derived from the same source of truth.
