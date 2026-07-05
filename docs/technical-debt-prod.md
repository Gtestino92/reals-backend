# TECH_DEBT_PROD

This file lists technical debt, production hardening tasks and long-term product/architecture decisions that are not required for MVP, but should be revisited before meaningful production traffic, store distribution, scaling or stricter trust/safety requirements.

Do not implement these implicitly while working on unrelated tasks.

---

## 1. Production product decisions

### 1.1 Reveal quotas

Decision pending:
- Whether to limit visual reveals, active second matches, or pending visual reviews.
- Whether quotas are daily, weekly, lifetime, or state-based.
- Whether unused reveals expire.

Production reason:
- Controls user overload.
- Prevents gaming.
- Supports the product goal of slower, more intentional matching.

### 1.2 Advanced compatibility scoring

Current behavior:
- Matching uses SQL hard filters plus rule-based compatibility scoring.

Future work:
- Add interest/affinity overlap if useful.
- Avoid popularity, attractiveness or ELO-style ranking.
- Keep scoring explainable enough to debug.

### 1.3 ML-based matching

Deferred:
- ML-based matching is not required until there is real usage data and a clear optimization target.

Constraints:
- Do not introduce attractiveness/popularity ranking.
- Avoid opaque ranking that conflicts with product principles.

### 1.4 Production trust score

Current behavior:
- Trust score evaluator is intentionally neutral.

Future decision:
- Define whether trust score should exist.
- Define inputs and weights:
  - penalty count;
  - penalty severity;
  - penalty recency;
  - abandonment rate;
  - repeated safety reports;
  - positive completion signals.
- Do not expose gamified reputation badges unless product direction changes.

### 1.5 Social authentication providers

Deferred:
- Google Sign-In and other social providers are post-MVP.

Future work:
- Add Google Sign-In through Firebase Auth.
- Define account linking and duplicate-email behavior.
- Ensure account deletion/reactivation works across providers.
- Backend should continue receiving Firebase ID tokens regardless of provider.

### 1.6 Firebase email verification

Decision pending:
- Whether backend should reject provisioning or profile activation when Firebase token has `email_verified=false`.

Future work:
- Add stable error such as `EMAIL_NOT_VERIFIED`.
- Define Android resend/refresh flow.
- Decide whether this is required only for production or also dev.

---

## 2. Identity verification

Current state:
- `Profile.identityVerified` exists.
- `Profile.identityVerificationStatus` is the richer persisted verification state.
- Identity verification endpoint exists.
- Provider abstraction exists.
- Current `none` provider returns `VERIFIED` for MVP/local compatibility only; it is not real external identity or age verification.

Production decision:
- Identity verification is separate from profile photos.
- Do not infer identity from person detection, full-body detection or visual approval.
- Decide whether production profile activation must set `PROFILE_IDENTITY_VERIFICATION_REQUIRE_FOR_ACTIVATION=true`.

Future implementation:
- Choose identity verification provider or internal verification flow.
- Define required inputs:
  - selfie;
  - liveness check;
  - document verification;
  - profile photo comparison;
  - or hybrid process.
- Store provider reference/audit metadata.
- Define retry/failure policy around `PENDING`, `REJECTED` and `NEEDS_REVIEW`.
- Define manual review flow for `NEEDS_REVIEW`.
- Define provider webhook/callback handling if provider verification is asynchronous.
- Define privacy and data-retention policy for provider artifacts.
- Define frontend UX.
- Decide whether identity verification is:
  - optional;
  - required for activation;
  - required only after trust/safety escalation;
  - required for certain user actions.

---

## 3. Profile photo validation and moderation

### 3.1 Future upload lifecycle

MVP shortcut:
- `validationStatus` now means technical upload validation only.
- `moderationStatus` is separate and currently uses provider `none`, which returns `APPROVED` without external review.
- Production can enable `PROFILE_PHOTO_REQUIRE_MODERATION_APPROVAL_FOR_ACTIVATION=true`, but a real provider is still pending.

Production target:
1. User uploads a photo.
2. Backend performs technical checks.
3. If upload succeeds, photo starts as `PENDING`.
4. Technical analysis updates:
   - `validation_status = VALIDATED` or `FAILED`;
   - `isPersonPhoto`;
   - `isFullBody`;
