# AWS Production Deployment Runbook

This repository contains the manually triggered AWS production backend
deployment workflow currently used for Reals production. The workflow deploys
immutable GHCR images to the existing production EC2 host through SSM, validates
public readiness and ping, and preserves application-image rollback semantics.

The workflow does not create AWS infrastructure. The current production
infrastructure is operator-managed outside this repository in `us-east-2` and
includes a dedicated backend EC2 instance, private PostgreSQL RDS, private
profile-photo S3 bucket, DNS, HTTPS/TLS, Nginx in front of the backend,
production Firebase configuration and enforced Firebase App Check.

Production application defaults enable the already implemented matchmaking
ranking system: `matchmaking.ranking.mode=PROBABILISTIC_WEIGHTED`,
`user-reliability.enabled=true` and
`matchmaking.ranking.affinity.mode=ACTIVE`. Affinity changes candidate weights
within the configured bounds and is never a hard eligibility filter. No ML
model is involved. Runtime rollback controls are
`MATCHMAKING_RANKING_AFFINITY_MODE=OFF`,
`MATCHMAKING_RANKING_MODE=LEGACY_EARLY_ACCEPT` and
`USER_RELIABILITY_ENABLED=false`; because affinity `ACTIVE` is incompatible
with legacy ranking, disable affinity before or together with switching ranking
back to legacy.

## Current Shape

```text
GitHub Actions
  -> manual Deploy AWS Prod workflow
  -> GitHub Environment prod approval/configuration
  -> GitHub OIDC
  -> production AWS deployment role
  -> SSM Run Command
  -> production EC2 Docker container
  -> public readiness and ping smoke checks
```

The workflow does not build or push Docker images, does not clone the repository
on EC2, does not create or modify AWS resources, and does not use `latest`,
`master` or `development` as the deployed Docker tag. It sends the committed
`ops/aws/deploy-backend.sh` script to the target EC2 instance through Systems
Manager Run Command.

## Manual Production Deployment

Production deployment is manual only:

```text
Actions
-> Deploy AWS Prod
-> Run workflow
-> Branch: master
-> revision: blank or full 40-character SHA
-> rollback_mode: automatic or disabled
```

The workflow rejects any selected ref other than:

```text
refs/heads/master
```

When `revision` is blank, the workflow resolves the current `origin/master`
commit after checkout. When `revision` is provided, it must be a full
40-character hexadecimal commit SHA. In both cases, the resolved commit must
exist in Git and must be an ancestor of `origin/master`.

The deployed image is always:

```text
ghcr.io/gtestino92/reals-backend:sha-<first 7 characters of resolved revision>
```

The EC2-side script verifies the image OCI label
`org.opencontainers.image.revision` against the full resolved Git SHA before
replacing the container.

## GitHub Environment `prod`

The workflow uses a GitHub Environment named `prod`.

Required environment configuration that must remain present:

| Name | Secret | Purpose |
| --- | --- | --- |
| `AWS_REGION` | No | AWS Region containing the production EC2 instance. |
| `AWS_DEPLOY_ROLE_ARN` | Usually no | Role assumed by GitHub OIDC. The workflow also accepts this as an environment secret. |
| `AWS_EC2_INSTANCE_NAME` | No | Exact EC2 `Name` tag value used for running-instance discovery. |
| `BACKEND_BASE_URL` | No | Public HTTPS base URL served by the production edge for smoke checks. |

The workflow fails during configuration validation if any required value is
missing. Do not hardcode ARNs, instance IDs, domains, passwords or secrets in
the workflow.

Required reviewers should remain configured in the GitHub Environment before
operator-approved production use.

## AWS and EC2 Requirements

Production uses a separate AWS OIDC role, separate EC2 target and separate
runtime configuration from dev. The workflow resolves exactly one running EC2
instance by:

```text
Name=<AWS_EC2_INSTANCE_NAME>
instance-state-name=running
```

