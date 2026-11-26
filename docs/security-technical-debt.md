# Security Technical Debt

This register records deferred work and the outcome of the final DEV backend
hardening pass. Local Docker Network Hardening #1 addressed host publication.
Planning priorities are P0 for release gates, P1 for the next hardening work,
and P2 for conditional follow-up.
They are not vulnerability severity ratings. NOT STARTED means no implementation
of the proposed task was verified in this review, not proof that no control
exists anywhere. PARTIAL means concrete controls exist but the stated acceptance
criteria are not fully verified. No deferred task is claimed IMPLEMENTED.

Evidence baseline for the original register was backend `development` at
`1bf015c`. The final DEV pass used `origin/development` at `1f58337`.
Web observations are read-only snapshots, not a full Web audit; Android and live
production infrastructure were not audited. Potential risks below require
investigation and must not be reported as confirmed vulnerabilities.

## 1. Web CSP And Browser Security Headers

- Priority: P1.
- Component: reals-web; Web hosting/reverse proxy.
- Evidence/status: NOT STARTED. Web Vite configuration was reviewed for proxy
  compatibility only; deployed CSP/header coverage was not verified.
- Risk: potential script execution or framing exposure if browser defenses are
  absent; no exploitable injection or missing deployed header is established.
- Proposal: inventory deployed headers, define CSP from actual resource origins,
  trial report-only CSP, then enforce CSP and appropriate framing, MIME and
  referrer policies without breaking Firebase or media.
- Deployment dependency: Web host/CDN response-header configuration and reporting.
- Acceptance: automated deployed-response checks plus Firebase login, media and
  navigation tests pass with an enforced documented policy.

## 2. HTTPS, HSTS And Production CORS Policy

- Priority: P0 before public production exposure.
- Component: Web/backend ingress and deployment configuration.
- Evidence/status: NOT STARTED for this deployment-wide verification. Local
  Compose intentionally uses HTTP; this does not establish production exposure.
- Risk: potential interception, downgrade or unintended cross-origin access if
  deployed transport/origin policy is misconfigured.
- Proposal: inventory TLS termination and redirects; validate explicit production
  origins/methods/headers, reject unapproved origins, introduce HSTS after all
  affected HTTPS hosts are ready. Do not apply production TLS rules to local ADB.
- Deployment dependency: DNS, certificates, trusted ingress and Web domains.
- Acceptance: HTTPS/redirect/certificate checks pass; allowed and denied CORS
  preflights tested; HSTS policy and subdomain implications documented and tested.

## 3. XSS Audit And Untrusted URL Handling

- Priority: P1.
- Component: reals-web rendering, navigation and media consumers.
- Evidence/status: NOT STARTED. No comprehensive rendering/URL audit performed.
- Risk: potential execution/navigation through unsafe sinks or URL schemes;
  no XSS vulnerability confirmed.
- Proposal: enumerate backend/user-controlled text and URL sinks, retain React
  text rendering, validate allowed URL schemes/origins where appropriate and
  avoid raw HTML unless a separately reviewed sanitization design requires it.
- Deployment dependency: final media and authentication origins for allowlists.
- Acceptance: sink inventory reviewed; malicious text, javascript/data URLs and
  unexpected origins covered by regression tests without rewriting signed URLs.

## 4. Firebase Token Persistence And Browser Session Threat Model

- Priority: P1.
- Component: reals-web authentication/session lifecycle.
- Evidence/status: PARTIAL. Read-only review of Web `src/auth/firebase.ts`
  confirms Firebase `browserLocalPersistence` and SDK sign-out. This is an
  explicit persistence choice, not evidence of token theft or insecure storage.
- Risk: potential session theft/reuse on shared devices or after script compromise.
- Proposal: document trust boundaries, persistence choice and session lifetime;
  evaluate shared-device behavior, sign-out, revocation and multi-tab handling.
- Deployment dependency: Firebase project settings and agreed product session UX.
- Acceptance: reviewed threat model plus reload, multi-tab, sign-out, revocation
  and expired-token tests; no tokens in logs, URLs or unintended storage.

## 5. CSRF Reassessment If Cookie-Based Authentication Is Introduced