5. Content moderation updates `moderation_status`.
6. Optional provider/model/version metadata is recorded if a future provider needs it.

### 3.2 Future activation analysis

Decision pending:
- Whether activation-time analysis should process all photos or only enough photos to satisfy the required counts.

Option A:
- Analyze all profile photos before activation.
- Preferred if all photos will be visible to other users.

Option B:
- Analyze only until minimum activation requirements are satisfied.
- Remaining photos stay non-counting/non-visible until validated.

Preferred long-term direction:
- Analyze all photos that will be visible to other users.
- Avoid showing unvalidated photos in visual review.

### 3.3 Future moderation workflow for photos

Future work:
- Add admin ability to hide/reject/remove photos.
- Add external automatic image moderation for:
  - nudity;
  - explicit sexual content;
  - violence;
  - hate symbols;
  - minors/underage risk;
  - other prohibited content.
- Add person detection, full-body detection and face/person consistency if product requirements need them.
- Define whether rejected photos are deleted, hidden, quarantined or retained for audit.

### 3.4 Media storage production work

Future work:
- Object lifecycle rules.
- Orphan cleanup after replace/delete.
- Malware/content scanning if required.
- CDN/cache strategy.
- Thumbnail generation.
- Dimension normalization.
- Avoid proxying image bytes through backend unless intentional.

---

## 4. Safety and moderation

### 4.1 Full safety report workflow

Current state:
- Safety cancellation/report can record a report and apply a penalty.
- User-created reports can target chat, visual profile, personal message and profile-photo contexts when the backend can validate a real interaction.
- User-created reports automatically create a directional user block, and matchmaking treats a block in either direction as a bidirectional exclusion.
- Admin-created reports can be general `USER` context reports or contextual reports. Admin-created reports do not auto-block, auto-close chats or auto-apply penalties.
- Safety reports capture an evidence snapshot with message counts, timestamps and transcript hash, not a full transcript copy.
- Safety-relevant backend flows record `audit_events` with operational metadata only.
- Admin safety DTOs intentionally avoid raw email, Firebase UID and full `User` exposure.
- Pending/confirmed report counters are computed dynamically for the reported user.
- Full manual review workflow is not complete.

Production work:
- Admin queue for reports.
- Report status transitions.
- Moderator notes.
- Evidence/context view.
- Escalation policy.
- Appeal or correction policy if needed.
- Request context enrichment for audit events, including request id and hashed IP/user-agent if needed.
- Additional admin filters/pagination if report volume grows.
- Decide when admin-created reports should optionally create blocks or other containment actions.

### 4.2 User blocking and objectionable content controls

Future work:
- Clear user-facing block/report actions.
- User-facing manual block and unblock behavior.
- Explicit policy for objectionable profile photos and messages.
- Internal tooling to remove content and sanction users.
- Define and implement future `SYSTEM` safety report sources if automated detection creates reports.

### 4.3 Sensitive message data protection

Current MVP stance:
- Chat and personal message contents are stored as application text.

Before production:
- Define retention/deletion rules.
- Keep request/response bodies out of logs.
- Restrict and audit internal access.
- Evaluate application-level field encryption with keys stored outside the database.
- Define data export/deletion behavior for account deletion if required.

### 4.4 Backoffice production access hardening

Current state:

* Admin endpoints are exposed under `/api/admin/**`.
* Access is protected at application level with `ROLE_ADMIN`.
* Admin role is currently derived from configured admin email allowlist.
* There is no dedicated backoffice UI yet.
* CORS is not currently a meaningful protection for non-browser tools such as Bruno, Postman, curl, or internal scripts.

Before production backoffice usage:

* Define the production backoffice access model:

  * internal-only tooling;
  * browser-based admin UI;
  * VPN/Zero Trust protected access;
  * or public internet endpoint with stricter controls.
* If a browser-based backoffice UI is introduced, add restrictive CORS:

  * allow only the official admin origin, e.g. `https://admin.reals.app`;
  * allow dev/staging admin origins only in non-prod profiles;
  * do not use wildcard origins for admin endpoints;
  * keep `allowCredentials=false` while using Bearer tokens;
  * revisit CSRF if cookie-based admin sessions are ever introduced.
