# Deployment Inventory and AWS Dev Plan

Retrieval date for external facts: 2026-07-21.
Deployment automation update: 2026-07-23.

Scope:
- Backend repository: `Gtestino92/reals-backend`, inspected locally from `development`.
- Android repository: `Gtestino92/reals-app`, inspected locally as sibling repository from `development`.
- Target: first hosted Reals `dev` environment on AWS.
- Non-action: no AWS resources, deployments, secrets, commits, branches, PRs, or remote Git state were created or changed.

## 1. Executive summary

Current deployment readiness:
- The backend is containerizable and has a cloud-oriented `dev` Spring profile with PostgreSQL, Flyway, Firebase Admin/Auth, App Check modes, S3-compatible storage, schedulers, ShedLock, Actuator health probes, and GHCR image publication. Evidence: `Dockerfile`, `.github/workflows/ci.yml`, `src/main/resources/application-dev.yml`, `docs/dev-deployment.md`.
- The repository now includes manual AWS dev deployment automation through GitHub Actions OIDC, AWS Systems Manager Run Command, and an EC2 Docker container replacement script. AWS resources are still managed outside this repository. Existing Helm values remain documented placeholders, not an active Kubernetes deployment design. Evidence: `.github/workflows/deploy-aws-dev.yml`, `ops/aws/deploy-backend.sh`, `docs/aws-dev-deployment.md`.
- Android has isolated `local`, `dev`, and `prod` flavors. `dev` builds require a real non-placeholder HTTPS backend URL, a flavor-specific Firebase config for `com.reals.app.dev`, Play Integrity App Check, and release signing for `devRelease`. Evidence: `../reals-app/app/build.gradle.kts`, `../reals-app/docs/infra.md`, `../reals-app/docs/testing.md`.

Largest remaining unknowns:
- AWS account plan, remaining credits, selected Region, monthly budget, domain/subdomain, DNS owner, and acceptable dev uptime.
- Whether the operator prefers lower-cost EC2 operations or managed-service simplicity.
- Whether dev PostgreSQL may be colocated temporarily or should use RDS from the start.
- Whether the account is on the post-July 15, 2025 credit-based Free Plan, whether credits will last through the intended dev window, and whether the operator will upgrade to Paid before account closure if hosted dev data must continue.
- Firebase dev project layout, Play Console availability, Android signing-key custody, and whether `devRelease` will be distributed through Google Play Internal Testing/Internal App Sharing.

Recommended first hosted-dev direction:
- With `BACK-AWS-0` implemented in code, start with one `linux/amd64` EC2 instance running the backend container, Amazon RDS PostgreSQL when budget allows, one private Amazon S3 bucket, ECR or GHCR as the image registry, and one explicit TLS path. For lowest fixed cost, provisionally prefer EC2 host TLS with Caddy/Nginx and ACME/Let's Encrypt; use ALB plus a normal ACM public certificate only if the operator accepts ALB and public IPv4 cost. If the budget is very tight, use the same x86_64 EC2 instance for the container and a colocated PostgreSQL volume only as a temporary dev compromise.
- The selected dev deployment shape is GitHub Actions -> GitHub OIDC -> AWS deployment role -> SSM Run Command -> EC2 Docker container -> Nginx HTTPS -> private RDS PostgreSQL -> private Amazon S3. The repository does not create these AWS resources.

Why this is proportionate:
- The backend currently expects one JVM process with in-process rate limiting, database-backed ShedLock, a single S3-compatible object store, and no evidence of traffic requiring autoscaling. Evidence: `src/main/kotlin/com/reals/backend/config/security/ratelimit/RateLimitProperties.kt`, `src/main/kotlin/com/reals/backend/config/ShedLockConfig.kt`, `src/main/resources/application-dev.yml`.
- ECS/Fargate and App Runner are viable later, but they add fixed networking/runtime decisions before Reals has hosted-dev operating evidence.

Decisions that must remain open until AWS account and Region constraints are verified:
- Whether the AWS account was created before or after the July 15, 2025 Free Tier transition. Newer accounts use the credit-based Free Plan model, not the legacy 12-month RDS allowance model.
- Whether the Free Plan service catalog and credits cover each selected service in the selected Region and account state.
- Whether the operator will upgrade to Paid before six months or credit exhaustion if hosted dev resources and data must remain accessible. AWS states Free Plan accounts close at that point, workload resources become inaccessible, and account content is retained for 90 days before permanent deletion. Sources retrieved 2026-07-21: AWS Free Tier and AWS Billing Free Tier plan docs.
- Whether public IPv4, NAT Gateway, ALB, RDS, CloudWatch Logs, Route 53, Secrets Manager, and ECR costs fit the operator's monthly budget.

## 2. Current-state deployment inventory