- Priority: P2; P0 gate before introducing automatic browser credentials.
- Component: backend security chain and future Web authentication changes.
- Evidence/status: NOT STARTED for conditional reassessment. Verified
  `SecurityConfig.kt` disables CSRF with stateless bearer authentication;
  [current rationale](security-mvp.md#csrf-and-stateless-bearer-auth) is documented.
- Risk: conditional CSRF if cookie/session authentication is later added without
  revisiting the current assumption; no current CSRF vulnerability established.
- Proposal: require a security design for cookies, SameSite, CSRF tokens and
  origin validation before adopting browser-attached authentication.
- Deployment dependency: only if the authentication model changes.
- Acceptance: threat model updated and cross-site mutation tests reject requests
  without valid CSRF proof while intended authentication flows still work.

## 6. Firebase App Check Integration For Web

- Priority: P1.
- Component: reals-web Firebase setup; backend App Check; Firebase console.
- Evidence/status: PARTIAL across components. Backend verifier/filter and focused
  tests exist; [App Check boundary](security-mvp.md#firebase-app-check-boundary)
  is documented. Reviewed Web auth setup does not initialize Web App Check;
  deployed Web attestation/enforcement was not verified.
- Risk: potential automated/unofficial client abuse; App Check does not replace
  authentication, authorization or rate limiting.
- Proposal: choose/configure a Web provider, send App Check tokens, handle
  refresh/failure and roll out backend App ID allowlists/enforcement gradually.
- Deployment dependency: Firebase Web registration/provider and domain settings.
- Acceptance: real Web tokens accepted; missing/invalid tokens handled as defined;
  local debug setup and Android compatibility verified without debug secrets in Git.

## 7. Dependency Vulnerability Scanning And Supply-Chain Controls

- Priority: P1.
- Component: backend/Web/Android dependencies, images and CI.
- Evidence/status: PARTIAL. Backend `.github/workflows/ci.yml` has Trivy image
  scans and high-severity PR dependency review. Compose still uses MinIO `latest`
  tags; workflow actions include version tags. No vulnerable version is asserted.
- Risk: potential vulnerable dependencies or mutable upstream artifacts.
- Proposal: inventory existing gates across repos, automate updates/scans, review
  digest/SHA pinning, SBOM/provenance and exception/patch ownership.
- Deployment dependency: CI permissions, artifact registry and release process.
- Acceptance: repeatable scans and provenance records, tested severity gates,
  reviewed pins and time-bounded exceptions; coverage documented for all repos.

## 8. Backend Object-Level Authorization (BOLA/IDOR)

### Resolved in the final DEV pass

- **Medium, private audio after closure.** Exact audio-send replays and existing
  HEART replays could reach URL signing after CLOSED even though chat history
  rejected the same access. Participant audio responses now apply the existing
  chat history policy before signing and check current state again after
  signing. Both history formats also reject a stale response if the chat closes
  during construction. A block observed during signing withdraws counterpart
  audio from the response. PostgreSQL coverage includes committed fixtures and
  state changes at the signing boundary.
- **Low, unrelated photo ID disclosure.** A participant submitting a photo
  report could distinguish an existing unrelated photo ID from a missing one by
  the validation message. The lookup now requires the matched partner's profile
  ID and produces the same public error in both cases. Administrative evidence
  continues to use its privileged path.

### Reviewed controls with no additional access defect confirmed

| Surface | Boundary reviewed |
| --- | --- |
| Chat messages, exits, reactions and replies | Match membership on direct chat access, same-chat cursor/message/reply/exit IDs, sender-scoped idempotency, and separate text/history behavior |
| Matches, connections and scheduling | Participant checks, visual access by phase and block state, same-connection proposal and completion-request IDs, and actor/transition checks |
| Profiles and photos | Principal-owned self-service profiles and their photos, partner profile derived from authorized visual access, and separate admin moderation |
| Safety reports and evidence | Report target inferred from interaction membership, parent/child context validation, and backoffice-only access to persisted evidence |
| Provisioning and account lifecycle | Firebase UID or authenticated backend user, provider binding, and restricted deleted-account and permanent-ban routes |
| Hosted DEV tooling and backoffice | Exact DEV profile and opt-in flags, verified token and App Check app, allowlisted active actor, MFA grant binding and scope. Password grants remain scoped to DEV tooling. |

The [phase-1 assessment](authorization-assessment-phase1.md) describes the existing
negative coverage and retained historical scheduling policy. Existing signed
URLs are bearer references until expiry. Changes committed after the final
authorization read can overlap transmission of an already built response.
Storage retention and administrative evidence remain available under their
existing policies.

## 9. Rate Limiting Behind Trusted Reverse Proxies

- **Medium, shared proxy buckets.** The repository previously used the socket
  peer for both pre-authentication and provisioning. On a reverse-proxy path
  with forwarding disabled this groups legitimate users by the proxy peer.
  Railway DEV metadata showed one replica in SFO, a Railway-managed HTTP
  networking domain on port 8080, and zero public TCP proxies.
- **Implemented.** Both paths use the same client IP resolver with opt-in,
  exact direct-peer trust. Only one numeric `X-Real-IP` from an explicitly
  trusted peer is accepted. A missing, malformed or duplicate value falls back
  to the socket peer. Client-supplied forwarding headers on direct requests
  cannot select a bucket. Post-authentication keys and endpoint quotas retain
  their existing behavior.
- **Deferred activation.** Railway documents `X-Real-IP` at its public edge.
  Service metadata does not establish stable trusted socket peers or overwrite
  behavior on all routes. Leave the allowlist empty until that is verified.
  Verify the independent AWS ingress before enabling its dedicated password-reset
  or persistent provisioning quotas. Multi-replica enforcement remains a
  production follow-up if deployment changes.
  [Ingress verification](security-mvp.md#client-ip-verification-gap-and-activation-prerequisite)

## 10. Private Media URL Expiration, Caching And Leakage Prevention

- Priority: P1.
- Component: backend S3 storage, Web/Android media clients, CDN/logging.
- Evidence/status: PARTIAL. Uploads and replacements now persist
  `Cache-Control: no-store`. Existing objects require an operator-controlled
  backfill. Hosted R2/CDN verification, client cache isolation, hosted log
  redaction and issued-URL revocation remain separate follow-ups.
  [Storage rollout and operator procedure](storage-r2-configuration.md#rollout-and-existing-objects)
- Risk: potential bearer-URL disclosure or access beyond the intended cache window.
- Proposal: review TTLs, response/referrer/cache policies, analytics/log redaction
  and client persistence; document revocation limitations for already-issued URLs.
- Deployment dependency: object storage/CDN configuration and log pipelines.
- Acceptance: expiry tested against storage; private objects reject anonymous
  reads; signed URLs absent from logs/analytics and durable client caches;
  authorized media continues to load without URL rewriting.

## 11. PWA Service Worker And Offline Storage Security

- Priority: P2; review before enabling offline private data.
- Component: reals-web future/current PWA and browser caches.
- Evidence/status: NOT STARTED. Service Worker registration and offline cache
  behavior were not audited; this does not assert that a worker currently exists.
- Risk: conditional persistence of private content across logout/account changes.
- Proposal: inventory workers/caches, classify offline data, exclude credentials
  and private responses by default; design update/logout/account-switch cleanup.
- Deployment dependency: PWA rollout and HTTPS hosting if enabled.
- Acceptance: worker/cache inventory complete; offline, update, sign-out and
  account-switch tests demonstrate no cross-account private-data exposure.

## 12. Production Secrets, Logs And Sensitive-Data Exposure

- Priority: P0 before public production exposure.
- Component: backend/Web/Android builds, CI, secret stores and observability.
- Evidence/status: PARTIAL. [Security hygiene](security-mvp.md) defines secret
  and sensitive-log policies; Compose mounts a local Firebase file read-only.
  No secrets were inspected; deployed stores, logs and built artifacts unaudited.
- Risk: potential credential/PII leakage or excessive secret access; no leak claimed.
- Proposal: audit secret injection/access/rotation, public client configuration,
  redaction and retention; add secret scanning and safe log checks.
- Deployment dependency: secret manager, CI permissions and log access/retention.
- Acceptance: inventory/owners and rotation procedure verified; scanning gates
  active; sampled sanitized logs/artifacts contain no tokens, private media URLs,
  passwords, service-account contents or personal message text.

## 13. Automated DAST And Security Regression Testing

- Priority: P1.
- Component: backend/Web CI and isolated deployed test environment.
- Evidence/status: PARTIAL. Backend security exposure, Firebase/App Check and
  rate-limit tests exist; CI runs tests. No DAST stage was identified in reviewed
  backend CI/smoke workflows; authenticated DAST coverage is unverified.
- Risk: potential regressions across deployment/browser/API boundaries.
- Proposal: establish an isolated seeded target with synthetic identities, run
  scoped authenticated DAST, triage findings and add focused regression tests.
- Deployment dependency: disposable environment, test accounts and scan scope.
- Acceptance: repeatable non-production scan, no real-user mutations, severity
  gates with reviewed exceptions and regression coverage for confirmed findings.


## 14. Provisioning quota activation and follow-up

- Priority: P1 before activation; advanced detection P2.
- Trusted client IP: production ingress/Nginx/Docker/container trust remains
  unverified. Complete the runtime checks in `security-mvp.md` before enabling
  persistent provisioning quotas. Defaults intentionally remain disabled.
- Tune 3/day and 5/week from real traffic and false-positive measurements; monitor
  shared Wi-Fi, VPN, mobile CGNAT and IPv6 /64 grouping and rotating-prefix bypass.
- Linked-account and ban-evasion detection remains deferred. IP quotas cannot
  prove unique people or prevent all abuse.
- Define HMAC key rotation/compromise procedures: changing keys resets effective
  counters; coordinate all instances and retention/window changes.
- Review Firebase identity lifecycle and Web/Android provisioning rejection UX.
  Acceptance: 429 preserves the external identity/session, delays retries using
  `Retry-After`, and never enters the backend application without provisioning.
- Verify cleanup operation/retention when disabled or retired; automatic cleanup
  currently runs only while enforcement and scheduling are enabled.

## Administrative MFA integration follow-up

Backend Google + TOTP grants replace email-only ADMIN derivation; see [runbook](admin-auth.md).
Web App Check initialization, Google reauthentication UX, in-memory grant handling and explicit
logout revocation remain separate Web work. No backend bypass accommodates that unfinished work.
Actuator info/metrics are denied. Hosted Dev tooling requires explicit enablement and the same verified administrative credentials.
Operational gates: verify real Firebase revocation, Web attestation, synchronized secret rollout,
trusted client-IP attribution, and retention execution in the deployment. No physical-device identity
is claimed. No automated recovery or administrator enrollment is offered.
