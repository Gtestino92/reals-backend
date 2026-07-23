# AWS Dev Deployment Runbook

This repository now supports a manually triggered AWS dev backend deployment.
The workflow deploys an immutable GHCR image that was already built, scanned,
and published by the existing CI workflow on `development`.

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

The deployment workflow does not create or modify AWS infrastructure. It does
not build or push Docker images. It sends the repository-owned deployment script
to the selected EC2 instance through Systems Manager Run Command.

## Repository Branch and Workflow Registration

`development` is the repository default branch, the normal pull-request target,
and the only allowed source branch for AWS dev deployments. Because
`development` is the default branch, GitHub registers the manual
`Deploy AWS Dev` workflow directly from:

```text
development:.github/workflows/deploy-aws-dev.yml
```

No duplicate workflow file on `master` is required for AWS dev deployment.

Normal workflow execution is:

```text
Actions
-> Deploy AWS Dev
-> Run workflow
-> Branch: development
-> revision: blank
```

The workflow itself still rejects any selected ref other than:

```text
refs/heads/development
```

The immutable image tag is calculated automatically from the selected
`development` SHA as `sha-<first 7 characters>`.

Branch roles:

- `development`: repository default branch, integration branch, default target
  for feature PRs, source branch for AWS dev image publication, and only
  allowed source for `Deploy AWS Dev`.
- `master`: not the default branch, not required for AWS dev workflow
  registration, reserved for a future deliberate production promotion, and must
  not receive unrelated direct functional changes.

Future production changes should arrive through a reviewed promotion from
`development` to `master`. Do not treat `master` as currently synchronized with
`development`, and do not treat it as production-ready merely because the branch
exists.

## Normal Manual Deployment

1. Merge the change into `development`.
2. Wait for the existing `CI` workflow on `development` to pass.
3. Confirm the CI `publish-image` job pushed `ghcr.io/gtestino92/reals-backend:sha-<short-sha>`.
4. Open GitHub Actions.
5. Select `Deploy AWS Dev`.
6. Select branch `development`.
7. Leave `revision` blank.
8. Press `Run workflow`.

When `revision` is blank, the workflow deploys `${{ github.sha }}` for the
selected `development` revision and calculates the image tag automatically as:

```text
sha-<first 7 characters of the resolved full SHA>
```

Operators do not copy SHAs, create tags, open SSM shells, or restart containers
manually.

## Explicit Rollback Deployment

Use the optional `revision` input only for a deliberate rollback to an older
image that was already published from `development`.

Requirements:

- The value must be a full 40-character Git commit SHA.
- The commit must exist in the repository history fetched by the workflow.
- The commit must be an ancestor of the current `development` branch.
- The corresponding GHCR image must already exist as `sha-<short-sha>`.

The workflow still calculates the immutable image tag automatically from the
provided full SHA.

## Automatic Rollback

`ops/aws/deploy-backend.sh` captures the currently configured container image
reference, actual local image ID, and running state before replacement. It pulls
and verifies the requested image before stopping the existing container.

If the new container fails to start, or if internal EC2 checks fail against
`127.0.0.1`, the script:

1. Removes the failed container if present.
2. Recreates `reals-backend` from the previously captured local image ID.
3. Runs the same internal readiness and ping checks.
4. Prints `DEPLOY_RESULT=ROLLED_BACK` when rollback succeeds.
5. Prints `DEPLOY_RESULT=ROLLBACK_FAILED` when rollback cannot be verified.
6. Returns non-zero even when rollback succeeds.

The script does not delete the previous image and does not run broad Docker
cleanup commands.

The workflow then performs public HTTPS smoke checks through Nginx only after
SSM succeeds. If internal checks succeeded but public readiness or ping fails,
the workflow fails but does not automatically roll back. Public-path failure may
come from Nginx, TLS, DNS, security-group routing, or another host-level issue
unrelated to the application image. Inspect the public path before deciding
whether to run an explicit image rollback.

## SSM Command Polling

The workflow does not use the default AWS CLI `ssm wait command-executed`
waiter. It polls `aws ssm get-command-invocation` every 5 seconds for up to 15
minutes.

Non-terminal statuses are:

- `Pending`
- `InProgress`
- `Delayed`

`Success` is the only successful terminal status. `Cancelled`, `TimedOut`,
`Failed`, `Cancelling`, and unknown terminal statuses fail the workflow. The
poller temporarily tolerates `InvocationDoesNotExist` immediately after
`send-command`.

## Deployed Revision

The GitHub workflow summary shows:

- environment: `dev`;
- resolved full Git revision;
- immutable image tag;
- configured EC2 `Name` tag;
- SSM command result;
- deployment stage and controlled error code;
- readiness and ping results;
- whether rollback occurred.

The remote script also verifies the image OCI label
`org.opencontainers.image.revision` against the requested full SHA. That label
is the authoritative image-to-commit binding for the host-side deployment.

`/actuator/info` remains administrator-protected in hosted environments. Do not
depend on it for public deployment validation.

## Actions Output Safety

GitHub Actions receives only controlled deployment markers from the remote
script, such as:

```text
DEPLOY_STAGE=...
DEPLOY_RESULT=SUCCESS
DEPLOY_RESULT=ROLLED_BACK
DEPLOY_RESULT=ROLLBACK_FAILED
DEPLOYED_REVISION=<full-sha>
DEPLOYED_IMAGE=<immutable-image>
ROLLBACK_IMAGE=<previous-image-id>
ERROR_CODE=...
```