* Require MFA for all admin accounts through the chosen identity provider.
* Revisit admin role assignment:

  * current email allowlist is acceptable for MVP/dev;
  * production should consider Firebase custom claims, persisted admin roles, or another auditable role management mechanism;
  * avoid relying indefinitely on static config-only admin identity.
* Add admin-specific rate limiting:

  * protect `/api/admin/**` from accidental scripts, abusive clients, and brute-force-like usage;
  * keep limits separate from user-facing safety report rate limits.
* Consider network-layer restrictions for backoffice:

  * VPN;
  * Cloudflare Access / Zero Trust;
  * IP allowlist;
  * private ingress;
  * or equivalent platform-level access control.
* Keep application-level authorization even if network restrictions are added.
* Strengthen admin audit coverage:

  * record admin user id;
  * record action type;
  * record target aggregate/entity;
  * record result/success/failure;
  * record timestamp;
  * avoid storing raw sensitive content unless explicitly required;
  * avoid exposing raw email, Firebase UID, message bodies, report details, or storage keys in broad admin summaries.
* Add operational visibility for admin usage:

  * count admin requests by endpoint;
  * alert on repeated forbidden/unauthorized admin access;
  * alert on unusual admin activity volume;
  * monitor admin action failure rates.
* Define admin session/token operational policy:

  * expected token lifetime;
  * reauthentication expectations for sensitive actions;
  * behavior when admin access is revoked;
  * emergency admin lockout/revocation procedure.
* Document which admin actions are safe read-only actions and which are mutating/escalating actions.
* Consider requiring explicit confirmation or stronger authorization for high-impact actions:

  * confirming/dismissing safety reports;
  * applying sanctions;
  * removing content;
  * changing user status;
  * accessing sensitive evidence views.

Non-goals for MVP:

* Do not add a full admin role management system until there is real backoffice usage.
* Do not introduce cookie-based admin sessions unless there is a clear product/security reason.
* Do not treat CORS as a replacement for authentication, authorization, MFA, audit, or network-level access controls.


---

## 5. Location and geographic matching

### 5.1 Canonical country/city reference data

Future work:
- Validate profile `country` and `city` against a canonical dataset.
- Prefer reference endpoints such as:
  - `GET /api/reference/countries`;
  - optional city/region endpoints.
- Use ISO-3166 alpha-2 country codes for country identity.
- Avoid embedding global reference data in `GET /api/me/profile`.

### 5.2 Geographic search optimization

Future work:
- Improve geographic matching with:
  - geohash;
  - bounding boxes;
  - spatial database indexes;
  - location buckets;
  - region partitioning.
- Revisit if queue volume makes application-level Haversine filtering too expensive.

### 5.3 Accuracy meters policy

Decision pending:
- How `accuracyMeters` should affect matchmaking.

Future options:
- Reject imprecise locations.
- Expand effective radius for imprecise locations.
- Warn user but allow queue entry.
- Store and ignore, as currently.

---

## 6. Database, query and data-volume hardening

Likely first production bottleneck:
- PostgreSQL.

Before meaningful production traffic:
- Add query latency monitoring.
- Track slow queries.
- Review indexes for:
  - `matchmaking_queue`;
  - `profiles`;
  - `penalties`;
  - `matches`;
  - `connections`;
  - `chat_sessions`;
  - `chat_messages`;
  - `schedule_negotiations`;
  - `schedule_proposals`;
  - `active_engagement_locks`.
- Track connection pool usage.
- Track database CPU.
- Track lock wait time and deadlocks.
- Add pagination/cursor access where payloads can grow.

Important metrics:
- HTTP latency by endpoint.
- DB query latency.
- DB connection pool saturation.
- Deadlock count.
- Table growth.
- Queue sizes and job backlogs.

---

## 7.1 Matchmaking scalability

Risk:
- Current matchmaking job is safe but serialized.
- Queue backlog can grow if users enter faster than the job processes.

Before scale:
- Track matchmaking queue size.
- Track average time in queue before match.
- Track processed/skipped/failed pairs per run.
- Tune:
  - `scheduler.matchmaking-job.fixed-delay`;
  - `scheduler.matchmaking-job.max-pairs-per-run`.
