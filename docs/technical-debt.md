# Technical Debt And Product Decisions

This file lists known pending or intentionally unimplemented behavior. Do not implement these implicitly while working on unrelated tasks.

## Product Decisions

- Guided first-chat questions or conversation starters.
- Whether guided questions belong to frontend or backend.
- Exact visibility rule for visual-review personal messages beyond current `VISUAL_APPROVED` enforcement.
- Whether second-chat explicit close should ever create penalties.
- Whether matchmaking should be processed by a scheduler/worker instead of the manual `/api/matchmaking/process` endpoint.

## Not Currently Implemented

- Real-time chat via WebSocket or SSE.
- Notification delivery.
- Reveal quotas.
- Advanced compatibility scoring.
- ML-based matching.
- Popularity, attractiveness or ELO ranking.
- Gamified reputation badges.
- Production trust score based on real behavior.
- Full Firebase/JWT production authentication flow.

## Infrastructure Gaps

- `pom.xml` includes Oracle and PostgreSQL drivers, but this repository currently does not include `application-dev.yml` or `application-prod.yml`.
- Local profile uses H2 file storage and disables Flyway.
- Maven CLI may be unavailable on the target machine; IntelliJ IDEA is the reliable local execution path.

## Code Notes To Revisit

- Some controller comments mention old or tentative behavior; prefer service implementation and these docs as the current source of truth.
- `TECH_DEBT.md` recovered from the previous setup was empty.