The workflow summary does not include arbitrary SSM stdout, stderr, Docker
logs, HTTP response bodies, `/etc/reals/backend.env`, environment values,
credentials, tokens, or application logs. Inspect detailed container logs on the
EC2 host through an authorized SSM session.

## GitHub Environment `dev`

Create a GitHub Environment named `dev`.

Required environment variables:

| Name | Secret | Purpose |
| --- | --- | --- |
| `AWS_REGION` | No | AWS Region containing the dev EC2 instance. |
| `AWS_DEPLOY_ROLE_ARN` | Usually no | Role assumed by GitHub OIDC. Store as an environment secret if repository policy treats role ARNs as sensitive. |
| `AWS_EC2_INSTANCE_NAME` | No | Exact EC2 `Name` tag value used for running-instance discovery. |
| `BACKEND_BASE_URL` | No | Public HTTPS base URL served by Nginx for smoke checks. |

Optional required reviewers can be added later in the GitHub Environment
settings without changing the workflow.

Do not store application runtime secrets in GitHub Actions for this deployment
flow. The EC2 host reads application configuration from `/etc/reals/backend.env`.

## AWS OIDC Provider

Configure the IAM OIDC identity provider for GitHub Actions:

```text
Provider URL: token.actions.githubusercontent.com
Audience: sts.amazonaws.com
```

## AWS Deployment Role Trust Template

Replace placeholders before applying. Do not commit real account IDs.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<aws-account-id>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:Gtestino92/reals-backend:environment:dev"
        }
      }
    }
  ]
}
```

The subject restriction uses the GitHub Environment identity. The workflow also
rejects any selected ref other than `refs/heads/development`.

## AWS Deployment Role Permissions Template

Replace placeholders before applying. Prefer scoping the EC2 instance resource
to the approved dev instance ARN when stable; otherwise use a tag boundary for
the dev target where IAM condition keys support it.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DiscoverDevInstance",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeInstances"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestedRegion": "<aws-region>"
        }
      }
    },
    {
      "Sid": "SendRunShellScriptToDevInstance",
      "Effect": "Allow",
      "Action": [
        "ssm:SendCommand"
      ],
      "Resource": [
        "arn:aws:ec2:<aws-region>:<aws-account-id>:instance/<dev-instance-id>",
        "arn:aws:ssm:<aws-region>::document/AWS-RunShellScript"
      ]
    },
    {
      "Sid": "ReadRunCommandResult",
      "Effect": "Allow",
      "Action": [
        "ssm:GetCommandInvocation",
        "ssm:ListCommandInvocations"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestedRegion": "<aws-region>"
        }
      }
    }
  ]
}
```

`ec2:DescribeInstances` and SSM command-result reads require broad resource
scope in common IAM configurations because these read APIs are not cleanly
resource-scoped. Keep them constrained by Region and by the workflow's exact
instance discovery rule.

Do not grant EC2 mutation permissions, IAM mutation permissions, RDS access, S3
application-data access, Parameter Store secret reads, broad `ssm:*`, or
administrator access.

## EC2 Prerequisites

The host must already have:

- SSM managed-instance connectivity.
- Docker and a running Docker daemon.
- `/etc/reals/backend.env`, readable by the SSM command execution user.
- Nginx already proxying HTTPS traffic to `127.0.0.1:8080`.
- Permission to pull `ghcr.io/gtestino92/reals-backend`.
- Enough disk space for the new and immediately previous Docker images.

The deployment does not transport a GHCR token. If the GHCR package is private,
configure host-level read-only Docker credentials before deployment. This is a
credential-hardening concern to revisit with least-privilege package access or
ECR mirroring if private pulls become operationally painful.

## Remote Runtime Defaults

The deployment script defaults to:

```text
IMAGE_REPOSITORY=ghcr.io/gtestino92/reals-backend
CONTAINER_NAME=reals-backend
ENV_FILE=/etc/reals/backend.env
PORT_BINDING=127.0.0.1:8080:8080
READINESS_URL=http://127.0.0.1:8080/actuator/health/readiness
PING_URL=http://127.0.0.1:8080/api/ping
```

Override these only through controlled host environment variables when the dev
runtime shape intentionally changes.

## Future Production Design

Do not reuse the dev workflow or role for production.

Production preparation is a separate task. Before the first production
deployment, the team must:

1. Review commits exclusive to `master`.
2. Reconcile branch divergence intentionally.
3. Create a reviewed promotion from `development` to `master`.
4. Validate Flyway migrations, backups, and rollback implications.
5. Configure a separate GitHub Environment such as `prod`.
6. Configure a separate AWS OIDC deployment role.
7. Use a separate manually approved production deployment workflow.
8. Deploy an immutable SHA or release tag, never moving `master` or `latest`.

The intended production design also requires separate EC2/runtime variables,
required reviewers or environment approval, a documented production rollback
procedure, and no shared dev/prod AWS role or environment configuration.

When release versioning is introduced, prefer an immutable release tag such as
`v1.0.0`. Until then, an exact SHA known to belong to the reviewed production
promotion on `master` is acceptable.

## Automatic Deployment Checklist

Do not enable automatic AWS dev deployment yet. Revisit only after:

- Multiple successful manual deployments.
- Tested automatic rollback.
- RDS backup and restore drill completed.
- Minimum logs and alarms configured.
- Sufficient disk monitoring.
- App Check rollout stable.
- No unresolved migration rollback risks.

Production deployment should remain manually approved even if dev later becomes
automatic.