- Add indexes based on actual query plans.
- Consider partitioning/filtering by:
  - location bucket;
  - intention;
  - gender/preference group;
  - age range.

Future option:
- Introduce parallel matchmaking workers only if backlog or latency requires it.
- Keep PostgreSQL `SKIP LOCKED`.
- Add stronger database constraints.
- Add concurrency tests for duplicate match prevention.

---

## 7.2 UserReliabilityScore cache and production scaling

`UserReliabilityScore` is initially computed from active `user_reliability_events`.

This is acceptable for MVP/early dev because:

* only recent events affect the score;
* the active scoring window is short;
* the expected number of reliability events per user is low;
* the score is not user-visible;
* the score does not require instant, event-by-event consistency.

The current design should keep reliability events as the source of truth.

```text
effectiveScore = baseScore + weightedSum(activeReliabilityEvents)
```

Temporal weighting must be applied at calculation time. Event deltas should not be mutated when they move from full weight to half weight.

Expected weighting model:

```text
0-9 days:
  100% weight

10-19 days:
  50% weight

20+ days:
  expired
```

Expired reliability events may be deleted by a scheduled cleanup job because long-term operational audit should live outside the scoring table.

### Future cache / materialized score

If matchmaking starts evaluating large candidate pools or score calculation becomes a measurable bottleneck, add a cache or materialized score table.

Suggested future table:

```text
user_reliability_score_cache
  user_id
  effective_score
  calculated_at
  valid_until
```

A simple TTL-based cache is acceptable. `UserReliabilityScore` does not require strict instant consistency. Small delays are acceptable because the score is used as a soft matchmaking modifier, not as a safety or access-control mechanism.

Recommended behavior:

```text
if cache exists and valid_until > now:
  use cached score

else:
  recompute score from active reliability events
  upsert cache row
```

The cache must be reconstructable from `user_reliability_events`.

### Cache invalidation rules

A future cache implementation should consider these invalidation triggers:

* new reliability event created;
* reliability event cleanup job deletes expired events;
* scoring configuration changes;
* cache TTL expires.

A minimal implementation may rely only on TTL-based lazy recomputation, as long as the resulting staleness is documented and bounded.

Do not implement a cache that can remain stale indefinitely.

### Matchmaking usage

Matchmaking should avoid per-candidate N+1 score queries.

Preferred MVP shape:

```text
getEffectiveScores(userIds: Set<UUID>): Map<UUID, Int>
```

This allows the matchmaking processor to fetch scores in batch for the current candidate pool.

If a cache is added later, the same service boundary should remain stable so matchmaking does not need to know whether scores are computed from events or read from a cache.

### Non-goals

Do not use `UserReliabilityScore` cache for:

* user-visible score display;
* safety/moderation sanctions;
* hard bans;
* permanent user classification;
* overriding core eligibility, blocks, locks, distance filters or compatibility constraints.

The reliability score should remain a bounded, recoverable, short-memory matchmaking modifier.


## 8. Home and polling pressure

Risk:
- Home is likely to become one of the most frequently requested endpoints.
- It aggregates match, chat, visual review, connection, scheduling, second-chat and blocked-state information.

Implemented first step:
- Full `GET /api/me/home` remains the source of truth and keeps its existing response contract.
- `GET /api/me/home/pending` returns lightweight pending/actionable navigation state for future polling without partner summaries or the full Home summary.
- `GET /api/me/home/status` returns a persisted per-user Home `version`, `dirty` flag and `serverTime`.
- Home-relevant state transitions bump the persisted version in PostgreSQL, so change detection works across multiple backend instances.
- Android can later poll `/api/me/home/status` and only call full Home when the version changes.

Before scale:
- Measure Home endpoint latency.
- Review generated queries.
- Avoid expensive repeated joins.
- Reduce polling once push notifications are available.
- Keep the persisted version semantics and invalidation hooks correct before introducing stronger caching or projections.

