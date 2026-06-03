# Local Development

## Spring Profile

The default active profile is:

```text
local-firebase
```

This profile uses real Firebase ID tokens and an H2 file database:

```text
./data/realsdb
```

## Run Locally

The project is set up to run from IntelliJ IDEA. Maven CLI may not be installed on the target machine, so do not assume `mvn` is available unless confirmed.

The app starts on:

```text
http://localhost:8080
```

Sanity check:

```http
GET http://localhost:8080/api/ping
```

Expected response:

```json
{"status":"ok"}
```

## H2 Console

URL:

```text
http://localhost:8080/h2-console
```

The H2 console is enabled through `spring.h2.console.*` in the local H2 profiles.

Connection:

```text
JDBC URL: jdbc:h2:file:./data/realsdb
Username: sa
Password: empty
```

The local H2 datasource URL includes PostgreSQL compatibility mode:

```text
jdbc:h2:file:./data/realsdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
```

## Local Firebase Auth

The default `local-firebase` profile verifies real Firebase ID tokens locally.
It uses the same H2 file database style as `local-nodb`, but disables dev
auto-auth and enables Firebase token verification.

The local Firebase service-account JSON is expected at:

```text
./secrets/reals-backend-firebase-credentials-dev.json
```

The `secrets/` directory is ignored by Git and must never be committed.

## Local Auto-Auth

With `local-nodb`, no authorization header is needed. `DevAutoAuthFilter` injects:

```text
userId: 00000000-0000-0000-0000-000000000001
role: ROLE_USER
```

This filter is scoped to the local profile.

## Local PostgreSQL

Use `local-postgres` when you want to test the production-style database path
locally. This profile uses PostgreSQL, enables Flyway and validates the JPA
model against the migrated schema.

Start PostgreSQL:

```powershell
docker compose up -d postgres
```

Run the app with:

```text
SPRING_PROFILES_ACTIVE=local-postgres
```

Default connection:

```text
JDBC URL: jdbc:postgresql://localhost:5432/reals
Username: reals
Password: reals
```

This profile uses the same dev auto-auth behavior as `local-nodb`, so existing
Bruno local flows can run without Firebase tokens. It disables automatic
schedulers; use the dev job endpoints for deterministic manual testing.

Useful database commands:

```powershell
docker compose logs -f postgres
docker compose down
docker compose down -v
```

Use `docker compose down -v` only when you want to delete the local PostgreSQL
data volume and force Flyway to recreate the schema from scratch.

## Local Docker App

Build and run the backend plus PostgreSQL with Docker Compose:

```powershell
docker compose up -d --build backend
```

The `backend` service runs with:

```text
SPRING_PROFILES_ACTIVE=local-postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reals
```

Inside Docker, the database host is `postgres`, not `localhost`, because
`localhost` would point to the backend container itself.

Check the backend:

```powershell
curl http://localhost:8080/api/ping
```

View logs:

```powershell
docker compose logs -f backend
```

Stop only the backend:

```powershell
docker compose stop backend
```

Stop backend and database without deleting the database volume:

```powershell
docker compose down
```

### Local Docker App With Firebase

Use this mode when you want Docker to run the backend plus PostgreSQL, but
authenticate requests with real Firebase ID tokens instead of local auto-auth.

The Firebase service-account JSON must exist locally at:

```text
./secrets/reals-backend-firebase-credentials-dev.json
```

The `secrets/` directory is ignored by Git and must never be committed.

Build and run the Firebase-backed Docker app:

```powershell
docker compose -f docker-compose.yml -f docker-compose.firebase.yml up -d --build backend
```

This override runs the backend with:

```text
SPRING_PROFILES_ACTIVE=dev
DATABASE_URL=jdbc:postgresql://postgres:5432/reals
FIREBASE_SERVICE_ACCOUNT_PATH=/run/secrets/firebase-service-account.json
```

The service-account file is mounted read-only inside the backend container.
Automatic schedulers are disabled through `SCHEDULER_ENABLED=false` so local
manual testing stays deterministic.

Check the backend:

```powershell
curl http://localhost:8080/api/ping
curl http://localhost:8080/actuator/health/readiness
```

Stop backend and database without deleting the database volume:

```powershell
docker compose -f docker-compose.yml -f docker-compose.firebase.yml down
```

## Local Jobs

Local profiles disable automatic scheduled execution:

```yaml
scheduler.enabled: false
```

Use the dev endpoints for deterministic manual testing:

```http
POST /api/local-dev/jobs/{job}/run
```

For example:

```http
POST /api/local-dev/jobs/scheduled-second-chat-start/run
```

## Local Profile Photo Rules

Local H2 profile overrides:

- max photos: `9`
- required photos: `4`
- min person photos: `1`
- min full-body photos: `1`

Default application rules are stricter:

- max photos: `9`
- required photos: `9`
- min person photos: `3`
- min full-body photos: `1`

## Flyway And Schema

Local H2 profiles disable Flyway and use Hibernate `ddl-auto: update`.

Local `local-postgres` enables Flyway and uses Hibernate `ddl-auto: validate`.

Production-like schema changes should be represented with migrations under:

```text
src/main/resources/db/migration
```

Current migration:

```text
V1__init.sql
```