| Concern | Observed implementation | Authoritative file(s) | Dev readiness | Missing decision or gap | Security sensitivity |
| --- | --- | --- | --- | --- | --- |
| Backend runtime/JVM | Kotlin/Spring Boot app, Java 21 target, Spring Boot `4.0.6`, Kotlin `2.3.21`, JVM target 21. | `pom.xml`, `src/main/kotlin/com/reals/backend/RealsBackendApplication.kt` | Ready for Java 21 container/runtime. | Confirm AWS runtime image/instance supports Java 21. | Medium |
| Docker image | Multi-stage Temurin 21 JDK build, Temurin 21 JRE runtime, non-root `reals` user, exposes `8080`, excludes `secrets`. CI publishes with ordinary `docker build` on GitHub-hosted `ubuntu-latest`; no explicit `--platform`, Buildx multi-platform build, or manifest list is configured. | `Dockerfile`, `.dockerignore`, `.github/workflows/ci.yml` | Ready to build for the runner platform. Treat current published images as `linux/amd64`. | Select x86_64 runtime initially, or add future multi-platform CI before using Graviton/ARM64. | Medium |
| Image registry | CI publishes `ghcr.io/<owner>/reals-backend:<branch>` and `sha-<shortsha>` on push. | `.github/workflows/ci.yml`, `docs/dev-deployment.md` | Ready if runtime can pull GHCR. | Decide GHCR vs ECR for AWS. | Medium |
| HTTP port | `server.port=${PORT:8080}` and Docker exposes `8080`. | `src/main/resources/application.yml`, `Dockerfile`, `docs/dev-deployment.md` | Ready. | AWS health listener/target port must map to `8080` or set `PORT`. | Low |
| Health/liveness/readiness | Actuator health and probes enabled in `dev`/`prod`; public `/actuator/health/**`; smoke checks readiness and `/api/ping`. | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml`, `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `.github/workflows/smoke.yml` | Ready for health checks. | Need AWS target health path. | Low |
| PostgreSQL | `dev`/`prod` require `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; local compose uses PostgreSQL 16. | `src/main/resources/application-dev.yml`, `docker-compose.yml` | Ready against PostgreSQL. | Choose RDS vs colocated PostgreSQL for dev. | High |
| Flyway | Enabled in `dev`/`prod`; 30 migrations currently present; V1 creates `pgcrypto` and `shedlock`; latest migration is V30 by version number. | `src/main/resources/application-dev.yml`, `src/main/resources/db/migration/` | Ready, but startup migrations are operationally sensitive. | Define backup and rollback before migrations. | High |
| Connection pool | Hikari settings in `dev`/`prod`: 5s connection timeout, 2s validation timeout, 30m max lifetime, 5m keepalive. | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Ready for small dev. | Tune only after observing RDS/EC2 latency. | Medium |
| Object storage | AWS SDK S3 client and presigner support `STATIC` credentials for MinIO/R2/S3-compatible providers and `DEFAULT_CHAIN` for native AWS runtime credentials. Endpoint override is optional; presigner endpoint precedence is presigned endpoint, main endpoint, then AWS regional endpoint. | `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`, `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Code-ready for native Amazon S3 configuration and existing MinIO/R2 behavior. | AWS IAM role, bucket policy, and real EC2/ECS runtime validation remain future AWS-1/AWS-2 work. | High |
| Presigned photo URLs | Read URLs use `PRESIGNED` or `PUBLIC`; `prod` rejects `PUBLIC`; presigned duration defaults to 15 minutes. | `src/main/kotlin/com/reals/backend/service/S3StorageService.kt`, `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt` | Ready for private object reads. | Configure S3 virtual-host/path-style behavior for AWS. | High |
| Media cleanup | DB-backed cleanup tasks delete storage objects outside active DB transactions; scheduled `MediaCleanupJob` processes due tasks. | `src/main/kotlin/com/reals/backend/service/MediaCleanupTaskService.kt`, `src/main/kotlin/com/reals/backend/service/MediaCleanupProcessor.kt`, `src/main/kotlin/com/reals/backend/scheduler/MediaCleanupJob.kt`, `src/main/resources/db/migration/V29__media_cleanup_tasks.sql` | Ready. | Need alarm/log review for failed cleanup tasks. | Medium |
| Firebase Admin | Active in `local-firebase`, `dev`, `prod`; credentials loaded from path, raw JSON, base64, or Google ADC. | `src/main/kotlin/com/reals/backend/config/firebase/FirebaseConfig.kt`, `src/main/resources/application-dev.yml` | Ready. | Choose secret injection method; avoid service-account file in image. | High |
| Firebase Authentication | Firebase token filter/provider used for real auth profiles; `/api/me/provision` requires Firebase-authenticated or user role. | `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `src/main/kotlin/com/reals/backend/config/security/authentication/` | Ready. | Need Firebase project and Android app alignment. | High |
| Firebase App Check | Modes `DISABLED`, `MONITOR`, `ENFORCED`; `dev` defaults `DISABLED`, `prod` defaults `ENFORCED`; prod startup requires enforced mode, project number, allowed app IDs, and valid JWKS URI. | `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckProperties.kt`, `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckConfig.kt`, `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckFilter.kt`, `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Ready for staged dev rollout. | Need real Firebase project number and app ID allowlist. | High |
| FCM sender and tokens | Backend stores push tokens in PostgreSQL and sends FCM via Firebase Admin in `local-firebase`, `dev`, `prod`. | `src/main/kotlin/com/reals/backend/domain/PushDeviceToken.kt`, `src/main/kotlin/com/reals/backend/service/PushDeviceTokenService.kt`, `src/main/kotlin/com/reals/backend/service/notification/sender/FirebasePushNotificationSender.kt`, `src/main/resources/db/migration/V10__push_notifications.sql` | Ready. | Need remote-device validation with app backgrounded and USB disconnected. | High |
| Schedulers | Scheduler enabled by default and in `dev`/`prod`; local profiles mostly disable background jobs for deterministic testing. | `src/main/kotlin/com/reals/backend/config/SchedulingConfig.kt`, `src/main/resources/application.yml`, `src/main/resources/application-dev.yml`, `src/main/resources/application-local-firebase.yml` | Ready for one backend instance. | Review fixed delays for dev cost/noise. | Medium |
| ShedLock | JDBC lock provider on `shedlock` table, disabled only for `test`. | `src/main/kotlin/com/reals/backend/config/ShedLockConfig.kt`, `src/main/resources/db/migration/V1__init.sql` | Ready for one instance and future small horizontal scale. | None for one instance. | Medium |
| Rate limiting | In-memory Caffeine token buckets; pre-auth by remote address, post-auth by user/Firebase/local-dev principal; production proxy must preserve real client IP at servlet layer. | `src/main/kotlin/com/reals/backend/config/security/ratelimit/`, `src/main/resources/application.yml`, `docs/commons/api.md` | Acceptable for one instance. | Multi-instance rate limiting would require shared storage or edge limiter. | Medium |
| Admin endpoints | `/api/admin/**` requires `ROLE_ADMIN`; admin role comes from verified Firebase email allowlist and provisioned active user. | `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `docs/commons/api.md` | Ready with careful allowlist. | Need `BACKOFFICE_ADMIN_EMAILS` value and admin bootstrap process. | High |
| Local-dev endpoints | `/api/local-dev/**` is public only in local execution profiles, admin-only in `dev`, denied elsewhere. | `src/main/kotlin/com/reals/backend/config/environment/EnvironmentExposurePolicy.kt`, `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `docs/dev-deployment.md` | Acceptable for dev if admin-only. | Ensure Android clients never call these endpoints. | High |
| Observability | Actuator `health`, `info`, `metrics` exposed; `info`/`metrics` admin-only; Micrometer is present via Actuator; App Check records counters when meter registry exists. | `src/main/resources/application.yml`, `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckFilter.kt` | Minimum readiness exists. | Hosted logs/metrics retention and alarms not defined. | Medium |
| Logs | Root `INFO`, request ID in log level pattern, stdout container logs expected. | `src/main/resources/application.yml`, `src/main/kotlin/com/reals/backend/config/filter/RequestCorrelationFilter.kt`, `docs/data-retention.md` | Ready for CloudWatch/container logs. | Need log retention and sensitive-data review. | High |
| Metrics | Actuator metrics endpoint exists but documentation notes current application metrics are not a full observability solution. | `src/main/resources/application.yml`, `docs/data-retention.md` | Minimal. | Add CloudWatch alarms and decide custom metrics later. | Medium |
| Backups | No repository-defined backup mechanism; data-retention doc marks database/object backup retention unresolved. | `docs/data-retention.md`, `docs/technical-debt-prod.md` | Not ready without operator decision. | Define DB backup, restore drill, S3 versioning/lifecycle. | High |
| Secrets | Config uses environment variables and Docker local bind-mounted secret; `.dockerignore` excludes secrets and Firebase credentials. | `src/main/resources/application-dev.yml`, `docker-compose.yml`, `.dockerignore` | Ready if external secret store is used. | Choose SSM Parameter Store vs Secrets Manager; no committed secret values. | High |
| DNS | Placeholder Helm hosts and Android placeholder URLs exist; no real domain selected. | `deploy/helm/values-dev.yaml`, `../reals-app/app/build.gradle.kts` | Not ready. | Choose dev subdomain/domain owner. | Medium |
| TLS | Android dev/prod validation requires HTTPS and rejects placeholders/local hosts. Backend itself serves HTTP in container. | `../reals-app/app/build.gradle.kts`, `Dockerfile` | Needs a supported TLS path. | Choose EC2 host TLS with ACME/exportable cert, ALB TLS with normal ACM public cert, or another managed HTTPS runtime; do not assume a normal non-exportable ACM cert can be installed on EC2. | High |
| Domain ownership | No observed real Reals domain in repositories. | `deploy/helm/values-dev.yaml`, `../reals-app/app/build.gradle.kts` | Not ready. | Operator must provide/choose domain. | Medium |
| CI | Backend tests, Docker compose config, image build, Trivy scan, dependency review, GHCR publish; smoke workflow is manual against a URL. | `.github/workflows/ci.yml`, `.github/workflows/smoke.yml` | Build/publish ready; deploy intentionally manual. | Operator must wait for CI success before running deployment. | Medium |
| Deployment | Manual `Deploy AWS Dev` workflow resolves an immutable `sha-<shortsha>` image, assumes AWS role through OIDC, sends the repository-owned script over SSM with bounded polling, and validates public readiness and ping after SSM succeeds. `development` is the repository default branch, so the manual workflow is registered directly from `development:.github/workflows/deploy-aws-dev.yml`. | `.github/workflows/deploy-aws-dev.yml`, `ops/aws/deploy-backend.sh`, `docs/aws-dev-deployment.md` | Automation ready after GitHub Environment and AWS role/host prerequisites are configured. | Real AWS OIDC role, EC2 Name tag, SSM-managed host, Nginx, Docker, and runtime env file remain manual setup. | Medium |
| Rollback | Runtime script captures the previous container image ID before replacement and automatically recreates it if the new container fails to start or internal container health checks fail. Explicit rollback can deploy an older `development` ancestor SHA. | `.github/workflows/deploy-aws-dev.yml`, `ops/aws/deploy-backend.sh`, `docs/aws-dev-deployment.md` | Application-image rollback automated for container startup/internal health failures. | Public HTTPS smoke failure does not automatically roll back; inspect Nginx/TLS/DNS/routing before explicit rollback. Flyway rollback still requires migration compatibility discipline, restore drill, or forward-fix image. | High |
| Android dev artifact | `dev` flavor app ID is `com.reals.app.dev`, app name `Reals Dev`, HTTPS-only, Play Integrity provider. | `../reals-app/app/build.gradle.kts`, `../reals-app/app/src/dev/res/values/strings.xml`, `../reals-app/app/src/dev/res/xml/network_security_config.xml`, `../reals-app/app/src/dev/java/com/reals/app/core/appcheck/AppCheckInstaller.kt` | Requires real Firebase JSON and URL. | Create dev Firebase app and backend URL. | High |
| Android signing | Release signing uses either Gradle keystore path or base64 keystore plus passwords/alias; CI skips dev/prod release without full signing inputs. | `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml` | Ready once signing is provided. | Signing-key custody and Play signing strategy. | High |
| Android Firebase config | Flavor-specific `google-services.json` paths are supported; local file exists, dev/prod files are missing locally. | `../reals-app/app/build.gradle.kts`, `../reals-app/docs/local-development.md` | Not ready for hosted dev. | Supply `app/src/dev/google-services.json` through secure local/CI path. | High |
| Android distribution | CI uploads local APK/mapping artifacts; devRelease only builds when configured and signed; no Play distribution automation observed. | `../reals-app/.github/workflows/ci.yml`, `../reals-app/docs/testing.md`, `../reals-app/docs/technical-debt-frontend-prod.md` | Not ready for remote Play Integrity smoke. | Decide Play Internal Testing/Internal App Sharing or another verified channel. | High |
| Play Integrity | `dev`/`prod` use Play Integrity App Check provider; docs explicitly say local debug-provider smoke is not Play Integrity evidence. | `../reals-app/app/src/dev/java/com/reals/app/core/appcheck/AppCheckInstaller.kt`, `../reals-app/docs/security.md`, `../reals-app/docs/technical-debt-frontend-prod.md` | Requires Firebase/Play setup. | Link project/app and register signing SHA-256. | High |
| R8 mapping retention | Release builds enable minify/optimization/resource shrinking and CI uploads local mapping; docs require retaining exact mapping per release. | `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml`, `../reals-app/docs/infra.md`, `../reals-app/docs/testing.md` | Ready for local; dev/prod need artifact retention. | Define devRelease artifact retention. | Medium |
| Legal-document publication notes | Backend packages `legal-documents`; legal action SHA checks and retention gaps are documented; production publication evidence remains unresolved. | `pom.xml`, `legal-documents/`, `src/main/kotlin/com/reals/backend/config/legal/`, `docs/data-retention.md`, `docs/technical-debt-prod.md` | Dev can serve current configured documents. | Production legal publication process and evidence remain separate. | High |

## 3. Runtime dependency and network-flow inventory

Observed logical flow:

```text
Android dev build (com.reals.app.dev)
  -> HTTPS API base URL from BuildConfig.REALS_BASE_URL
  -> TLS termination / public AWS endpoint
  -> Reals backend container on HTTP 8080
  -> PostgreSQL
  -> S3-compatible object storage
  -> Firebase Admin/Auth/App Check/FCM over internet egress
```

Boundaries and flows:
- Public clients: Android calls only HTTPS for `dev` and `prod`; build validation rejects dev/prod cleartext, local-only hosts, and placeholder domains. Evidence: `../reals-app/app/build.gradle.kts`.
- Public inbound: only HTTPS should be public. The backend container itself listens on HTTP `8080`; TLS must terminate at the AWS runtime, load balancer, or reverse proxy. Evidence: `Dockerfile`, `src/main/resources/application.yml`.
- Health inbound: `/actuator/health/readiness`, `/actuator/health/liveness`, and `/actuator/health/**` are public; `/actuator/info` and `/actuator/metrics/**` require admin. Evidence: `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`.
- API inbound: `/api/ping` and `GET /api/legal/documents/current` are unauthenticated at Spring Security level, but App Check applies to API paths when enabled except skipped paths. Evidence: `src/main/kotlin/com/reals/backend/config/security/SecurityConfig.kt`, `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckFilter.kt`.
- Private database: PostgreSQL should not be public. Backend needs private network access to PostgreSQL and credentials through env/secrets. Evidence: `src/main/resources/application-dev.yml`.
- Object storage: backend uploads, deletes, and presigns reads; Android receives renderable URLs and then fetches the object URL directly. Evidence: `src/main/kotlin/com/reals/backend/service/S3StorageService.kt`, `docs/commons/api.md`.
- Presigned path: Android `GET /api/me/profile/photos` or visual-profile endpoints -> backend generates presigned S3 GET URL -> Android downloads directly from S3 endpoint until expiration. Evidence: `src/main/kotlin/com/reals/backend/service/S3StorageService.kt`, `docs/commons/api.md`.
- Scheduler/database locks: scheduled jobs run inside the backend process, use DB state, and are coordinated by JDBC ShedLock table. Evidence: `src/main/kotlin/com/reals/backend/scheduler/`, `src/main/kotlin/com/reals/backend/config/ShedLockConfig.kt`.
- Secrets consumed by backend: database credentials, Firebase Admin credentials, App Check config, S3 credentials, admin email allowlist, and optional moderation provider credentials. Evidence: `src/main/resources/application-dev.yml`, `src/main/resources/application.yml`.
- Secrets consumed by Android/CI: Firebase JSON files, release signing material, dev/prod base URLs, versioning values. Evidence: `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml`.
- Internet egress required: Firebase Admin/Auth/FCM, Firebase App Check JWKS, S3 public AWS endpoint unless VPC endpoints are introduced, image registry pulls unless mirrored locally, package downloads during build/CI. Evidence: `src/main/kotlin/com/reals/backend/config/firebase/FirebaseConfig.kt`, `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckConfig.kt`, `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`.

## 4. Configuration and secret matrix

Backend matrix:

| Variable | Source file | Dev/prod requirement | Secret | Safe default | Proposed AWS storage/injection | Startup consequence when absent | Consumer |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `docker-compose.yml`, `deploy/helm/values-dev.yaml` | Required operationally; `dev` for hosted dev | No | None | Runtime env var | Execution-profile guard fails if no execution profile is active | Backend |
| `PORT` | `src/main/resources/application.yml` | Optional | No | `8080` | Runtime env var if platform requires | Uses `8080` | Backend |
| `DATABASE_URL` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required for `dev`/`prod` | Sensitive | None | SSM SecureString or Secrets Manager; runtime env | Spring datasource binding/startup fails | Backend |
| `DATABASE_USERNAME` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required | Sensitive | None | SSM SecureString or Secrets Manager | DB connection/startup fails | Backend |
| `DATABASE_PASSWORD` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required | Secret | None | SSM SecureString or Secrets Manager | DB connection/startup fails | Backend |
| `BACKOFFICE_ADMIN_EMAILS` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required for admin access | Sensitive | Empty | SSM SecureString or encrypted config | No Firebase user receives `ROLE_ADMIN` | Backend |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | One Firebase credential source required unless ADC works | Secret path | Empty | Avoid for container unless mounted secret file is deliberate | Falls through to JSON/base64/ADC | Backend |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Alternative credential source | Secret | Empty | Secrets Manager if raw JSON is needed | Falls through to base64/ADC | Backend |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml`, `deploy/helm/values-dev.yaml` | Recommended current container secret source | Secret | Empty | Secrets Manager or SSM SecureString | Falls through to ADC; may fail if no ADC | Backend |
| `FIREBASE_PROJECT_NUMBER` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required before App Check `MONITOR`/`ENFORCED`; prod startup requires numeric | Sensitive identifier | Empty | SSM Parameter | App Check verification fails or prod startup fails | Backend |
| `FIREBASE_APP_CHECK_ALLOWED_APP_IDS` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required before App Check `MONITOR`/`ENFORCED`; prod startup requires non-empty | Sensitive identifier | Empty | SSM Parameter | App Check verification rejects tokens or prod startup fails | Backend |
| `FIREBASE_APP_CHECK_MODE` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | `DISABLED -> MONITOR -> ENFORCED` for dev; `ENFORCED` in prod | No | `DISABLED` in dev, `ENFORCED` in prod | Runtime env | Invalid value fails binding; prod rejects non-`ENFORCED` | Backend |
| `FIREBASE_APP_CHECK_JWKS_URI` | `src/main/resources/application.yml`, `src/main/resources/application-dev.yml` | Optional override | No | Firebase JWKS URL | Runtime env/parameter only if overriding | Invalid URI can fail prod startup | Backend |
| `STORAGE_S3_CREDENTIALS_MODE` / `S3_CREDENTIALS_MODE` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | `STATIC` for MinIO/R2; `DEFAULT_CHAIN` for AWS-hosted role credentials | No | `STATIC` | SSM Parameter/env | Invalid value or ambiguous credentials fail startup | Backend |
| `STORAGE_S3_ENDPOINT` / `S3_ENDPOINT` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Required for MinIO/R2; absent/blank for native Amazon S3 | Sensitive-ish | Empty in dev/prod | SSM Parameter when needed | Native S3 uses AWS regional endpoint; S3-compatible providers need explicit endpoint | Backend |
| `STORAGE_S3_PRESIGNED_URL_ENDPOINT` / `S3_PRESIGNED_URL_ENDPOINT` | `src/main/resources/application-dev.yml` | Optional when same as endpoint | Sensitive-ish | Empty -> main endpoint | SSM Parameter | Uses main endpoint | Backend |
| `STORAGE_S3_REGION` / `S3_REGION` | `src/main/resources/application-dev.yml` | Required logically | No | `auto` in dev/prod config | SSM Parameter/env | `auto` is accepted with explicit S3-compatible endpoint and rejected for native Amazon S3 without endpoint | Backend |
| `STORAGE_S3_BUCKET` / `S3_PROFILE_PHOTOS_BUCKET` | `src/main/resources/application-dev.yml` | Required | Sensitive identifier | None | SSM Parameter | S3 client exists but operations fail or config binding fails | Backend |
| `STORAGE_S3_ACCESS_KEY_ID` / `S3_ACCESS_KEY_ID` | `src/main/resources/application-dev.yml`, `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt` | Required only with `STATIC`; must be blank in `DEFAULT_CHAIN` | Secret | Empty in dev/prod | SSM/Secrets Manager only for static providers | Startup fails when incomplete or ambiguous | Backend |
| `STORAGE_S3_SECRET_ACCESS_KEY` / `S3_SECRET_ACCESS_KEY` | `src/main/resources/application-dev.yml`, `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt` | Required only with `STATIC`; must be blank in `DEFAULT_CHAIN` | Secret | Empty in dev/prod | SSM/Secrets Manager only for static providers | Startup fails when incomplete or ambiguous | Backend |
| `STORAGE_S3_SESSION_TOKEN` / `S3_SESSION_TOKEN` | `src/main/resources/application-dev.yml`, `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt` | Optional only with complete `STATIC` credentials; must be blank in `DEFAULT_CHAIN` | Secret | Empty | Secret store only if explicit temporary credentials are chosen | Startup fails without both key and secret | Backend |
| `STORAGE_S3_PATH_STYLE_ACCESS_ENABLED` / `S3_PATH_STYLE_ACCESS_ENABLED` | `src/main/resources/application-dev.yml` | Required decision | No | `true` | SSM Parameter/env | Defaults to path-style; AWS S3 usually works better with virtual-hosted style unless endpoint requires path-style | Backend |
| `STORAGE_S3_READ_URL_MODE` / `S3_READ_URL_MODE` | `src/main/resources/application-dev.yml` | Should be `PRESIGNED` | No | `PRESIGNED` | Runtime env | `PUBLIC` allowed in dev but rejected in prod; public mode requires public base URL | Backend |
| `STORAGE_S3_SIGNED_URL_DURATION_MINUTES` / `S3_SIGNED_URL_DURATION_MINUTES` | `src/main/resources/application-dev.yml` | Optional | No | `15` | Runtime env | Uses default | Backend |
| `STORAGE_S3_PUBLIC_BASE_URL` / `S3_PUBLIC_BASE_URL` | `src/main/resources/application-dev.yml` | Not needed for presigned dev | Sensitive-ish | Empty | Do not set for private S3 | Required only if public mode selected | Backend |
| `STORAGE_MEDIA_CLEANUP_*` | `src/main/resources/application-prod.yml`, `src/main/resources/application.yml` | Optional operational tuning | No | Batch `100`, lease `PT5M`, guard `PT30M`, retry defaults | Runtime env if tuning | Invalid values fail validation | Backend |
| `SCHEDULER_*` | `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml` | Optional operational tuning | No | Profile-specific defaults | Runtime env if tuning | Invalid values can fail job validation | Backend |
| `RATE_LIMIT_*` | `src/main/resources/application.yml`, `docker-compose.yml` | Optional tuning; keep enabled in hosted dev | No | Enabled and rule defaults | Runtime env if tuning | Invalid values fail validation; disabled weakens protection | Backend |
| `PROFILE_PHOTO_MODERATION_PROVIDER` | `src/main/resources/application.yml`, `docker-compose.yml` | Optional; `none` for initial dev unless provider configured | No | `none` | Runtime env/parameter | Provider-specific config may fail if required credentials absent | Backend |
| `SIGHTENGINE_API_USER` | `src/main/resources/application.yml`, `docker-compose.yml` | Required only when Sightengine provider is used | Secret | Empty | Secrets Manager/SSM | Sightengine config validation fails when provider active | Backend |
| `SIGHTENGINE_API_SECRET` | `src/main/resources/application.yml`, `docker-compose.yml` | Required only when Sightengine provider is used | Secret | Empty | Secrets Manager/SSM | Sightengine config validation fails when provider active | Backend |
| `PROFILE_AUTHENTICITY_VERIFICATION_PROVIDER` | `src/main/resources/application.yml`, `src/main/resources/application-dev.yml` | Optional; `none` for initial dev | No | `none` | Runtime env | Provider-specific behavior not active | Backend |
| `PROFILE_AUTHENTICITY_VERIFICATION_*` | `src/main/resources/application.yml`, `src/main/resources/application-dev.yml` | Optional policy tuning | No | Configured defaults | Runtime env if tuning | Invalid policy can fail validation | Backend |
| `IMAGE_REPOSITORY`, `IMAGE_TAG`, `IMAGE_REVISION` | `Dockerfile`, `.github/workflows/ci.yml`, `src/main/resources/application.yml` | Recommended for traceability | No | Empty/local/unknown | Docker build args/env labels | `/actuator/info` lacks useful image metadata | Backend/CI |

Android/CI matrix:

| Variable/property | Source file | Dev/prod requirement | Secret | Safe default | Proposed storage/injection | Startup/build consequence when absent | Consumer |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `REALS_DEV_BASE_URL` / `realsDevBaseUrl` | `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml` | Required for real `dev` builds | No | Placeholder rejected for dev task | GitHub secret/env or local Gradle property | `dev` validation/build fails if placeholder remains | Android/CI |
| `REALS_PROD_BASE_URL` / `realsProdBaseUrl` | `../reals-app/app/build.gradle.kts` | Required for prod builds | No | Placeholder rejected for prod task | GitHub secret/env or local Gradle property | Prod validation/build fails | Android/CI |
| `REALS_LOCAL_BASE_URL` / `realsLocalBaseUrl` | `../reals-app/app/build.gradle.kts` | Optional local | No | `http://127.0.0.1:8080/` | Local property/env | Local build uses default | Android |
| `GOOGLE_SERVICES_DEV_JSON_BASE64` | `../reals-app/.github/workflows/ci.yml` | Required for CI `devDebug`/`devRelease` | Sensitive config | None | GitHub Actions secret | Dev CI build skipped or validation fails | CI/Android |
| `GOOGLE_SERVICES_PROD_JSON_BASE64` | `../reals-app/.github/workflows/ci.yml` | Required for prod CI builds | Sensitive config | None | GitHub Actions secret | Prod CI build skipped or validation fails | CI/Android |
| `GOOGLE_SERVICES_LOCAL_JSON_BASE64` | `../reals-app/.github/workflows/ci.yml` | Optional local CI override | Sensitive config | Local file may exist | GitHub Actions secret | Local build may use committed/ignored local file if present | CI/Android |
| `REALS_RELEASE_KEYSTORE_BASE64` | `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml` | Required for signed release CI unless local keystore path used | Secret | None | GitHub Actions secret | Release build unsigned/skipped; partial config fails | CI/Android |
| `realsReleaseKeystorePath` | `../reals-app/app/build.gradle.kts` | Local alternative to base64 keystore | Secret path | None | Local Gradle property only | Missing file fails if configured | Android local |
| `REALS_RELEASE_STORE_PASSWORD` | `../reals-app/app/build.gradle.kts` | Required with release signing | Secret | None | GitHub Actions secret/local env | Partial signing config fails | CI/Android |
| `REALS_RELEASE_KEY_ALIAS` | `../reals-app/app/build.gradle.kts` | Required with release signing | Sensitive | None | GitHub Actions secret/local env | Partial signing config fails | CI/Android |
| `REALS_RELEASE_KEY_PASSWORD` | `../reals-app/app/build.gradle.kts` | Required with release signing | Secret | None | GitHub Actions secret/local env | Partial signing config fails | CI/Android |
| `REALS_VERSION_CODE` / `realsVersionCode` | `../reals-app/app/build.gradle.kts` | Required for controlled distribution | No | `GITHUB_RUN_NUMBER` or `1` | CI env/property | Uses default; may collide outside CI | CI/Android |
| `REALS_VERSION_NAME` / `realsVersionName` | `../reals-app/app/build.gradle.kts` | Required for traceability | No | `0.1.0-<sha>` or `0.1.0-local` | CI env/property | Uses default | CI/Android |

Proposed variables not currently implemented:
- `AWS_REGION`, `REALS_ENV`, `S3_BUCKET_NAME`, `DB_SECRET_ARN`, `FIREBASE_SECRET_ARN`, and IaC-specific names are not current application variables. They are operator/infrastructure variables only and must not be represented as application requirements unless future infrastructure code introduces them.
- `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN` are standard AWS SDK/default-chain inputs, not Reals `storage.s3.*` properties. In `DEFAULT_CHAIN`, do not copy them into application-specific S3 properties.

## 5. AWS architecture options

External AWS facts used here:
- AWS pricing is pay-as-you-go; you pay for selected services as used. Source: AWS Pricing, retrieved 2026-07-21, `https://aws.amazon.com/pricing/`.
- AWS accounts created after the July 15, 2025 Free Tier transition use the credit-based Free Plan: USD 100 credits at signup, the ability to earn up to USD 100 additional credits, and Free Plan expiration at six months after account creation or credit exhaustion, whichever happens first. Sources retrieved 2026-07-21: AWS Free Tier and AWS Free Tier FAQ.
- AWS states the Free Plan has limited service access while the Paid Plan has the full range of services; the operator must verify candidate services in the actual account before assuming they are usable. Source: AWS Free Tier, retrieved 2026-07-21.
- For new post-transition accounts, RDS access is part of the credit-based Free Plan model and Free Plan RDS eligibility is limited to permitted instance classes such as `db.t3.micro` and `db.t4g.micro` for supported engines including PostgreSQL. Source: Amazon RDS Free Tier, retrieved 2026-07-21, `https://aws.amazon.com/rds/free/`.
- The historical 750-hours-per-month/12-month RDS allowance applies only to legacy accounts created before the AWS Free Tier transition. Do not use that legacy allowance for a new 2026 account. Source: Amazon RDS Free Tier, retrieved 2026-07-21.
- When a Free Plan expires, AWS closes the account, workload resources become inaccessible, and AWS currently retains account content for 90 days before permanent deletion. Upgrade to Paid before expiration if the hosted dev environment and its data must continue. Sources retrieved 2026-07-21: AWS Billing Free Tier plan docs and AWS Free Tier FAQ.
- Current backend CI uses GitHub-hosted `ubuntu-latest`, which is an x64 Linux runner label. Docker's default build target is the builder platform unless `--platform` or Buildx multi-platform configuration is used. Sources retrieved 2026-07-21: GitHub hosted-runner docs and Docker Buildx/build docs.
- All cost statements below require operator verification in the selected account and Region. Region assumed for examples/methodology: `us-east-1` unless the operator chooses otherwise.

TLS alternatives for EC2-hosted dev:
- Option A — EC2 host TLS: run Caddy/Nginx or equivalent on EC2 and terminate HTTPS with ACME/Let's Encrypt, or with an explicitly exportable certificate. This is the provisional preferred first-dev path if the operator chooses EC2 and wants to avoid ALB fixed cost.
- Option B — ALB TLS: terminate HTTPS on an Application Load Balancer using a normal ACM non-exportable public certificate. This is operationally clean but adds ALB and public IPv4 cost.
- Option C — ACM exportable certificate on EC2: use an explicitly exportable ACM public certificate, not a normal non-exportable ACM certificate. AWS ACM pricing currently charges for exportable public certificate issuance and renewal; this path requires a certificate deployment and renewal procedure. Source: AWS Certificate Manager pricing and ACM exportable-certificate docs, retrieved 2026-07-21.

| Option | Fixed cost drivers | Free Tier/credit compatibility | Ops complexity | HTTPS/domain | Private networking | Secrets | DB connectivity | S3 integration | Logs/metrics | Deploy/rollback | One-operator fit | Production path | Lock-in | Failure modes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A. Single x86_64 EC2 container + RDS + S3 | EC2 instance/EBS, public IPv4, optional ALB, RDS, S3, CloudWatch logs, Route 53, Secrets/SSM. Public IPv4 is charged hourly per AWS VPC pricing. | For new 2026 accounts, verify Free Plan service access and credit coverage; do not assume legacy 12-month RDS allowance. RDS Free Plan access uses credits and permitted classes such as `db.t3.micro`/`db.t4g.micro`. Sources: AWS Free Tier, Amazon RDS Free Tier, VPC pricing. | Moderate; operator manages OS/container updates. | EC2 host TLS with Caddy/Nginx plus ACME/Let's Encrypt is lowest fixed cost; ALB+normal ACM certificate is cleaner but adds ALB/public IPv4 cost; exportable ACM-on-EC2 is a distinct paid certificate path. | Strong if RDS private subnet/security group; EC2 can be public only on 443/22 or use SSM Session Manager. | SSM/Secrets Manager -> env file/systemd/container; use `DEFAULT_CHAIN` with instance profile for S3. | Direct private RDS endpoint. | Code supports native S3 without endpoint override or static keys; IAM role and bucket policy still need real AWS validation. | CloudWatch agent/log driver or Docker logs; Actuator endpoints. | Pull immutable amd64 image tag, restart service; rollback by previous tag. | Best cost/control tradeoff if operator accepts server management. | Migrate container to ECS/App Runner later; keep RDS/S3; add multi-platform CI before ARM/Graviton. | Low-medium. | Instance loss if no automation; RDS mitigates DB loss; Free Plan closure makes resources inaccessible if not upgraded; colocated Docker process is single point of failure. |
| B. Single x86_64 EC2 container + colocated PostgreSQL + S3 | EC2/EBS, public IPv4, S3, backups/snapshots, optional Route 53. Avoids RDS fixed cost. | EC2/S3 credit compatibility requires verification under the actual Free/Paid plan. | Higher risk; operator owns DB backups, patching, disk growth, restore. | Same TLS alternatives as option A. | DB not publicly exposed if bound to local Docker network/localhost. | Same as option A. | Local PostgreSQL volume. | Same native S3 support as option A; IAM role and bucket policy still need AWS validation. | Host logs plus DB logs. | Image rollback easy; DB rollback harder. | Lowest initial cost but more fragile. | Must migrate to RDS before production; add multi-platform CI before ARM/Graviton. | Low. | EC2 disk/instance loss affects app and DB together; restore quality depends on snapshots/dumps; Free Plan closure can make the account inaccessible. |
| C. ECS/Fargate + RDS + S3 + ALB/ECR | Fargate vCPU/memory per second, ALB hourly+LCU, public IPv4 for tasks/load balancer where applicable, RDS, S3, ECR, CloudWatch. Fargate also charges for additional ephemeral storage beyond default. Sources: AWS Fargate pricing, ELB pricing, VPC pricing, ECR pricing. | Requires verification, especially Free Plan service access and credit coverage. | Moderate-high upfront; lower OS maintenance. | ALB+normal ACM certificate standard pattern. | Good with private subnets/security groups; NAT/VPC endpoints may add cost. | ECS can inject Secrets Manager secrets as env vars; rotated secrets need task restart. Source: ECS Secrets Manager docs. | Direct RDS endpoint. | Code supports `DEFAULT_CHAIN`; ECS task role behavior still needs real runtime validation. | Native CloudWatch logs and ECS metrics. | New task definition revision; rollback previous revision. | Good once set up, but more moving parts than current need. | Strong production path. | Medium to AWS ECS constructs. | ALB/NAT/RDS fixed costs continue even at low traffic; misconfigured health checks can churn tasks. |
| D. AWS App Runner + RDS + S3 | App Runner provisioned memory while deployed, active vCPU/memory during requests, automated deployment fee/build fee if enabled, RDS, S3, VPC connector if private RDS. Source: App Runner pricing. | Requires verification for account plan/credits/service access. | Lower runtime management. | Built-in HTTPS and custom domains. | Private RDS requires VPC connector; networking must be checked. | Supports runtime env/secrets, but exact secret source and rotation behavior must be verified before implementation. | Possible through VPC connector. | Code supports no endpoint override and default-chain credentials; App Runner role/credential behavior still needs platform review. | Built-in logs/metrics. | Deploy new image; pause/resume helps cost. | Good if service access and costs fit. | Acceptable for dev; migration to ECS possible later. | Medium-high to App Runner behavior. | Less direct host control; VPC connector/RDS networking issues; provisioned cost while warm. |
| E. Elastic Beanstalk Docker + RDS + S3 | EC2/EBS, load balancer depending environment type, RDS, S3, CloudWatch; Beanstalk itself has no separate service charge but underlying resources bill. | Requires verification. | Medium; managed deployment wrapper around EC2. | Supports managed load balancer/ACM patterns. | Good if configured into VPC/private RDS. | Env vars and platform secrets patterns need deliberate setup. | Direct RDS endpoint. | Same as EC2. | CloudWatch integration. | Application versions and environment rollback. | Reasonable, but less transparent than simple EC2. | Can be production stepping stone, though ECS is more modern for containers. | Medium. | Platform abstraction drift; hidden EC2/ALB costs. |

Do not select EKS/Kubernetes now:
- The only Kubernetes-like evidence is placeholder Helm values. They explicitly document app-specific values and future chart/deploy convention, not an active deploy requirement. Evidence: `deploy/helm/values-dev.yaml`, `deploy/helm/values-prod.yaml`.
- EKS would add cluster, node/Fargate, ingress, IAM, upgrades, and Kubernetes operations that are disproportionate for one backend instance.

## 6. PostgreSQL decision

| Option | Cost | Durability | Backups | Patching | Networking | Flyway | Credentials | Restore testing | Blast radius | Production migration |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Amazon RDS PostgreSQL | Higher fixed cost than colocated DB. For new post-July 15, 2025 accounts, RDS uses the credit-based Free Plan model and permitted Free Plan classes such as `db.t3.micro`/`db.t4g.micro`; the legacy 750-hours-per-month/12-month allowance is only for legacy accounts created before the transition. Sources retrieved 2026-07-21: Amazon RDS Free Tier and AWS Billing Free Tier docs. | Better managed durability than local disk; Multi-AZ not needed for first dev unless uptime requires it. | Automated backups available; retention must be configured. | AWS manages engine patching options, operator schedules. | Private subnet/security group; no public DB. | App startup runs Flyway automatically. | Store generated credentials in SSM/Secrets Manager. | Use snapshot restore into throwaway DB before trusting backups. | DB failure isolated from app instance. | Natural production path; adjust class, storage, Multi-AZ, backups later. |
| PostgreSQL colocated on EC2 | Lowest fixed AWS service count. | EBS-backed only; app and DB share host failure domain. | Must implement snapshots and/or `pg_dump` schedule manually. | Operator patches OS/Postgres/container. | Bind locally/private Docker network; never expose 5432 publicly. | Same Flyway behavior. | Local secret/env file or SSM pulled to host. | Operator must test full restore to new instance. | EC2 loss affects app and DB together. | Requires later migration to RDS before production. |
| Aurora/RDS Serverless or other managed PostgreSQL | Potentially useful later; not evidenced as necessary now. | Managed. | Managed. | Managed. | Private networking. | Compatible in principle, verify PostgreSQL compatibility/version. | Managed secret. | Snapshot/restore drill still needed. | Lower app/DB colocation risk. | Could be production option, but may add cost/complexity before needed. |

Recommendation for hosted `dev`:
- Prefer RDS PostgreSQL Single-AZ if the operator accepts the fixed cost and the selected AWS plan allows it. It makes Flyway, backups, restore drills, and future production migration cleaner.
- For a new 2026 AWS account, treat RDS as credit-consuming under the Free Plan, not as an entitlement to a separate 12-month monthly-hours allowance. Verify Region, instance class, storage, backup, and account-plan access before creating the DB.
- If the monthly budget is the dominant constraint, colocate PostgreSQL on the same EC2 instance for a short-lived dev environment only, with explicit EBS snapshots and a documented migration cutoff to RDS before production.

Production-required change:
- Production should use managed PostgreSQL with private networking, automated backups, restore drills, encryption, patch windows, and a tested migration/rollback process.

## 7. Object-storage decision

Observed S3-compatible behavior:
- Endpoint override is optional. When `storage.s3.endpoint` is nonblank, it is passed to `S3Client`; when blank, native Amazon S3 uses the AWS SDK regional endpoint. Evidence: `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`.
- Region is configured with `Region.of(properties.region)`. Evidence: `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`.
- Credential mode is explicit. `STATIC` uses configured access key/secret and optional session token; `DEFAULT_CHAIN` uses the AWS SDK default credentials provider chain for EC2 instance profiles, ECS task roles and standard AWS credential sources. Evidence: `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`.
- Session-based temporary credentials are represented by `storage.s3.session-token` only in `STATIC` mode with complete key and secret. Evidence: `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`, `src/main/resources/application-dev.yml`.
- Path-style addressing is controlled by `storage.s3.path-style-access-enabled`, defaulting to `true` in current dev/prod config. Evidence: `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml`.
- Bucket name is configured, not created by the app. Evidence: `src/main/resources/application-dev.yml`, `docs/storage-r2-configuration.md`.
- Objects should remain private and profile reads should use generated renderable URLs, not expose storage keys or bucket names. Evidence: `docs/storage-r2-configuration.md`, `docs/commons/api.md`.
- Uploads use `PutObject`; deletes use `DeleteObject`; reads are presigned `GetObject` URLs. Evidence: `src/main/kotlin/com/reals/backend/service/S3StorageService.kt`.
- CORS is not relevant for current backend-mediated upload path. Android downloads presigned object URLs directly; no browser direct-upload flow is observed. Evidence: `src/main/kotlin/com/reals/backend/service/S3StorageService.kt`, `docs/storage-r2-configuration.md`.
- Cleanup deletes objects from queued DB cleanup tasks. Evidence: `src/main/kotlin/com/reals/backend/service/MediaCleanupProcessor.kt`, `src/main/kotlin/com/reals/backend/scheduler/MediaCleanupJob.kt`.

Mapping to Amazon S3:
- Use one private dev bucket, for example `reals-dev-profile-photos-<operator-suffix>`; exact name is an operator decision and must not be committed.
- Enable Block Public Access and keep `STORAGE_S3_READ_URL_MODE=PRESIGNED`.
- For AWS S3, prefer virtual-hosted addressing (`STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=false`) unless testing proves path-style is required. This is a configuration decision because current local MinIO/R2 docs default to path-style.
- For native Amazon S3, leave `STORAGE_S3_ENDPOINT` and `STORAGE_S3_PRESIGNED_URL_ENDPOINT` absent/blank unless a selected design requires an explicit endpoint override. Do not fabricate a regional S3 endpoint manually.
- Required IAM policy shape: allow `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` for `arn:aws:s3:::<dev-bucket>/users/*/profile-photos/*`; allow minimal bucket-level actions only if SDK calls require them after testing. Do not grant public access or broad `s3:*`.
- Preferred target architecture: EC2 instance profile or ECS task role credentials through the AWS SDK default credential chain, with no committed or long-lived IAM user access key.
- Long-lived IAM user access keys remain a temporary dev exception only if the operator explicitly accepts them; they are not acceptable for production.
- Consider lifecycle rules for failed/abandoned objects only after validating media cleanup behavior; do not use lifecycle rules that delete current profile photos by age.

## 8. Firebase and Android dev-distribution plan

Required alignment:
- Firebase project: choose a dev Firebase project or a clearly isolated dev app inside an existing Firebase project. Backend Firebase Admin credentials must belong to the same project intended for dev authentication/App Check. Evidence: `src/main/kotlin/com/reals/backend/config/firebase/FirebaseConfig.kt`.
- Firebase Android App: create/register package `com.reals.app.dev`. Evidence: `../reals-app/app/build.gradle.kts`.
- Backend Admin project: configure `FIREBASE_SERVICE_ACCOUNT_BASE64`/equivalent for the same backend Firebase project.
- App Check project number: configure backend `FIREBASE_PROJECT_NUMBER` with the numeric Firebase/GCP project number. Evidence: `src/main/kotlin/com/reals/backend/config/security/appcheck/FirebaseAppCheckProperties.kt`.
- Accepted Firebase App ID: configure backend `FIREBASE_APP_CHECK_ALLOWED_APP_IDS` with the dev Firebase Android App ID. Evidence: `src/main/kotlin/com/reals/backend/config/security/appcheck/NimbusFirebaseAppCheckVerifier.kt`.
- Android signing SHA-256: register the SHA-256 fingerprint of the signing certificate used for the distributed `devRelease`.
- Play Integrity provider: Android `dev` installs `PlayIntegrityAppCheckProviderFactory`. Evidence: `../reals-app/app/src/dev/java/com/reals/app/core/appcheck/AppCheckInstaller.kt`.
- `google-services.json`: provide `app/src/dev/google-services.json` or CI `GOOGLE_SERVICES_DEV_JSON_BASE64`; local inspection found `app/src/dev/google-services.json` missing.
- Backend HTTPS URL: set `REALS_DEV_BASE_URL` or `realsDevBaseUrl` to the real hosted HTTPS URL; placeholder `api-dev.reals.example.com` is rejected by validation. Evidence: `../reals-app/app/build.gradle.kts`.
- `devRelease` signing: provide all release signing inputs; partial input fails and absent input causes CI to skip `devRelease`. Evidence: `../reals-app/app/build.gradle.kts`, `../reals-app/.github/workflows/ci.yml`.
- Distribution channel: use Google Play Internal Testing, Internal App Sharing, or another operator-verified channel if Play Integrity must reflect Play-distributed behavior. The repository already warns that local debug-provider testing is not hosted-dev or Play Integrity evidence; this correction pass revalidated only AWS, GitHub, and Docker external sources.

Do not treat sideloading as sufficient evidence for the final Play Integrity posture:
- Current repo docs already warn that the July 21, 2026 local optimized smoke used local App Check debug provider and does not prove hosted dev, Play Integrity, Google Play distribution, remote HTTPS, or production devices. Evidence: `../reals-app/docs/local-development.md`, `../reals-app/docs/technical-debt-frontend-prod.md`.

Staged App Check rollout:

```text
DISABLED -> MONITOR -> ENFORCED
```

Promotion criteria:
- `DISABLED` to `MONITOR`: backend hosted HTTPS is reachable; Android `dev` build sends `X-Firebase-AppCheck`; backend has project number and allowed app ID configured; no auth/provisioning regressions.
- `MONITOR` to `ENFORCED`: representative two-device dev traffic shows valid App Check tokens for protected API paths; missing/invalid token counts are understood; FCM and photo flows pass.

Rollback criteria:
- `ENFORCED` to `MONITOR`: valid users receive `MISSING_APP_CHECK_TOKEN` or `INVALID_APP_CHECK_TOKEN`, Play Integrity tokens become unavailable, or Firebase/Play configuration changes break dev clients.
- `MONITOR` to `DISABLED`: App Check verification adds unacceptable dev instability before a compatible Android build is available.

## 9. Recommended hosted-dev architecture

Preferred architecture:
- Services: one x86_64 EC2 instance for the backend container, RDS PostgreSQL Single-AZ if budget allows, one private S3 bucket, CloudWatch Logs, SSM Parameter Store/Secrets Manager, optional ECR, optional Route 53 hosted zone/subdomain, and explicit host TLS through Caddy/Nginx with ACME/Let's Encrypt unless the operator chooses ALB TLS.
- Region: unresolved; use `us-east-1` only for initial pricing methodology until operator selects a Region.
- Network boundaries: public HTTPS only; backend HTTP `8080` private to host/load balancer; PostgreSQL private; S3 private bucket with presigned reads; outbound internet to Firebase, App Check JWKS, FCM, S3, and registry.
- Replica count: one backend instance.
- Database: RDS PostgreSQL for clean backups/migration path; colocated PostgreSQL only as a temporary cost compromise.
- Storage: Amazon S3 private dev bucket with presigned reads, `STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN`, no endpoint override, and least-privilege IAM role after AWS resources are created.
- Image registry: keep GHCR initially if pull auth is simple; use ECR if private AWS-native pulls and lifecycle policies are preferred.
- TLS/DNS: use real `api-dev.<domain>` with HTTPS before Android `dev` validation. Provisional preference is EC2 host TLS with ACME/Let's Encrypt for low fixed cost. If using ALB, terminate HTTPS on ALB with a normal ACM non-exportable public certificate and accept ALB/public IPv4 cost. If using ACM directly on EC2, use an explicitly exportable ACM certificate and operate certificate deployment/renewal; do not install a normal non-exportable ACM certificate on EC2.
- Secrets: use SSM Parameter Store standard parameters for non-secret config where possible; use SSM SecureString or Secrets Manager for secrets. AWS Systems Manager standard parameters have no additional charge; Secrets Manager is priced per secret and API calls. Sources retrieved 2026-07-21: `https://aws.amazon.com/systems-manager/pricing/`, `https://aws.amazon.com/secrets-manager/pricing/`.
- Logging/metrics: CloudWatch Logs with retention; minimum alarms on process/readiness, HTTP 5xx, DB connectivity, disk, CPU/memory, scheduler failures, and push/storage errors.
- Backups: RDS automated backups/snapshots or EC2 snapshot/`pg_dump` if colocated; S3 versioning/lifecycle decision; restore drill before real testing data matters; export critical backups outside a Free Plan account before closure risk.
- Deployment trigger: manual first deploy from immutable image tag; CI deployment automation is a later phase.
- Rollback: redeploy previous immutable image tag; keep DB migrations forward-only and avoid backward-incompatible migrations without a recovery plan.
- Estimated cost methodology: calculate Region-specific monthly fixed costs for 730 hours of EC2, EBS, public IPv4, optional ALB, RDS instance/storage/backup, S3 storage/requests/egress, CloudWatch log ingestion/storage, Route 53 hosted zone/query, ECR storage, and secrets. Use AWS Pricing Calculator before creating resources.

What can be implemented immediately:
- AWS-0 account guardrails, account-plan verification, budget alerts, Region/domain decisions, and documentation/runbook preparation.
- Current CI can build and publish an amd64 container image from a known SHA.
- Runtime secrets can be designed without S3 static AWS keys when the selected runtime provides AWS SDK default-chain credentials.

What `BACK-AWS-0` implements in code:
- AWS SDK default credential-chain support for Amazon S3 so EC2 instance profiles or ECS task roles can be used.
- Optional endpoint override behavior so native Amazon S3 does not require a custom endpoint while MinIO/R2 still can.
- Test coverage for S3 client and presigner configuration modes.

What depends on operator choices:
- AWS Region, Free versus Paid plan timing, RDS versus colocated PostgreSQL, TLS path, domain/subdomain ownership, GHCR versus ECR, and acceptable monthly budget.

Initial dev compromise:
- One instance and no autoscaling.
- In-memory rate limiting is acceptable only because replica count is one.
- App Check may start `DISABLED` then move to `MONITOR`.
- Colocated PostgreSQL is acceptable only if budget blocks RDS and restore is tested.
- A temporary static S3 access key is acceptable only for a short-lived hosted dev exception after explicit operator approval; it is not the preferred target and must not be committed.

Production-required changes:
- Managed PostgreSQL with backups/restore, role-based AWS S3 credentials, no long-lived S3 IAM user key, tighter IAM, IaC, automated deployments, image scanning gates, secret rotation, stricter observability, production Firebase/App Check enforcement, tested rollback/runbooks, and production legal/data-retention decisions.

Deliberately deferred:
- EKS/Kubernetes, Terraform/CDK, multi-region, autoscaling, WAF, CDN, object thumbnails, full analytics/crash platform, and deployment workflows.

## 10. Phased implementation plan

### AWS-0 — Account and cost guardrails

Purpose:
- Make AWS safe to use before resource creation.

Prerequisites:
- Operator owns AWS account, billing access, and selected account plan.

Files likely to change:
- Documentation only at first; no application files required.

AWS resources affected:
- Account root MFA, IAM users/roles, budgets, billing alerts, service quotas, Free/Paid plan status.

Secrets involved:
- None in repository.

Validation:
- Root MFA enabled; admin access is not root; budget alert email tested; Region chosen; Free/Paid plan constraints documented; Free Plan expiration date and credit-exhaustion behavior recorded.

Rollback:
- Disable unused IAM access; do not create workload resources before guardrails are confirmed.

Completion criteria:
- Account plan, credits, Region, monthly budget, naming/tagging convention, uptime expectation, Paid Plan upgrade deadline, and 90-day account-content recovery window are written down.

Dependencies on later decisions:
- Runtime, database, and DNS choices depend on budget and plan access.

### BACK-AWS-0 — AWS-native S3 credentials and endpoint compatibility

Status after this code change:
- Implemented in backend code, tests and documentation. No AWS resources were created, and no EC2/ECS/App Runner runtime has validated real IAM role credentials yet.

Purpose:
- Remove the current S3 credential blocker before preferred AWS runtime deployment.

Prerequisites:
- None for the code change; AWS-0 and runtime target selection remain prerequisites for real deployment validation.

Files likely to change:
- Implemented in `src/main/kotlin/com/reals/backend/config/s3/S3Config.kt`, profile YAML, focused S3 configuration tests, `docs/configuration.md`, `docs/storage-r2-configuration.md`, and this plan.

AWS resources affected:
- None during implementation; later IAM role/policy design depends on the result.

Secrets involved:
- Existing static S3 credentials for MinIO/R2 remain supported; AWS-hosted dev should use instance/task role credentials through `DEFAULT_CHAIN`.

Validation:
- `S3Client` and `S3Presigner` configuration tests cover AWS SDK default credential chain, explicit static credentials, optional session credentials if selected, optional endpoint override for native Amazon S3, and explicit endpoint override for MinIO/R2.

Rollback:
- Keep explicit static-credential mode working for local MinIO and non-AWS S3-compatible providers.

Completion criteria:
- Environment-variable contract documents AWS-hosted role-based mode, static MinIO/R2 mode, optional temporary-credential mode, native S3 endpoint behavior, and presigner behavior.

Dependencies on later decisions:
- If the operator chooses ECS/Fargate instead of EC2, this phase still targets the AWS SDK default credential chain so task roles work without committed access keys.

### AWS-1 — Foundational dev resources

Purpose:
- Create isolated network, DNS/TLS decisions, database, bucket, secrets, and IAM roles.

Prerequisites:
- AWS-0 complete; selected architecture approved.

Files likely to change:
- Future docs/runbook only; no Terraform/CDK in this task.

AWS resources affected:
- VPC/subnets/security groups or default VPC decision, RDS or EC2 volume, S3 bucket, SSM/Secrets Manager, IAM role/policy, optional Route 53/ACM/ALB.

Secrets involved:
- DB credentials, Firebase Admin credential, no S3 static AWS key when `DEFAULT_CHAIN` is used, admin allowlist, optional Sightengine credentials.

Validation:
- DB private; S3 public access blocked; secrets not visible in image/repo; selected TLS path validates; no normal non-exportable ACM certificate is assumed installable on EC2.

Rollback:
- Delete unused resources only after confirming no test data is needed; revoke temporary credentials.

Completion criteria:
- Backend can theoretically receive all required config through AWS storage/injection without committing values.

Dependencies on later decisions:
- DNS/TLS path affects Android `REALS_DEV_BASE_URL`.

### AWS-2 — Backend image and runtime

Purpose:
- Run one backend container from a known image tag.

Prerequisites:
- AWS-1 complete; immutable amd64 image exists from backend CI; runtime IAM role or equivalent default-chain credential source selected for Amazon S3.

Files likely to change:
- Deployment runbook; no application code in this planning task.

AWS resources affected:
- EC2/ECS/App Runner/Beanstalk runtime, ECR if chosen, CloudWatch logs.

Secrets involved:
- Runtime env/secrets from AWS-1.

Validation:
- Image digest/tag recorded; process starts; Flyway succeeds; `/actuator/health/readiness` is `UP`; `/api/ping` returns `ok`; logs show no secret values.

Rollback:
- Stop service or redeploy previous immutable image tag; if Flyway changed schema incompatibly, restore DB snapshot or run forward fix.

Completion criteria:
- Backend is reachable through the intended HTTPS URL and reports healthy.

Dependencies on later decisions:
- App Check remains `DISABLED` until Android-compatible dev build exists.

### AWS-3 — CI/CD and rollback

Purpose:
- Make builds traceable and deployment repeatable.

Prerequisites:
- Existing CI image publication on `development`.
- Pre-existing AWS dev EC2 host with SSM, Docker, Nginx, runtime env file, and GHCR pull access.

Files changed:
- Manual `Deploy AWS Dev` GitHub Actions workflow.
- EC2-side Docker deployment and rollback script sent through SSM.
- Stubbed Bash tests for script validation.
- AWS/GitHub setup runbook.

AWS resources affected:
- None directly in this repository. The workflow assumes pre-existing AWS resources.

Secrets involved:
- GitHub Actions uses OIDC, not long-lived AWS access keys. Runtime application secrets remain on the host in `/etc/reals/backend.env` or external AWS secret stores.

Validation:
- Tests green; image scanned; immutable tag deployed; SSM polling completes within the bounded timeout; internal health checks pass; public smoke workflow passes against base URL; previous image rollback tested.

Rollback:
- Automatic rollback recreates the previously captured local image ID when the new container fails to start or fails internal readiness or ping. Explicit rollback deploys a provided full SHA that is an ancestor of current `development`. Public HTTPS smoke failure after successful internal checks fails the workflow but does not automatically roll back.

Completion criteria:
- Exact source SHA maps to image tag, runtime revision, smoke result, and rollback target.

Dependencies on later decisions:
- Production deployment design, approvals, and release tagging.

### AWS-4 — Firebase/App Check dev integration

Purpose:
- Align Firebase backend auth/App Check with Android dev app.

Prerequisites:
- Backend HTTPS URL exists; Firebase dev app/project selected.

Files likely to change:
- Android local/CI secret config only; backend env config only.

AWS resources affected:
- Runtime secrets/env updates.

Secrets involved:
- Firebase service account, Firebase Android JSON, App Check allowed app IDs, project number.

Validation:
- Firebase Auth token verifies; App Check `MONITOR` records valid tokens; missing/invalid outcomes understood; no unauthenticated protected access.

Rollback:
- Set `FIREBASE_APP_CHECK_MODE=DISABLED` or `MONITOR`; redeploy/restart backend.

Completion criteria:
- Dev Android build sends App Check and backend accepts it in `MONITOR`.

Dependencies on later decisions:
- Play Console/Internal Testing/App Sharing path and signing SHA-256.

### AWS-5 — Android `devRelease`

Purpose:
- Produce and distribute a signed, optimized dev client pointed only at hosted dev.

Prerequisites:
- AWS-4 ready; real `REALS_DEV_BASE_URL`; `app/src/dev/google-services.json`; release signing inputs.

Files likely to change:
- CI secret configuration; possibly docs. Do not commit Firebase JSON or keystore.

AWS resources affected:
- None directly.

Secrets involved:
- `GOOGLE_SERVICES_DEV_JSON_BASE64`, `REALS_RELEASE_KEYSTORE_BASE64`, `REALS_RELEASE_STORE_PASSWORD`, `REALS_RELEASE_KEY_ALIAS`, `REALS_RELEASE_KEY_PASSWORD`.

Validation:
- `assembleDevRelease` succeeds; app ID is `com.reals.app.dev`; HTTPS URL is dev only; R8 mapping retained; Play Integrity provider present; artifact distributed through chosen channel.

Rollback:
- Withdraw bad artifact from test channel; distribute previous signed artifact; keep backend App Check in `MONITOR` or `DISABLED`.

Completion criteria:
- Two physical devices can install the same dev client without USB-dependent local networking.

Dependencies on later decisions:
- Signing-key custody and Play testing channel.

### AWS-6 — Remote two-device E2E

Purpose:
- Prove hosted dev supports the core Reals flow outside local smoke assumptions.

Prerequisites:
- AWS-5 complete; two test accounts/devices; backend logs visible.

Files likely to change:
- Test evidence/runbook only.

AWS resources affected:
- Existing runtime, DB, S3, Firebase/FCM.

Secrets involved:
- None newly; testers use normal Firebase Auth.

Validation:
- Authentication; profile activation; photo upload and presigned read; matchmaking; first chat; visual review; scheduling; second chat; FCM with app backgrounded and USB disconnected; failure/recovery; App Check `MONITOR` evidence.

Rollback:
- Revert backend image; lower App Check mode; restore DB snapshot if destructive test data corrupts dev; remove broken Android artifact.

Completion criteria:
- E2E checklist passes and App Check enforcement decision is recorded.

Dependencies on later decisions:
- Whether to enforce App Check in dev and whether dev can be stopped when unused.

## 11. Backup, restore and rollback plan

- Database backup mechanism: RDS automated backups/snapshots if using RDS; scheduled EBS snapshots plus logical `pg_dump` if colocated PostgreSQL is used.
- Restore drill: before real dev users/testers, restore the latest backup into a separate database/instance, point a one-off backend at it, and confirm Flyway validation/readiness.
- Object-storage recovery: S3 durability does not replace deletion recovery. Decide S3 versioning and lifecycle; media cleanup deletes objects intentionally, so versioning may be useful for dev mistakes but must not create indefinite retention surprises.
- Free Plan account-closure risk: if the AWS Free Plan reaches six months or exhausts credits, AWS closes the account and workload resources become inaccessible. AWS currently retains account content for 90 days before permanent deletion; upgrade to Paid before expiration if hosted dev and its data must continue. Backups must not rely exclusively on continued access to the Free Plan account.
- Application-image rollback: deploy immutable `sha-<shortsha>` tag from GHCR/ECR; keep at least the previous known-good tag documented.
- Flyway implications: migrations are forward-only at startup. If a migration is incompatible with the previous image, rollback requires either a forward fix image or DB restore to the pre-migration snapshot.
- Incompatible migration handling: create DB snapshot immediately before deployment; do not run destructive migrations in dev without tested restore.
- Firebase rollback: keep old backend Firebase config values available in the secret store version history; restart service after secret change.
- App Check rollback: move `ENFORCED -> MONITOR -> DISABLED` by runtime config and restart/redeploy.
- Android compatibility window: keep the previous dev artifact available while backend accepts both old and new client behavior; do not enforce App Check until all active dev clients send valid tokens.

## 12. Security checklist

- Root account MFA enabled; no routine root use.
- Least-privilege IAM for runtime, registry pulls, logs, S3, and secrets.
- No public PostgreSQL; DB security group allows only backend.
- Private S3 bucket; Block Public Access enabled.
- AWS-hosted S3 access uses `DEFAULT_CHAIN` with an instance profile/task role or equivalent runtime credential source; long-lived IAM user keys are not acceptable for production.
- TLS only for Android dev/prod API traffic.
- No Firebase service-account file in image or Git.
- Secret rotation process for DB, Firebase, S3 access keys if used, and signing credentials.
- No sensitive request-body logging; Android network logs redact `Authorization` and `X-Firebase-AppCheck`. Evidence: `../reals-app/app/src/main/java/com/reals/app/data/api/RealsApiClient.kt`.
- Restricted admin endpoints; `BACKOFFICE_ADMIN_EMAILS` configured deliberately.
- Local-dev tooling admin-only in hosted `dev`.
- Dependency/image scanning retained from CI.
- Database encryption enabled.
- S3 encryption enabled.
- CloudWatch log retention configured.
- Backup protection and restore tested.
- Budget alerts configured before resource creation.

## 13. Observability minimum

Minimum hosted-dev signals:
- Process health: runtime service state and container restarts.
- Readiness: `/actuator/health/readiness`.
- HTTP errors: runtime/load balancer 4xx/5xx and application logs.
- API latency: ALB/App Runner/ECS metrics or reverse-proxy logs; app-level latency metrics require additional work if not available.
- JVM/container memory: EC2 agent/container metrics or runtime metrics.
- DB connectivity and pool: Actuator health/readiness and Hikari metrics if exported/visible.
- Scheduler failures: log queries/alarms for `JobRunSummary`, scheduler exceptions, and failed media cleanup tasks.
- Matchmaking/job summaries: logs from scheduler jobs.
- Failed push deliveries: warnings from `FirebasePushNotificationSender`.
- App Check outcomes: counters/logs from `FirebaseAppCheckFilter` if metric registry/export is available.
- Storage failures: `ObjectStorageException` logs and failed media cleanup tasks.

Do not add a large observability platform for first dev. CloudWatch Logs plus basic AWS/runtime metrics are enough initially. Custom dashboards/alerts should be added only for the signals above.

## 14. Validation checklist

Pre-deployment:
- Known backend source SHA selected.
- Backend tests green and Docker image built/scanned; current CI image is treated as `linux/amd64` unless future multi-platform build evidence exists.
- Image tag/digest recorded.
- AWS account plan, credits, Region, and budget verified.
- Free Plan expiration/credit-exhaustion closure risk and Paid upgrade deadline documented.
- Database backup/snapshot plan exists before first Flyway run.
- `STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN` configured for preferred AWS S3 runtime deployment, or a short-lived static-key exception explicitly approved and documented.
- Required backend env/secrets present and no real values committed.
- S3 bucket private and public access blocked.
- Firebase Admin credential belongs to dev project.
- Android `dev` Firebase app exists as `com.reals.app.dev`.
- `REALS_DEV_BASE_URL` is a real HTTPS URL and not a placeholder.
- Android `devRelease` signing and mapping-retention plan exists.

Post-deployment:
- Flyway migration succeeds.
- `/actuator/health/readiness` returns `UP`.
- `/api/ping` returns `ok`.
- Public health endpoint behavior matches expectation.
- Protected endpoints reject missing auth.
- Admin endpoints require admin Firebase user.
- App Check `MONITOR` shows expected valid/missing/invalid outcomes.
- Image upload stores an object under `users/<userId>/profile-photos/...`.
- Presigned read URL loads from Android.
- FCM token registration and notification delivery work.
- Scheduler jobs run and log summaries.
- No dev secrets appear in logs, image, git diff, or Android artifacts.
- Android dev build points only to hosted dev.
- Rollback to previous image is tested.

## 15. Open decisions

- AWS Region.
- Free versus Paid account plan.
- Paid Plan upgrade deadline if hosted dev data must survive Free Plan expiration or credit exhaustion.
- Domain/subdomain and DNS owner.
- RDS versus colocated PostgreSQL for first dev.
- Runtime platform: EC2, ECS/Fargate, App Runner, or Beanstalk.
- TLS path: EC2 ACME/Let's Encrypt, ALB plus normal ACM certificate, or EC2 plus exportable ACM certificate.
- x86_64 EC2 initially versus future multi-platform CI before ARM/Graviton.
- Real AWS runtime validation criteria for `DEFAULT_CHAIN` on the selected platform.
- Infrastructure-as-code timing.
- CI deployment credentials or GitHub OIDC.
- Signing-key custody and Play App Signing strategy.
- Firebase dev-project layout.
- Google Play Internal Testing/Internal App Sharing versus another verified distribution channel.
- Expected dev uptime.
- Acceptable monthly budget.
- Backup retention.
- Whether dev may be stopped when unused.

## Risk notes

- Free Plan closure: for post-transition accounts, the Free Plan ends at six months or credit exhaustion, whichever comes first. AWS closes the account at that point, workload resources become inaccessible, and account content is currently retained for 90 days before permanent deletion. Upgrade to Paid before expiration if hosted dev and its data must continue.
- Backup continuity: do not rely exclusively on an expiring Free Plan account for recoverability. Keep restore procedures and any critical exports independent enough to survive loss of account access.
- S3 credential model: backend code now supports the AWS SDK default credential chain, but real EC2 instance profile/ECS task role behavior is unproven until AWS-1/AWS-2 runtime validation. Long-lived static S3 keys are a temporary dev compromise only.
- TLS ambiguity: EC2 host TLS requires ACME/Let's Encrypt or an explicitly exportable certificate; a normal non-exportable ACM certificate belongs on integrated services such as ALB, not directly on a standard EC2 host.
- Image architecture: current CI output should be treated as `linux/amd64`. Do not select ARM/Graviton until CI publishes a verified arm64 image or multi-platform manifest.
- RDS model: do not assume the legacy 750-hours-per-month/12-month RDS allowance applies to a new 2026 account; verify credit use, eligible classes, and selected Region before DB creation.

## 16. Explicit non-goals

This planning task does not:
- Create AWS resources.
- Choose production architecture permanently.
- Implement Kubernetes.
- Implement Terraform/CDK.
- Deploy the backend.
- Configure Google Play.
- Create signing keys.
- Change notification behavior.
- Change API contracts.
- Change databases.
- Alter product flows.

## External source inventory

AWS official sources:
- AWS Free Tier, retrieved 2026-07-21: `https://aws.amazon.com/free/`.
- AWS Free Tier FAQ, retrieved 2026-07-21: `https://aws.amazon.com/free/free-tier-faqs/`.
- AWS Free Tier terms, retrieved 2026-07-21: `https://aws.amazon.com/free/terms/`.
- AWS Billing Free Tier plan docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html`.
- AWS Billing Free Tier docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier.html`.
- AWS legacy Free Tier docs for pre-July 15, 2025 accounts, retrieved 2026-07-21: `https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/billing-free-tier.html`.
- AWS Pricing overview, retrieved 2026-07-21: `https://aws.amazon.com/pricing/`.
- Amazon EC2 On-Demand pricing, retrieved 2026-07-21: `https://aws.amazon.com/ec2/pricing/on-demand/`.
- Amazon RDS Free Tier, retrieved 2026-07-21: `https://aws.amazon.com/rds/free/`.
- Amazon RDS for PostgreSQL pricing, retrieved 2026-07-21: `https://aws.amazon.com/rds/postgresql/pricing/`.
- AWS Fargate pricing, retrieved 2026-07-21: `https://aws.amazon.com/fargate/pricing/`.
- AWS App Runner pricing, retrieved 2026-07-21: `https://aws.amazon.com/apprunner/pricing/`.
- Amazon S3 pricing, retrieved 2026-07-21: `https://aws.amazon.com/s3/pricing/`.
- Amazon ECR pricing, retrieved 2026-07-21: `https://aws.amazon.com/ecr/pricing/`.
- Elastic Load Balancing pricing, retrieved 2026-07-21: `https://aws.amazon.com/elasticloadbalancing/pricing/`.
- Amazon VPC pricing, retrieved 2026-07-21: `https://aws.amazon.com/vpc/pricing/`.
- Amazon CloudWatch pricing, retrieved 2026-07-21: `https://aws.amazon.com/cloudwatch/pricing/`.
- AWS Secrets Manager pricing, retrieved 2026-07-21: `https://aws.amazon.com/secrets-manager/pricing/`.
- AWS Systems Manager pricing, retrieved 2026-07-21: `https://aws.amazon.com/systems-manager/pricing/`.
- Amazon Route 53 pricing, retrieved 2026-07-21: `https://aws.amazon.com/route53/pricing/`.
- AWS Certificate Manager pricing, retrieved 2026-07-21: `https://aws.amazon.com/certificate-manager/pricing/`.
- AWS ACM exportable certificate docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/acm/latest/userguide/acm-exportable-certificates.html`.
- AWS ACM services docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/acm/latest/userguide/acm-services.html`.
- AWS ACM ACME issuance docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/acm/latest/userguide/acm-acme-issuance.html`.
- AWS SDK for Java 2.x default credential chain docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html`.
- AWS SDK for Java 2.x temporary credential docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-temporary.html`.
- AWS SDK for Java 2.x EC2 IAM role docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ec2-iam-roles.html`.
- ECS Secrets Manager env-var docs, retrieved 2026-07-21: `https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html`.

GitHub official sources:
- GitHub hosted runner docs, retrieved 2026-07-21: `https://docs.github.com/actions/using-github-hosted-runners/about-github-hosted-runners`.
- GitHub runner selection docs, retrieved 2026-07-21: `https://docs.github.com/actions/using-jobs/choosing-the-runner-for-a-job`.

Docker official sources:
- Docker multi-platform build docs, retrieved 2026-07-21: `https://docs.docker.com/build/building/multi-platform/`.
- Docker Buildx build reference, retrieved 2026-07-21: `https://docs.docker.com/reference/cli/docker/buildx/build/`.
- Docker GitHub Actions multi-platform docs, retrieved 2026-07-21: `https://docs.docker.com/build/ci/github-actions/multi-platform/`.
