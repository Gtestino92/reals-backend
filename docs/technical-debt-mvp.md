# TECH_DEBT_MVP

This file lists technical debt, cleanup tasks and product decisions that should be resolved for a first usable MVP/beta version of Reals.

MVP scope here means: enough to run the core product flow end-to-end with controlled users, a dev/staging backend, Android APK distribution, and known temporary shortcuts clearly documented.

Do not implement these implicitly while working on unrelated tasks.


## 4. MVP safety and security minimum

### 4.1 Secrets must remain out of source control

Status:
- Hygiene documented in `docs/security-mvp.md`.

MVP requirement:
- Never commit real Firebase Web API keys, Firebase test user passwords, ID tokens or service-account credentials.
- Bruno tracked environments should keep placeholders.
- Real values belong only in local ignored files or deployment secrets.

### 4.2 Firebase/JWT operational validation for dev/staging

Status:
- Operational validation checklist documented in `docs/security-mvp.md`.
- Still requires execution in the deployed dev/staging environment.

MVP requirement:
- Validate Firebase service-account configuration in the intended dev/staging backend environment.
- Validate Android Firebase Auth login/provisioning against deployed backend.
- Validate deleted-account and reactivation behavior against real Firebase tokens.

### 4.3 Basic sensitive log policy

Status:
- Policy documented in `docs/security-mvp.md`.

MVP requirement:
- Do not log:
  - Firebase ID tokens;
  - Authorization headers;
  - private media URLs;
  - raw request bodies containing chat/personal message content;
  - passwords or test credentials.

Production hardening can go further, but these should already be avoided in MVP.

### 4.4 CSRF remains disabled only under stateless bearer-token auth

Status:
- Documented in `docs/security-mvp.md` and in the Spring Security configuration.

Current decision:
- CSRF protection is intentionally disabled while the API is stateless and authenticated through explicit `Authorization: Bearer ...` tokens.
- Revisit before introducing cookie auth, form login, browser-managed sessions or credentials automatically attached by browsers.

---

## 5. MVP infrastructure/dev environment

### 5.1 First external dev deploy target

MVP need:
- A backend environment reachable from a physical Android device and installable APK.

Decision pending:
- Choose first external development deploy target.

Candidates:
- Render.
- Fly.io.
- Railway.
- Google Cloud Run.
- AWS App Runner.
- ECS Fargate.
- Managed PostgreSQL provider such as Neon, Supabase, Render PostgreSQL, Railway PostgreSQL or AWS RDS.

MVP recommendation:
- Prefer simple container platform + managed PostgreSQL before Kubernetes.

### 5.2 Dev deployment model

Before distributing APKs beyond local machine:
- Define runtime platform.
- Define managed PostgreSQL instance.
- Define Firebase service-account secret.
- Define environment variables.
- Define health check path.
- Define rollback strategy.
- Define which GHCR tag dev tracks.

### 5.3 Smoke check workflow

MVP task:
- Wire the manual `Smoke check` GitHub Actions workflow into the eventual dev deploy pipeline once the dev runtime platform exists.

Acceptance criteria:
- Smoke check runs against the deployed backend.
- `/actuator/health` and `/actuator/info` are aligned with deployed image metadata.

---

## 6. Explicitly deferred from MVP

The following are intentionally not MVP blockers:

- Real-time chat via WebSocket or SSE.
- Additional push notification event coverage beyond `VISUAL_REVIEW_AVAILABLE`.
- Google Sign-In / social auth providers.
- Reveal quotas.
- Advanced compatibility scoring.
- ML-based matching.
- Popularity, attractiveness or ELO-style ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full manual moderation workflow.
- Identity verification provider integration.
- Canonical country/city reference dataset.
- Geohash/spatial indexing.
- CDN/cache strategy for media.
- Application-level message encryption.
- Parallel matchmaking workers.
- Kubernetes/Helm/Terraform unless the chosen platform requires them.
