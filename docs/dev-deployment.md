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

Flyway runs automatically in the `dev` profile and applies migrations at app
startup. `spring.jpa.hibernate.ddl-auto` is `validate`, so schema drift should
fail startup instead of silently mutating the database.

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
through `GET /actuator/info`:

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

After updating a deployed environment, run the manual GitHub Actions workflow
`Smoke check` with the environment base URL. Optionally provide the expected
image tag and Git revision to verify that the runtime is serving the intended
container image. It checks:

```http
GET /actuator/health/readiness
GET /actuator/info
GET /api/ping
```
