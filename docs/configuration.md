# Configuration

Production and shared development profiles should receive environment-specific values from environment variables or a secret manager, not from committed files.

## Profiles

- `local-firebase`: default local profile, Firebase auth, PostgreSQL datasource for local Docker runs and schedulers disabled.
- `local-nodb`: local H2 file database, dev auto-auth and schedulers disabled.
- `local-postgres`: local PostgreSQL, dev auto-auth, Flyway enabled and schedulers disabled.
- `dev`: external database, Firebase auth, Flyway enabled by default, schedulers enabled by default and local-only `/api/local-dev/**` controllers disabled.
- `prod`: external database, Flyway enabled, schedulers enabled.
- `test`: H2 in-memory test profile under `src/test/resources`.

## Placeholder Reference

`deploy/helm/values-dev.yaml` and `deploy/helm/values-prod.yaml` are temporary Helm-style values references. Their exact role is still undecided because the final Helm chart may live in a separate infrastructure repository.

If these values files are kept, they should contain deploy-time concerns such as image tag, replicas, probes, resource limits, service/ingress settings and references to config or secret keys. Application behavior settings such as scheduler cadence, chat durations and product limits are defined directly in `src/main/resources/application-dev.yml` and `src/main/resources/application-prod.yml`.

Use the deployment platform to inject non-sensitive configuration values, and
use its secret manager for sensitive values.

Non-sensitive runtime configuration:

| Variable | Required | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | Use `dev` or `prod` outside local development. |
| `DATABASE_URL` | yes | JDBC URL, for example `jdbc:postgresql://host:5432/reals`. |
| `DATABASE_USERNAME` | yes | PostgreSQL user. |
| `ACCOUNT_DELETION_RECOVERY_WINDOW_DAYS` | no | Defaults to `30`; controls how long a deleted account can be reactivated before finalization. |
| `S3_ENDPOINT` | when media upload is enabled | S3-compatible API endpoint used by the backend for object operations. For AWS S3 this can be omitted. |
| `S3_PRESIGNED_URL_ENDPOINT` | when returned URLs need a different public host | Endpoint used only when generating presigned read URLs, for example Android Emulator local MinIO. |
| `S3_REGION` | when media upload is enabled | Defaults per profile; use the real bucket region in shared environments. |
| `S3_PROFILE_PHOTOS_BUCKET` | when media upload is enabled | Bucket names are not treated as secrets, but keep one value per environment. |
| `S3_PUBLIC_BASE_URL` | only with `S3_READ_URL_MODE=PUBLIC` | Public base URL used when objects are intentionally public. |
| `S3_PATH_STYLE_ACCESS_ENABLED` | no | Use `true` for MinIO and many S3-compatible providers; AWS S3 usually supports virtual-hosted style. |
| `S3_READ_URL_MODE` | no | `PRESIGNED` by default for private buckets; `PUBLIC` only for intentionally public media. |
| `S3_SIGNED_URL_DURATION_MINUTES` | no | Presigned read URL validity duration. Defaults to a short-lived local/dev value. |
| `PROFILE_PHOTO_MAX_SIZE_BYTES` | no | Maximum accepted multipart profile-photo file size. |
| `IDENTITY_VERIFICATION_PROVIDER` | no | Defaults to `none`. |

Sensitive runtime secrets:

| Variable | Required | Notes |
| --- | --- | --- |
| `DATABASE_PASSWORD` | yes | PostgreSQL password. |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | one Firebase credential source | Preferred for lightweight container runtimes. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | one Firebase credential source | Raw service-account JSON when the platform supports multiline secrets safely. |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | one Firebase credential source | Path to a mounted service-account JSON file. |
| `S3_ACCESS_KEY_ID` | when S3 credentials are not provided by the runtime | MinIO/R2/S3-compatible access key. Prefer runtime IAM roles for AWS. |
| `S3_SECRET_ACCESS_KEY` | when S3 credentials are not provided by the runtime | MinIO/R2/S3-compatible secret key. |
| `IDENTITY_VERIFICATION_API_KEY` | when a non-`none` provider exists | Keep empty while identity verification is disabled. |

