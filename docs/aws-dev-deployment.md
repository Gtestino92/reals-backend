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

If the new container fails readiness or `/api/ping`, the script:

1. Captures a sanitized log tail from the failed container.
2. Removes the failed container.
3. Recreates `reals-backend` from the previously captured local image ID.
4. Runs the same readiness and ping checks.
5. Prints `DEPLOY_RESULT=ROLLED_BACK` when rollback succeeds.
6. Prints `DEPLOY_RESULT=ROLLBACK_FAILED` when rollback cannot be verified.
7. Returns non-zero even when rollback succeeds.

The script does not delete the previous image and does not run broad Docker
cleanup commands.

## Deployed Revision

The GitHub workflow summary shows:

- environment: `dev`;
- resolved full Git revision;
- immutable image tag;
- configured EC2 `Name` tag;
- SSM command result;
- readiness and ping results;
- whether rollback occurred.

The remote script also verifies the image OCI label
`org.opencontainers.image.revision` against the requested full SHA. That label
is the authoritative image-to-commit binding for the host-side deployment.

`/actuator/info` remains administrator-protected in hosted environments. Do not
depend on it for public deployment validation.

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

The intended production design is:

- Separate workflow, for example `Deploy AWS Prod`.
- Separate GitHub Environment `prod`.
- Separate AWS OIDC deployment role.
- Separate EC2/runtime variables.
- Required reviewers or environment approval.
- Deployment only from `master` or an immutable release tag.
- Exact immutable image revision; never `latest` or moving `master`.
- Backups and restore validation before deployment.
- Documented production rollback procedure.
- No shared dev/prod AWS role or environment configuration.

When release versioning is introduced, prefer an immutable release tag such as
`v1.0.0`. Until then, an exact SHA known to belong to `master` is acceptable.

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