Future options:
- Full PostgreSQL read model/projection: add a `user_home_projection` table with precomputed pending/Home JSON or structured columns if full Home aggregation becomes expensive.
- Redis/Valkey distributed cache: cache `HomeResponse` or pending state per user and invalidate on Home status bumps after correctness and invalidation are stable.
- ETag / `If-None-Match` / `304`: use the Home version as the basis once version semantics are stable.
- Push-driven refresh: Firebase Cloud Messaging can notify clients to refresh Home status or full Home; polling should remain the fallback.
- Realtime transport: WebSocket or SSE should be considered only if chat/Home polling plus push is not enough, and must use shared pub/sub or managed infrastructure in multi-instance mode.

---

## 9. Chat scaling and realtime transport

### 9.1 Chat message scaling

Risk:
- Chat polling and message history can create high read volume.
- Fetching full message history does not scale well for long chats or frequent polling.

Future work:
- Add cursor-based pagination for chat messages.
- Prefer message IDs or `(sentAt, id)` cursors.
- Limit response sizes.
- Add indexes by `chat_session_id`, `sent_at`, and possibly `id`.
- Add metrics:
  - messages sent per minute;
  - messages fetched per minute;
  - active chats;
  - average message fetch latency.

### 9.2 Push notifications

Before larger production usage:
- Add Firebase Cloud Messaging support.
- Register device tokens.
- Handle token refresh.
- Add notification tap routing.
- Keep Home refresh as source of truth after notification taps.
- Keep polling as fallback.
- Reduce unnecessary polling once push is reliable.

### 9.3 Realtime transport

Not required for MVP.

Future options:
- WebSocket.
- Server-Sent Events.
- Managed realtime service.
- Push-notification-driven refresh.

If realtime chat is introduced:
- Use shared pub/sub or managed infrastructure.
- Do not rely on in-memory connection state when more than one backend instance exists.
- Keep REST polling fallback.
- Define reconnect behavior.
- Define missed-message recovery.
- Keep durable message persistence.

---

## 10. Lifecycle jobs and reconciliation

### 10.1 Lifecycle job hardening

Risk:
- Scheduled jobs can become bottlenecks when many records expire or transition at similar times.

Before scale:
- Process records in bounded batches.
- Ensure one bad record does not abort the entire batch.
- Add metrics for:
  - processed;
  - succeeded;
  - skipped;
  - failed;
  - duration;
  - backlog.
- Add indexes for status/deadline fields used by jobs.
- Add retry/no-op handling for expected stale states.
- Add alerts for stuck records.

Relevant domains:
- First-chat expiration.
- Visual-review expiration.
- Scheduling activation.
- Scheduling expiration.
- Second-chat availability.
- Second-chat expiration.
- Account deletion finalization.
- Penalty expiration/cleanup.

### 10.2 State consistency diagnostics

Future work:
- Add diagnostics for inconsistent or partially advanced states:
  - visual-approved match without connection;
  - connection without expected locks;
  - closed connection with remaining locks;
  - confirmed schedule without second-chat availability;
  - second-chat available but expired;
  - duplicated penalties;
  - stale queue rows for users with active engagements.

Production preference:
- State transitions should be idempotent.
- Re-running a transition should either complete missing dependent records or no-op safely.

---

## 11. Concurrency hardening

Before scale, add explicit concurrency tests for:

### 11.1 Mutual visual approval

Assert:
- only one connection is created;
- locks are upgraded once;
- repeated/competing approvals are idempotent.

### 11.2 Scheduling confirmation

Assert:
- only one confirmed negotiation result exists;
- only one second-chat availability transition occurs;
- repeated job runs are safe.

### 11.3 Second-chat materialization

Assert:
- only one second chat exists per connection;
- concurrent entry/send attempts do not create duplicates.

### 11.4 Chat cancellation and safety reports

Assert:
- terminal chat state is set once;
- penalties are not duplicated;
- exit requests are not duplicated.

### 11.5 Account deletion

Assert:
- active locks are removed;
- queue rows are removed;
- active engagements are closed;
- repeated deletion attempts return stable behavior.

### 11.6 Active engagement lock hardening

Future work:
- Document lock acquisition rule:
  - operations involving two users must lock both users in canonical user-id order.
- Ensure all new match/connection flows follow that rule.
- Add diagnostics for locks referencing terminal matches/connections.
- Add cleanup for orphaned locks.
- Consider additional database constraints where invariants can be expressed.

---

## 12. Observability and alerting