S3-compatible storage has two endpoint concerns. `S3_ENDPOINT` is where the
backend writes and deletes objects. `S3_PRESIGNED_URL_ENDPOINT` is the host
embedded in returned presigned URLs and must be reachable by the client that
renders the image.

For AWS deployments, prefer IAM roles for S3 access instead of long-lived access keys. In that setup the application usually only needs the bucket setting; AWS credentials come from the runtime role.

## Helm Chart Location

The Helm chart does not have to live in this repository. A common setup is:

- Application repository: source code, Dockerfile/build pipeline metadata and per-environment `values-*.yaml`.
- Infrastructure repository: shared Helm chart, Kubernetes templates, cluster-specific configuration and deployment automation.

In that setup, CI builds and publishes the image from this repository, then the deployment pipeline applies the chart from the infrastructure repository using this app's values file.

## Environment-Specific Files

`application-dev.yml` and `application-prod.yml` intentionally repeat operational settings such as scheduler cadence, chat durations, scheduling limits and profile photo limits. This makes deploy-time behavior explicit without relying on implicit inheritance from `application.yml`.

These files should use placeholders only for environment-specific or secret-backed values, such as database credentials, S3 bucket/region, Firebase service account location and future identity-verification credentials.

Do not commit real credentials in any `application-*.yml` file.

## Identity Verification

`identity-verification.provider` currently supports only `none`. The provider
abstraction exists so a real identity-verification integration can be added
later without changing profile creation flow. Identity verification is invoked
explicitly through `POST /api/me/profile/identity-verification`; profile
creation does not call the provider. With `provider=none`, profiles keep
`identityVerified=false`.

`IDENTITY_VERIFICATION_API_KEY` is reserved for a future provider and should stay
empty until that provider is implemented.

## Matchmaking Tuning

`matchmaking.candidate-pair-limit` controls how many SQL-filtered candidate pairs are scored per matchmaking selection. Local and test profiles keep this low for deterministic, cheap checks. Dev/prod use higher starting values and should be adjusted using queue size, job duration and match creation metrics.

`matchmaking.min-compatibility-score` discards scored pairs below the configured threshold. `matchmaking.early-accept-compatibility-score` stops scoring as soon as a strong enough pair is found. Scores are expected to be normalized from `0.0` to `1.0`.

## How Injection Works

The application only sees environment variables such as `DATABASE_PASSWORD` or `S3_PROFILE_PHOTOS_BUCKET`. The CI/CD or runtime platform is responsible for resolving those values from its own config store or secret manager before starting the process.

Example flow:

1. `application-prod.yml` references `${DATABASE_PASSWORD}`.
2. `deploy/helm/values-prod.yaml` says `DATABASE_PASSWORD` should come from secret key `database_password`.
3. The deploy pipeline reads that secret from its configured secret manager.
4. The pipeline starts the app with `DATABASE_PASSWORD=<resolved secret>`.
5. Spring resolves `${DATABASE_PASSWORD}` at startup.

## Health And Smoke Checks

Runtime readiness checks should call:

```http
GET /actuator/health/readiness
```

Runtime liveness checks should call:

```http
GET /actuator/health/liveness
```

Post-deploy smoke checks should verify readiness, ping and the image metadata
served by `/actuator/info`:

```http
GET /actuator/info
GET /api/ping
```

`/actuator/info` includes Docker image metadata when the application is started
from a CI-built image:

```json
{
  "image": {
    "repository": "ghcr.io/gtestino92/reals-backend",
    "tag": "development",
    "revision": "<full-git-sha>"
  }
}
```

Use the manual `Smoke check` GitHub Actions workflow after a runtime update. For
the dev environment, set `expected_image_repository` to
`ghcr.io/gtestino92/reals-backend`, `expected_image_tag` to `development` and
`expected_image_revision` to the deployed commit SHA or its prefix.
