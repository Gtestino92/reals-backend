# Dev Deployment

The current shared dev backend target is AWS. The application runs as a Docker
container on EC2, behind Nginx HTTPS, using private PostgreSQL and S3-compatible
storage. GitHub Actions builds and publishes images; deployment is a separate
manual workflow.

## Current Shape

```text
GitHub Actions
  -> test, scan, publish GHCR image

Manual Deploy AWS Dev workflow
  -> GitHub OIDC
  -> AWS deployment role
  -> SSM Run Command
  -> EC2 Docker container
  -> Nginx HTTPS
  -> private RDS PostgreSQL
  -> private Amazon S3
```

The local `docker-compose.yml` keeps PostgreSQL and MinIO in Docker only for
local development convenience.

## Deploying Dev

Normal flow:

1. Merge the change into `development`.
2. Wait for the existing `CI` workflow to pass on `development`.
3. Open GitHub Actions and run `Deploy AWS Dev` from the `development` branch.
4. Leave the optional `revision` input blank for the selected `development`
   HEAD.
5. Review the workflow summary for the resolved full revision, immutable image
   tag, SSM result, readiness, ping, and rollback status.

The workflow does not build or publish an image. It deploys the image already
published by CI:

```text
ghcr.io/gtestino92/reals-backend:sha-<short-sha>
```

The tag is calculated automatically from the resolved full Git SHA.

For the detailed AWS and GitHub setup, rollback behavior, IAM policy templates,
branch strategy, and production design notes, see `docs/aws-dev-deployment.md`.

`development` is the repository default branch and the source for AWS dev
deployments, so GitHub registers `Deploy AWS Dev` directly from
`development:.github/workflows/deploy-aws-dev.yml`. No duplicate workflow file
on `master` is required for AWS dev deployment.

## Explicit Rollback

To deploy an older known-good image, run `Deploy AWS Dev` from `development`
and provide a full 40-character SHA in `revision`.

The workflow rejects revisions that are not ancestors of current `development`
and still deploys only the immutable `sha-<short-sha>` image tag. Operators do
not manually create tags or copy a short SHA.

Database migrations are forward-only at startup. If a failed deploy includes an
incompatible Flyway migration, application rollback may also require a database
restore or forward-fix image.

## Automatic Rollback

The EC2-side deployment script pulls and verifies the new immutable image before
stopping the existing container. It validates the image OCI revision label
`org.opencontainers.image.revision` against the requested full SHA.

If the new container fails to start, or internal checks against
`127.0.0.1:8080` fail for readiness or `/api/ping`, the script restores the
previously captured local image ID and returns non-zero. The workflow reports
whether rollback occurred.

After SSM succeeds, GitHub Actions checks the public Nginx HTTPS URL. Public
smoke failure does not automatically roll back because the failure may be in
Nginx, TLS, DNS, security-group routing, or another host-level path. Inspect the
public path before deciding whether to run an explicit image rollback.

## Runtime Configuration

The `dev` Spring profile uses managed PostgreSQL, Firebase auth, Flyway,
Hibernate schema validation, S3-compatible storage, and automatic schedulers.

Minimum backend runtime environment in `/etc/reals/backend.env`:

```text
SPRING_PROFILES_ACTIVE=dev
DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>
DATABASE_USERNAME=<database-user>
DATABASE_PASSWORD=<database-password>
```

`DATABASE_PASSWORD`, Firebase credentials, static S3 credentials if used, and
provider API secrets are runtime secrets. Do not commit them.

The deployment script defaults to:

```text
IMAGE_REPOSITORY=ghcr.io/gtestino92/reals-backend
CONTAINER_NAME=reals-backend
ENV_FILE=/etc/reals/backend.env
PORT_BINDING=127.0.0.1:8080:8080
READINESS_URL=http://127.0.0.1:8080/actuator/health/readiness
PING_URL=http://127.0.0.1:8080/api/ping
```

Nginx should proxy public HTTPS traffic to `127.0.0.1:8080`. Do not expose the
container port publicly.

## PostgreSQL For Dev

Prefer private RDS PostgreSQL for hosted dev when budget allows. The backend
expects:

```text
DATABASE_URL=jdbc:postgresql://<private-host>:<port>/<database>
DATABASE_USERNAME=<database-user>
DATABASE_PASSWORD=<database-password>
```

Flyway runs automatically in the `dev` profile and applies migrations at app
startup. `spring.jpa.hibernate.ddl-auto` is `validate`, so schema drift should
fail startup instead of silently mutating the database.

## Profile Photo Storage For Dev

For AWS-hosted dev with native Amazon S3, prefer role-based AWS SDK credentials:

```text
STORAGE_S3_CREDENTIALS_MODE=DEFAULT_CHAIN
STORAGE_S3_REGION=<aws-region>
STORAGE_S3_BUCKET=<dev-bucket-name>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=false
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

Do not point shared dev at a developer-machine MinIO instance. See
`docs/storage-r2-configuration.md` for S3-compatible provider behavior and
non-AWS options.

## Firebase For Dev

The `dev` profile uses Firebase token verification, not local auto-auth.

Provide credentials using one controlled runtime secret source:

```text
FIREBASE_SERVICE_ACCOUNT_PATH=/mounted/secret/firebase-service-account.json
FIREBASE_SERVICE_ACCOUNT_JSON=<raw service account json>
FIREBASE_SERVICE_ACCOUNT_BASE64=<base64 encoded service account json>
```

If none are set, the app falls back to Google Application Default Credentials.
Do not commit Firebase credentials.

## Firebase App Check Rollout For Dev

The `dev` profile defaults App Check to `DISABLED` so the backend can deploy
before the Android client starts sending App Check tokens.

Rollout sequence:

1. Enable App Check for the Android dev app in Firebase.
2. Configure `FIREBASE_PROJECT_NUMBER` and `FIREBASE_APP_CHECK_ALLOWED_APP_IDS`.
3. Set `FIREBASE_APP_CHECK_MODE=MONITOR` and deploy.
4. Deploy Android with `X-Firebase-AppCheck: <token>` on API requests.
5. Inspect logs and `reals.app_check.requests` outcomes.
6. Set `FIREBASE_APP_CHECK_MODE=ENFORCED` only after dev traffic is clean.

## GHCR Image

GitHub Actions publishes images on pushes to `development` and `master`:

```text
ghcr.io/gtestino92/reals-backend:development
ghcr.io/gtestino92/reals-backend:sha-<short-sha>
ghcr.io/gtestino92/reals-backend:master
ghcr.io/gtestino92/reals-backend:latest
```

AWS dev deploys the immutable `sha-<short-sha>` tag, not the moving
`development` tag. Production should not track a moving branch tag either.

## Required Runtime Checks

Public deployment validation checks only:

```http
GET /actuator/health/readiness
GET /api/ping
```

Expected responses:

```json
{"status":"UP"}
```

```json
{"status":"ok"}
```

`/actuator/info` and `/actuator/metrics/**` are administrator-protected in
hosted environments. Inspect `/actuator/info` manually with a fresh
administrator bearer token only when needed.
