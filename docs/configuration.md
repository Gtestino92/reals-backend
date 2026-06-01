# Configuration

Production and shared development profiles should receive environment-specific values from environment variables or a secret manager, not from committed files.

## Profiles

- `local-nodb`: local H2 file database, dev auto-auth and schedulers disabled.
- `dev`: external database, Flyway enabled by default, schedulers enabled by default.
- `prod`: external database, Flyway enabled, schedulers enabled.
- `test`: H2 in-memory test profile under `src/test/resources`.

## Placeholder Reference

`deploy/helm/values-dev.yaml` and `deploy/helm/values-prod.yaml` are temporary Helm-style values references. Their exact role is still undecided because the final Helm chart may live in a separate infrastructure repository.

If these values files are kept, they should contain deploy-time concerns such as image tag, replicas, probes, resource limits, service/ingress settings and references to config or secret keys. Application behavior settings such as scheduler cadence, chat durations and product limits are defined directly in `src/main/resources/application-dev.yml` and `src/main/resources/application-prod.yml`.

Use the deployment platform or secret manager to inject values such as:

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `FIREBASE_SERVICE_ACCOUNT_PATH`
- `S3_PROFILE_PHOTOS_BUCKET`
- `S3_REGION`
- `IDENTITY_VERIFICATION_API_KEY`

For AWS deployments, prefer IAM roles for S3 access instead of long-lived access keys. In that setup the application usually only needs bucket/region settings; AWS credentials come from the runtime role.

## Helm Chart Location

The Helm chart does not have to live in this repository. A common setup is:

- Application repository: source code, Dockerfile/build pipeline metadata and per-environment `values-*.yaml`.
- Infrastructure repository: shared Helm chart, Kubernetes templates, cluster-specific configuration and deployment automation.

In that setup, CI builds and publishes the image from this repository, then the deployment pipeline applies the chart from the infrastructure repository using this app's values file.

## Environment-Specific Files

`application-dev.yml` and `application-prod.yml` intentionally repeat operational settings such as scheduler cadence, chat durations, scheduling limits and profile photo limits. This makes deploy-time behavior explicit without relying on implicit inheritance from `application.yml`.

These files should use placeholders only for environment-specific or secret-backed values, such as database credentials, S3 bucket/region, Firebase service account location and future identity-verification credentials.

Do not commit real credentials in any `application-*.yml` file.

## Matchmaking Tuning

`matchmaking.candidate-pair-limit` controls how many SQL-filtered candidate pairs are scored per matchmaking selection. Local and test profiles keep this low for deterministic, cheap checks. Dev/prod use higher starting values and should be adjusted using queue size, job duration and match creation metrics.

`matchmaking.min-compatibility-score` discards scored pairs below the configured threshold. `matchmaking.early-accept-compatibility-score` stops scoring as soon as a strong enough pair is found. Scores are expected to be normalized from `0.0` to `1.0`.

## How Injection Works

The application only sees environment variables such as `DATABASE_PASSWORD` or `S3_PROFILE_PHOTOS_BUCKET`. The CI/CD or runtime platform is responsible for resolving those values from its own variable store or secret manager before starting the process.

Example flow:

1. `application-prod.yml` references `${DATABASE_PASSWORD}`.
2. `deploy/helm/values-prod.yaml` says `DATABASE_PASSWORD` should come from secret key `database_password`.
3. The deploy pipeline reads that secret from its configured secret manager.
4. The pipeline starts the app with `DATABASE_PASSWORD=<resolved secret>`.
5. Spring resolves `${DATABASE_PASSWORD}` at startup.
