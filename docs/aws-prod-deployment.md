# AWS Production Deployment Runbook

This repository contains a prepared, manually triggered AWS production backend
deployment workflow. The workflow is ready for repository review, but it does
not create AWS infrastructure and it will fail clearly until the production
GitHub Environment, OIDC role, EC2 host, database, storage, runtime env file and
public base URL exist.

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

Create a GitHub Environment named `prod`.

Required environment configuration:

| Name | Secret | Purpose |
| --- | --- | --- |
| `AWS_REGION` | No | AWS Region containing the production EC2 instance. |
| `AWS_DEPLOY_ROLE_ARN` | Usually no | Role assumed by GitHub OIDC. The workflow also accepts this as an environment secret. |
| `AWS_EC2_INSTANCE_NAME` | No | Exact EC2 `Name` tag value used for running-instance discovery. |
| `BACKEND_BASE_URL` | No | Public HTTPS base URL served by the production edge for smoke checks. |

The workflow fails during configuration validation if any required value is
missing. Do not hardcode ARNs, instance IDs, domains, passwords or secrets in
the workflow.

Required reviewers should be configured in the GitHub Environment before real
production use.

## AWS and EC2 Requirements

Production must use a separate AWS OIDC role, separate EC2 target and separate
runtime configuration from dev. The workflow resolves exactly one running EC2
instance by:

```text
Name=<AWS_EC2_INSTANCE_NAME>
instance-state-name=running
```

Zero matches or multiple matches fail the deployment before SSM execution.

The EC2 host must already have Docker, SSM managed-instance connectivity,
permission to pull `ghcr.io/gtestino92/reals-backend`, and a readable
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

## Pending Before Real Production

The repository mechanism is prepared, but real production deployment still
requires:

- GitHub Environment `prod` with approval and required variables.
- Production AWS OIDC role and least-privilege permissions.
- Production EC2 target with unique `Name` tag and SSM connectivity.
- Production RDS PostgreSQL and backup/restore procedure.
- Production S3 bucket and IAM runtime access.
- Production `BACKEND_BASE_URL`.
- Production `/etc/reals/backend.env`.
- Host-level GHCR read access when required.
- DNS/TLS/edge configuration.
- Production Firebase/App Check/Play Integrity work when separately approved.
