# Dev Deployment

The dev environment should run the backend image against a managed PostgreSQL
database. Do not package PostgreSQL into the backend image.

## Target Shape

```text
GitHub Actions
  -> build and test
  -> publish ghcr.io/gtestino92/reals-backend:development

Dev runtime
  -> backend container from GHCR
  -> managed PostgreSQL database
  -> Firebase real auth
```

The local `docker-compose.yml` keeps PostgreSQL in Docker only for local
development convenience.

No cloud deployment automation exists yet. At this stage GitHub Actions builds,
tests, scans and publishes the container image; the runtime platform still needs
to be chosen and configured separately.

## Current Readiness

The repository is ready for a manual first dev deployment on Render or Railway,
but not for a one-click deploy.

Already in place:

- Dockerfile builds a Java 21/Kotlin backend image.
- GitHub Actions publishes `ghcr.io/gtestino92/reals-backend:development` on
  pushes to `development`.
- The `dev` Spring profile uses managed PostgreSQL, Firebase auth, Flyway,
  Hibernate schema validation and automatic schedulers.
- Public readiness, liveness, ping and image metadata endpoints exist for smoke
  checks.
- Cloudflare R2/S3-compatible storage configuration is documented separately in
  `docs/storage-r2-configuration.md`.

Still required before the first deploy:

- Choose Render or Railway as the first runtime.
- Create the runtime service and managed PostgreSQL database manually.
- Configure all runtime variables and secrets listed below.
- Configure the runtime port so incoming HTTP traffic reaches Spring Boot.
- Configure R2, hosted MinIO or another S3-compatible store for profile photos.
- Run the `Smoke check` GitHub Actions workflow against the deployed URL.

The app currently listens on Spring Boot's default port `8080`. Render and
Railway both use a platform `PORT` concept for public web services. For the
first deployment, set the service variable:

```text
PORT=8080
```

This keeps the platform's target port aligned with the current Docker image. If
we later want the app to bind to a provider-assigned dynamic port instead, add a
Spring configuration such as `server.port=${PORT:8080}` and update this doc.

## PostgreSQL For Dev

Create a managed PostgreSQL instance in the same platform or region where the
backend runs. Good first options are Render PostgreSQL, Railway PostgreSQL,
Neon, Supabase, Fly Postgres, AWS RDS or Azure Database for PostgreSQL.

For the first dev environment, prefer the option that matches the app runtime:

- Render backend -> Render PostgreSQL.
- Railway backend -> Railway PostgreSQL.
- Fly backend -> Fly Postgres.
- AWS App Runner backend -> RDS PostgreSQL.
- Azure Container Apps backend -> Azure Database for PostgreSQL.

After creating the database, configure the backend runtime with:

```text
SPRING_PROFILES_ACTIVE=dev
DATABASE_URL=jdbc:postgresql://<host>:<port>/<database>
DATABASE_USERNAME=<database-user>
DATABASE_PASSWORD=<database-password>
```

`DATABASE_URL` and `DATABASE_USERNAME` are runtime configuration. `DATABASE_PASSWORD`
is a runtime secret.

Use the provider's internal/private database hostname when available. Use the
external hostname only if the runtime cannot access private networking yet.
Many platforms expose PostgreSQL URLs as `postgres://...` or
`postgresql://...`; convert that to the JDBC shape above before assigning
`DATABASE_URL`.

Flyway runs automatically in the `dev` profile and applies migrations at app
startup. `spring.jpa.hibernate.ddl-auto` is `validate`, so schema drift should
fail startup instead of silently mutating the database.

## Profile Photo Storage For Dev

Do not point a shared Render/Railway dev environment at the MinIO container that
runs on a developer laptop. Use a storage service that lives with the deployed
environment and has persistent storage.

Good first options:

- Cloudflare R2.
- Hosted MinIO on the same platform, with a persistent disk/volume.
- Another S3-compatible object store.

Minimum R2-style variables:

```text
STORAGE_S3_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_PRESIGNED_URL_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_REGION=auto
STORAGE_S3_BUCKET=<dev-bucket-name>
STORAGE_S3_ACCESS_KEY_ID=<access-key-id>
STORAGE_S3_SECRET_ACCESS_KEY=<secret-access-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

See `docs/storage-r2-configuration.md` for the full setup and verification
checklist.

Hosted MinIO is also acceptable for development when the platform supports it.
Render has a MinIO deployment guide/template backed by a persistent disk, and
Railway has MinIO templates/volumes. In that setup, keep the backend and MinIO
in the same project/region when possible, and configure:

```text
STORAGE_S3_ENDPOINT=<backend-reachable MinIO S3 API URL>
STORAGE_S3_PRESIGNED_URL_ENDPOINT=<client-reachable MinIO S3 API URL>
STORAGE_S3_REGION=us-east-1
STORAGE_S3_BUCKET=<dev-bucket-name>
STORAGE_S3_ACCESS_KEY_ID=<minio-access-key>
STORAGE_S3_SECRET_ACCESS_KEY=<minio-secret-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

