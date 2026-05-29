# Local Development

## Spring Profile

The default active profile is:

```text
local-nodb
```

Despite the name, this profile uses an H2 file database:

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

The H2 console is enabled through `spring.h2.console.*` in `application-local-nodb.yml`.

Connection:

```text
JDBC URL: jdbc:h2:file:./data/realsdb
Username: sa
Password: empty
```

The actual datasource URL in `application-local-nodb.yml` includes PostgreSQL compatibility mode:

```text
jdbc:h2:file:./data/realsdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
```

## Local Auth

With `local-nodb`, no authorization header is needed. `DevAutoAuthFilter` injects:

```text
userId: 00000000-0000-0000-0000-000000000001
role: ROLE_USER
```

This filter is scoped to the local profile.

## Local Firebase Auth

Use `local-firebase` only when you want to test real Firebase ID tokens locally.
It uses the same H2 file database style as `local-nodb`, but disables dev
auto-auth and enables Firebase token verification.

The local Firebase service-account JSON is expected at:

```text
./secrets/reals-backend-firebase-credentials-dev.json
```

The `secrets/` directory is ignored by Git and must never be committed.

## Local Jobs

`local-nodb` disables automatic scheduled execution:

```yaml
scheduler.enabled: false
```

Use the dev endpoints for deterministic manual testing:

```http
POST /api/dev/jobs/{job}/run
```

For example:

```http
POST /api/dev/jobs/scheduled-second-chat-start/run
```

## Local Profile Photo Rules

Local overrides:

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

Local `local-nodb` disables Flyway and uses Hibernate `ddl-auto: update`.

Production-like schema changes should be represented with migrations under:

```text
src/main/resources/db/migration
```

Current migration:

```text
V1__init.sql
```
