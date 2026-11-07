# Security Technical Debt

Deferred work only; Local Docker Network Hardening #1 changes host publication,
not these controls. Priorities are planning priorities: P0 = production release
gate where applicable, P1 = next hardening work, P2 = conditional/follow-up work.
They are not vulnerability severity ratings. NOT STARTED means no implementation
of the proposed task was verified in this review, not proof that no control
exists anywhere. PARTIAL means concrete controls exist but the stated acceptance
criteria are not fully verified. No deferred task is claimed IMPLEMENTED.

Evidence baseline: backend `development` at `1bf015c`, reviewed for this task.
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

## 8. Backend Object-Level Authorization (BOLA/IDOR) Tests

- Priority: P1.
- Component: backend user/profile/match/chat/media/admin endpoints.
- Evidence/status: PARTIAL. [Visual content boundaries](security-mvp.md#visual-content-boundaries)
  document participant checks; a complete endpoint/ownership test matrix was not
  audited. No missing authorization check is claimed.
- Risk: potential cross-user object access if an ownership path is untested.
- Proposal: inventory object identifiers and add focused owner/other-user/admin,
  blocked/deleted and cross-aggregate tests around existing rules.
- Deployment dependency: none; use isolated fixtures and existing auth model.
- Acceptance: each object endpoint has positive/negative ownership coverage;
  foreign identifiers reveal no private data and cause no unauthorized mutation.

## 9. Rate Limiting Behind Trusted Reverse Proxies

- Priority: P1.
- Component: backend rate-limit filters and ingress/container configuration.
- Evidence/status: PARTIAL. Existing filters/tests and [rate-limit boundaries](security-mvp.md#rate-limiting-boundaries)
  use `request.remoteAddr`; documentation requires trusted proxy configuration.
  Live proxy trust and replica behavior were not verified.
- Risk: potential shared proxy buckets, spoofed client attribution or replica
  quota bypass if infrastructure does not enforce the intended boundary.
- Proposal: validate trusted hops, strip untrusted forwarded headers at ingress,
  test real client-IP attribution and choose edge/distributed controls as needed.
- Deployment dependency: known proxy topology and replica count.
- Acceptance: spoofed headers cannot select a bucket; distinct clients are
  attributed correctly; quotas/429 behavior hold through the production ingress.

## 10. Private Media URL Expiration, Caching And Leakage Prevention

- Priority: P1.
- Component: backend S3 storage, Web/Android media clients, CDN/logging.
- Evidence/status: PARTIAL. `S3StorageService.kt` generates presigned reads;
  `S3StorageServiceTest.kt` checks browser endpoint/signature fields; local MinIO
  initialization sets the bucket private. Full cache/log/client behavior is unverified.
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