Before production scale:
- Add structured logs with request/job identifiers.
- Add metrics export.
- Add alerts for:
  - matchmaking backlog;
  - job failures;
  - high API latency;
  - DB connection pool saturation;
  - repeated deadlocks;
  - stuck negotiations;
  - stale active locks;
  - failed notification delivery;
  - elevated auth failures;
  - elevated chat send failures.

Core product metrics:
- Time to first match.
- First-chat completion rate.
- Visual approval rate.
- Scheduling confirmation rate.
- Second-chat attendance rate.
- Safety report rate.
- Penalty rate.
- Account deletion/reactivation rate.

---

## 13. Firebase Auth and request volume

Risk:
- Auth verification and local user lookup happen on many API requests, especially with polling.

Before scale:
- Confirm Firebase token verification does not perform unnecessary remote calls per request.
- Monitor auth filter latency.
- Monitor 401/403 rates by reason.
- Avoid Android refresh loops.
- Consider short-lived caching of local user/session metadata if DB reads become excessive.

---

## 14. Infrastructure and deployment hardening

### 14.1 Production deployment model

Before production:
- Choose runtime platform.
- Choose managed PostgreSQL.
- Define backup/restore strategy.
- Define secrets management.
- Define rollback strategy.
- Define health/readiness checks.
- Define release image tagging.
- Define dev/prod environment separation.

### 14.2 Release image tagging

Future work:
- Dev can use moving `development` and immutable `sha-*` tags.
- Production should publish immutable `v*` tags from Git tags, such as `v1.0.0`.

### 14.3 Infrastructure as Code

Decision pending:
- Whether infrastructure should be represented as IaC.

Recommendation:
- If first provider is AWS, prefer Terraform or AWS CDK.
- If first provider is Render, Fly.io or Railway, start with service config and documented manual steps until shape stabilizes.

### 14.4 Kubernetes / Helm

Current stance:
- Helm-style values under `deploy/helm` are placeholders.
- Kubernetes, Helm and Terraform/CDK become worthwhile when there are multiple services, networking rules, autoscaling needs or repeatable environment provisioning requirements.

---

## 15. Framework and dependency maintenance

Future maintenance:
- Keep Spring Boot on the latest stable `4.0.x` patch line until `4.1.x` is stable and release notes have been reviewed.
- Remove temporary `tomcat.version` override once Spring Boot manages a sufficiently patched Tomcat version.
- PostgreSQL remains the only supported non-local database driver unless a concrete environment requires another one.
- Local H2/file-storage profiles remain local-only; external environments should use PostgreSQL plus Flyway.

---

## 16. Security decisions for future auth models

### 16.1 CSRF

Current stance:
- CSRF protection is disabled while Reals remains a stateless API with explicit bearer tokens.

Future requirement:
- Re-enable and test CSRF before introducing:
  - cookie authentication;
  - form login;
  - browser-managed sessions;
  - credentials automatically attached by browsers.

### 16.2 Sensitive logs and audit

Production work:
- Define sensitive-field log policy.
- Keep tokens, chat contents, personal messages, full emails, private media URLs and raw request bodies out of logs.
- Add audit trails for moderation, identity verification and admin actions.

---

## 17. Code notes to revisit

Future cleanup:
- Some controller comments mention old or tentative behavior.
- Prefer service implementation and these docs as current source of truth.
- Remove or update comments once behavior stabilizes.


## clean up pre-mvp


### 1.1 First-chat guided questions

Decision pending:
- Whether first-chat guided questions/conversation starters are required for MVP.
- Whether the question set belongs fully to the frontend, fully to the backend, or backend-provided with frontend rendering.

MVP recommendation:
- Keep the first implementation simple.
- Prefer backend-owned predefined question IDs/texts if questions affect product analytics or future experimentation.
- Prefer frontend-owned static copy only if the set is temporary and not important for backend decisions.

Acceptance criteria:
- First chat can start with a predictable prompt or question.
- Users can continue chatting without being blocked by the question mechanic.
- The decision is documented so future chats do not fork behavior across app versions.


## 2. Backend MVP cleanup

### 2.2.1 Future profile photo ordering endpoint

MVP decision:
- Multipart profile photo upload remains slot-based and requires `position`.
- Do not implement drag-and-drop reordering as part of the MVP validation shortcut.

