# MVP technical debt

This file tracks backend work that still blocks a controlled MVP/beta. Current
architecture, configuration, security, data-retention and deployment behavior
live in the canonical docs; production hardening lives in
`docs/technical-debt-prod.md`.

Do not use this file as a changelog. Delete completed setup notes once a
canonical current-state document covers the implemented behavior.

## Current MVP status

- The first external dev backend has been selected and implemented on AWS.
  Current behavior is documented in `docs/dev-deployment.md` and
  `docs/aws-dev-deployment.md`.
- The core backend flow is implemented with Firebase auth, PostgreSQL/Flyway,
  S3-compatible media storage, profile photos, profile activation, matchmaking,
  Home reads, first chat, visual review, scheduling, second chat, safety
  reports, user blocks, account deletion and local/dev operational tooling.
- Backend profile-photo reordering is implemented at
  `PUT /api/me/profile/photos/reorder`.
- The shared push notification preparation, delivery-result persistence and
  sender workflow is implemented under `service.notification`.
- Google-origin Firebase authentication is implemented at the backend boundary
  through Firebase ID tokens and immutable backend-owned `authOrigin`.

## Remaining MVP backend debt

- Keep Bruno/local smoke flows aligned with the current API when they are used
  as controlled MVP manual QA.
- Add new entries here only for concrete backend work that blocks controlled
  MVP/beta usage and is not already covered by canonical docs.

## Deferred beyond MVP

These are tracked as production or future-product work, not MVP blockers:

- Production deployment boundary, secrets, backup/restore and rollback policy.
- Production photo-analysis provider smoke validation and moderation operations.
- Production backoffice access model, child-safety operations and appeals.
- Final account-deletion purge/anonymization and backup-retention policy.
- Production observability backend, dashboards and alerting.
- Notification retry/backoff/outbox semantics and production FCM validation.
- Capacity, reliability and ranking calibration from real traffic.
- Distributed/gateway rate limiting for multi-instance scale.
- WebSocket/SSE, Redis, projections, direct-to-storage uploads, CDN, PostGIS,
  Kubernetes, Terraform/CDK, ML-based matching and public reputation features.