Zero matches or multiple matches fail the deployment before SSM execution.

The EC2 host must have Docker, SSM managed-instance connectivity, permission to
pull `ghcr.io/gtestino92/reals-backend`, and a readable
`/etc/reals/backend.env`. Application secrets belong in the host/runtime secret
source, not in GitHub Actions.

## Rollback Mode

`ops/aws/deploy-backend.sh` supports:

```text
ROLLBACK_MODE=automatic
ROLLBACK_MODE=disabled
```

The default is `automatic` to preserve the dev deployment behavior.

### `automatic`

If the new container fails to start, readiness fails, or ping fails, the script:

1. Records the primary failure stage and controlled error code.
2. Captures safe failed-container metadata when the new container exists.
3. Saves a bounded local log snapshot on the EC2 host when possible.
4. Removes the failed new container when present.
5. Starts a replacement container from the exact previously captured local image
   ID or image reference.
6. Runs the same internal readiness and ping checks.
7. Emits `DEPLOY_RESULT=ROLLED_BACK` when rollback succeeds.
8. Emits `DEPLOY_RESULT=ROLLBACK_FAILED` when rollback cannot be verified.
9. Returns non-zero even when rollback succeeds.

This is the normal production mode because production Flyway migrations are
expected to be N-1 compatible.

### `disabled`

Use `ROLLBACK_MODE=disabled` only for an explicit exceptional deployment where a
schema migration is known not to be compatible with the immediately previous
application image.

If the new container fails, the script:

1. Records the primary failure stage and controlled error code.
2. Captures safe failed-container metadata and a bounded local log snapshot when
   possible.
3. Removes the failed new container when present.
4. Emits `DEPLOY_RESULT=FAILED_ROLLBACK_DISABLED`.
5. Emits `ERROR_DETAIL=ROLLBACK_DISABLED`.
6. Does not start the previous image.
7. Does not attempt any database rollback.

Recovery in this mode is deliberate: fix-forward application deployment,
manual incident response, or database restore from an operator-approved backup
procedure outside this repository.

## Flyway N-1 Compatibility Policy

Every Flyway migration intended for production must leave the database schema
compatible with both the new application image and the immediately previous
application image:

```text
DB vN + App N   -> works
DB vN + App N-1 -> works
```

This policy allows automatic application rollback without reverting the
database schema.

Prefer production migrations that are additive or tolerant:

- `ADD COLUMN`
- `ADD TABLE`
- `ADD INDEX`
- Initially tolerant constraints
- Expand/backfill/contract sequencing

Avoid shipping these in the same release that introduces the dependent code:

- `DROP COLUMN`
- `DROP TABLE`
- `RENAME COLUMN`
- `RENAME TABLE`
- Incompatible `NOT NULL` constraints
- Incompatible type changes
- Irreversible data destruction

Use expand/backfill/contract instead:

```text
Release A:
display_name exists

Release B:
ADD public_name
App B supports public_name
App A still uses display_name

Release C:
after App A no longer needs rollback
DROP display_name
```

Do not add `U*.sql`, Flyway Undo, automatic destructive-SQL detection, or
automatic database restore logic in this repository.

## Flyway Failure Semantics

If Flyway/PostgreSQL fails during a transactional migration:

```text
startup fails
database transaction rolls back
deployment fails
```

Do not run `flyway undo`.

If a migration succeeds and the new app then fails:

```text
DB migrates successfully
new app fails
automatic mode restores the previous app image
DB remains at the new schema
previous app must work because migrations are N-1 compatible
```

If a migration is destructive or not N-1 compatible:

```text
automatic app rollback is unsafe
ROLLBACK_MODE=disabled is required
```

There is no automatic database rollback in the workflow or script.

## Failed Container Diagnostics

For failed new deployments, the script emits controlled markers for the primary
failure and for safe failed-container metadata:

```text
PRIMARY_FAILURE_STAGE=VERIFY_READINESS
PRIMARY_FAILURE_ERROR_CODE=READINESS_FAILED
FAILED_CONTAINER_STATE=exited
FAILED_CONTAINER_EXIT_CODE=1
FAILED_CONTAINER_OOM_KILLED=false
FAILED_CONTAINER_DIAGNOSTICS=saved
FAILED_CONTAINER_LOG_PATH=/var/log/reals/deploy-failures/reals-backend-<timestamp>-sha-abcdef0.log
```

If `docker run` fails before Docker leaves an inspectable container,
diagnostics are reported as `unavailable` and the primary failure remains
`NEW_CONTAINER_START_FAILED`.

Local log snapshots default to:

```text
/var/log/reals/deploy-failures
```

The directory is created with restrictive permissions, snapshot files are
written with `0600` permissions, each snapshot stores only safe metadata plus a
bounded `docker logs --tail 200`, and only the most recent five snapshots
created by this mechanism are retained. Snapshot creation and retention cleanup
are best-effort: they must not block automatic rollback or disabled-mode
cleanup.

`DEPLOY_FAILURE_LOG_DIR` must not be `/`, must not contain `..` path
components, and if it already exists it must already be a secure dedicated
directory. The script never changes permissions on a pre-existing directory.

Snapshot files can contain sensitive runtime application logs. GitHub Actions
must receive only the controlled markers and local path. Operators should
inspect the file only through an authorized SSM session:

```text
sudo less <diagnostics path>
```

## Deployment Summary Safety

The production GitHub Step Summary includes controlled fields only:

- Environment
- Resolved revision
- Immutable image tag
- Target EC2 Name tag
- Rollback mode
- SSM result
- Deployment result
- Deployment stage
- Error code
- Error detail
- Failed container state
- Failed container exit code
- Failed container OOM killed
- Diagnostics
- Diagnostics path
- Readiness result
- Ping result
- Rollback occurred

The summary must not include the env file, database password, Firebase secrets,
Sightengine credentials, raw SSM stdout/stderr, failed-container log contents,
raw container logs or raw HTTP response bodies. When primary failure markers
exist, `Deployment stage` and `Error code` describe the original failed new
deployment rather than later rollback health-check activity.

## Current Production State

Implemented and deployed:

- AWS production infrastructure exists in `us-east-2`.
- The production backend runs on its dedicated EC2 instance.
- Production PostgreSQL RDS exists and is private.
- The production profile-photo S3 bucket exists and is private.
- Production DNS, HTTPS/TLS and Nginx are configured and working.
- The production Firebase project/configuration exists.
- Firebase App Check is enforced by the backend in `prod`.
- `Deploy AWS Prod` deployments have succeeded.
- Production Flyway migrations execute successfully.
- Production Sightengine photo analysis has been exercised successfully with
  real profile-photo uploads.

Manually validated:

- Android `prodDebug` has been tested against production with a registered App
  Check debug token.
- Authenticated production flows have been exercised, including authentication,
  profile/photo operations and profile activation.
- Security checks have been exercised for Auth/App Check requirements and
  normal-user denial of admin and protected Actuator endpoints.

Still pending operational work:

- Complete and document the production database/object-store backup and restore
  drill. This repository still performs application rollback only and never
  automatic database rollback.
- Publish legally reviewed public legal documents and finalize data-retention,
  purge/anonymization and backup-retention policy.
- Configure production-grade observability: metrics backend, alerting,
  dashboards, log retention and operational incident runbooks.
- Define production-grade admin/backoffice and safety operations, including
  access model, escalation ownership and evidence handling.
- Broaden FCM operational observability and retry/backoff policy.
- Complete Google Play / Play Integrity distribution validation before claiming
  production distribution coverage. The `prodDebug` App Check debug-token smoke
  is not Play Integrity evidence.
- Treat additional scaling work as evidence-triggered, not part of this current
  single-instance deployment path.