Future cleanup:
- Add a dedicated reorder endpoint, for example `PUT /api/me/profile/photos/order`, when the product needs real drag-and-drop ordering.
- The endpoint should accept ordered `photoIds`, validate that every photo belongs to the current user's profile, reject duplicate or missing photos, and reassign positions atomically.

Acceptance criteria:
- Upload and replace-file flows remain unchanged for Android.
- Reordering is handled by the dedicated endpoint rather than overloading upload semantics.

### 2.2.2 Post-MVP media pipeline options

MVP decision:
- Keep profile photo uploads backend-mediated.
- Keep original images only; no generated thumbnails or previews.

Post-MVP options:
- Consider direct-to-storage upload using presigned write URLs if backend bandwidth becomes a concern.
- Add generated thumbnails/previews when profile photo load performance needs it.

Acceptance criteria:
- These options remain separate from the MVP R2 setup.
- Endpoint contracts stay stable until Android and backend agree on a new media flow.


## Tech debt prod: cleanup de cuentas no verificadas

### Contexto

Reals permite que un usuario cree cuenta y avance parcialmente en onboarding antes de verificar su email. La verificación de email se exige como precondición para activar el perfil y entrar al flujo operativo real.

Esta decisión mejora el onboarding y evita bloquear la creación de cuenta prematuramente, pero deja una deuda operativa: pueden acumularse cuentas Firebase y usuarios locales que nunca verifican email ni llegan a activar perfil.

### Riesgo

Cuentas no verificadas pueden generar:

* acumulación de usuarios inactivos en Firebase Auth;
* acumulación de usuarios locales en base de datos;
* perfiles incompletos o en borrador sin valor operativo;
* posible consumo innecesario de storage si se permitieron fotos antes de verificar;
* ruido en métricas de adquisición/onboarding;
* superficie de abuso por creación masiva de cuentas no verificadas.

### Regla funcional actual recomendada

Mantener permitido antes de verificar email:

* sign-up/sign-in;
* provisioning backend;
* creación de perfil;
* edición de perfil;
* configuración de filtros;
* eventualmente carga de fotos para no friccionar onboarding MVP.

Bloquear antes de verificar email:

* activación de perfil;
* entrada a matchmaking;
* cualquier operación que haga visible/operativo al usuario dentro del flujo social.

### Cleanup propuesto para producción

Implementar un job periódico que detecte cuentas no verificadas e inactivas y las limpie o marque para limpieza.

Criterios tentativos:

* usuario local creado hace más de 14 o 30 días;
* email Firebase sigue sin verificar;
* perfil inexistente, DRAFT o INACTIVE;
* nunca activó perfil;
* nunca participó en matchmaking/chat/visual review/connection;
* no tiene reportes, auditoría crítica ni datos que deban preservarse.

### Acciones posibles

Opción A — Soft cleanup local:

* marcar usuario local como `DELETED` o estado específico `UNVERIFIED_EXPIRED`;
* anonimizar datos locales no necesarios;
* conservar auditoría mínima.

Opción B — Cleanup completo coordinado:

* borrar o deshabilitar usuario en Firebase Auth;
* marcar/borrar datos locales asociados;
* eliminar fotos/storage no usadas;
* registrar evento de auditoría técnico.

Opción C — Fase intermedia:

* primero marcar como “eligible for cleanup”;
* luego ejecutar eliminación final después de una ventana adicional.

### Consideraciones

* No borrar cuentas que hayan tenido actividad real del flujo social.
* No borrar cuentas vinculadas a reportes, bloqueos, auditoría de seguridad o disputas.
* Coordinar cleanup local con Firebase Auth para evitar estados inconsistentes.
* Evitar que un usuario pueda quedar en limbo: Firebase activo pero backend eliminado, o viceversa, sin mensaje claro.
* Agregar métricas: cuentas creadas, verificadas, expiradas, limpiadas, fallidas.
* Agregar rate limit/cooldown para reenvío de email de verificación antes de producción pública.
* Revisar si fotos deben estar permitidas antes de verificar email o si deben bloquearse en producción para reducir abuso de storage.

### Prioridad

No es bloqueante para MVP cerrado/local.
Debe resolverse antes de una beta pública amplia o producción abierta.