The two endpoint values may differ. `STORAGE_S3_ENDPOINT` is used by the
backend for uploads/deletes. `STORAGE_S3_PRESIGNED_URL_ENDPOINT` is embedded in
returned photo URLs and must be reachable by Android/Bruno clients.

## Firebase For Dev

The `dev` profile uses Firebase token verification, not local auto-auth.

Provide credentials using one of these options:

```text
FIREBASE_SERVICE_ACCOUNT_PATH=/mounted/secret/firebase-service-account.json
FIREBASE_SERVICE_ACCOUNT_JSON=<raw service account json>
FIREBASE_SERVICE_ACCOUNT_BASE64=<base64 encoded service account json>
```

If none are set, the app falls back to Google Application Default Credentials.

For most lightweight runtimes, `FIREBASE_SERVICE_ACCOUNT_BASE64` is easiest:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\secrets\reals-backend-firebase-credentials-dev.json"))
```

Store the resulting value as a runtime secret, not in Git.

Pending before the first dev deploy:

- Generate the Firebase service-account JSON for the dev Firebase project.
- Encode it as Base64.
- Store it in the deployment platform as `FIREBASE_SERVICE_ACCOUNT_BASE64`.
- Rotate/delete any local copy that is no longer needed.

## Render First Deploy Notes

Use either a Docker build from this repository or a prebuilt GHCR image.

Recommended first path:

1. Create a Render Web Service.
2. Use Docker as the runtime, either from the repository `Dockerfile` or from
   `ghcr.io/gtestino92/reals-backend:development`.
3. Create Render PostgreSQL in the same region.
4. Set `PORT=8080`.
5. Set `SPRING_PROFILES_ACTIVE=dev`.
6. Set `DATABASE_URL` as a JDBC URL using the internal database host when
   possible.
7. Set `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
8. Set Firebase and storage secrets. For storage, choose R2 or hosted MinIO
   with a persistent disk.
9. Set the health check path to `/actuator/health/readiness`.

If the GHCR package is private, configure Render registry credentials or deploy
from the Git repository Dockerfile instead.

Render Web Services expose a public `onrender.com` URL by default. Use that URL
as the backend base URL for Bruno and Android dev testing.

## Railway First Deploy Notes

Use one Railway service for the backend and a Railway PostgreSQL database in the
same project/environment.

Recommended first path:

1. Create a Railway project.
2. Add PostgreSQL.
3. Add a backend service from the GitHub repository Dockerfile or from the GHCR
   image.
4. Generate a public domain for the backend service.
5. Set `PORT=8080`.
6. Set `SPRING_PROFILES_ACTIVE=dev`.
7. Set `DATABASE_URL` as a JDBC URL. If using Railway-provided database
   variables, do not pass a raw `postgres://...` URL directly to Spring.
8. Set `DATABASE_USERNAME` and `DATABASE_PASSWORD`, or derive them into the
   JDBC URL and matching username/password variables.
9. Set Firebase and storage secrets. For storage, choose R2 or hosted MinIO
   with a persistent volume.

Railway private networking is useful for backend-to-database traffic, but
Android and Bruno must use the public Railway domain for API calls.

## GHCR Image

GitHub Actions publishes images on pushes to `development` and `master`:

```text
ghcr.io/gtestino92/reals-backend:development
ghcr.io/gtestino92/reals-backend:sha-<short-sha>
ghcr.io/gtestino92/reals-backend:master
ghcr.io/gtestino92/reals-backend:latest
```

The deployment platform should pull `:development` for the dev environment.
Use a `sha-*` tag when you need an immutable rollback target.

The Docker image embeds runtime metadata in environment variables and exposes it
through `GET /actuator/info` for authenticated administrators:

```json
{
  "image": {
    "repository": "ghcr.io/gtestino92/reals-backend",
    "tag": "development",
    "revision": "<full-git-sha>"
  }
}
```

Production should not track a moving branch tag. Once a production environment
exists, publish immutable tags from Git release tags, for example `v1.0.0`, and
deploy that exact tag.

## Required Runtime Checks

The platform health check should use:

```http
GET /actuator/health/readiness
```

The endpoint is public by design and should return:

```json
{"status":"UP"}
```

`/api/local-dev/**` endpoints are local-only and are not available in the cloud `dev`
profile. Scheduled jobs run automatically in `dev`.

After updating a deployed environment, run smoke checks with the environment
base URL. Readiness and ping are public. Docker image metadata is exposed by
`/actuator/info` but requires an authenticated administrator token in hosted
environments. Checks:

```http
GET /actuator/health/readiness
GET /actuator/info   # with administrator bearer token
GET /api/ping
```
