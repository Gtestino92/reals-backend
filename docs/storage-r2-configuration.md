# S3-Compatible Profile Photo Storage

Profile photo storage uses the S3-compatible `S3StorageService`, so shared dev
and prod-like environments can use Cloudflare R2, hosted MinIO, or another
S3-compatible object store.

For the first shared dev environment, two practical options are:

- Cloudflare R2: managed object storage, no storage server to operate.
- Hosted MinIO: useful when the runtime platform supports persistent
  disks/volumes and you want dev storage to match local MinIO behavior more
  closely.

Local development continues to use MinIO through Docker Compose.

The upload flow remains backend-mediated:

```text
Android multipart upload
-> Backend receives MultipartFile
-> Backend performs technical validation
-> Backend uploads to S3-compatible storage
-> Backend stores provider, bucket and object key
-> Backend returns a renderable read URL generated from the object key
```

There is no direct Android-to-object-storage upload in the MVP flow.

## Local MinIO vs Shared Storage

- Local MinIO is provisioned by `docker-compose.yml` for developer-machine
  testing.
- R2 is a good default for shared/dev/prod-like MVP environments.
- Hosted MinIO is acceptable for shared dev when it runs on the deployment
  platform with persistent storage.
- All options use the same `S3StorageService` and `storage.s3.*` configuration.
- Buckets should stay private by default.
- API responses expose renderable read URLs, not storage keys or bucket names.
- The database stores profile-photo storage metadata, not read URLs. `storageKey`
  is the source of truth for retrieving a profile photo.

Do not point a shared deployed backend at a developer-machine MinIO instance.
That makes the environment dependent on a laptop, home network and local disk.

Use environment variables or the deployment platform's secret manager. Do not
commit real account IDs, bucket names, access keys or secrets.

## Option A: Cloudflare R2

Create one R2 bucket per environment, for example a dev bucket and a prod bucket.
Do not reuse local MinIO bucket credentials in shared environments.

Required R2 setup:

- Bucket exists before the backend starts.
- Bucket access is private.
- R2 API token or access key has object read/write/delete permissions for that bucket.
- CORS/public bucket hosting is not required for MVP because reads use presigned URLs.

R2 environment variables:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_PRESIGNED_URL_ENDPOINT=https://<cloudflare-account-id>.r2.cloudflarestorage.com
STORAGE_S3_REGION=auto
STORAGE_S3_BUCKET=<r2-bucket-name>
STORAGE_S3_ACCESS_KEY_ID=<r2-access-key-id>
STORAGE_S3_SECRET_ACCESS_KEY=<r2-secret-access-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
STORAGE_S3_SIGNED_URL_DURATION_MINUTES=15
```

`STORAGE_S3_PRESIGNED_URL_ENDPOINT` may be omitted when it is the same as
`STORAGE_S3_ENDPOINT`; the backend falls back to the main endpoint for presigned
URLs.

`STORAGE_S3_REGION=auto` is the preferred R2 value. `us-east-1` can also work as
an S3 compatibility alias, but `auto` makes the provider intent explicit.

R2 uses explicit static credentials. Keep `STORAGE_S3_CREDENTIALS_MODE=STATIC`
and do not use `DEFAULT_CHAIN` for R2.

## Option B: Hosted MinIO

Hosted MinIO can be used for the shared dev environment when the platform gives
it persistent storage:

- Render has MinIO deployment support/templates backed by a persistent disk.
- Railway has MinIO templates and service volumes.

References:

- Render MinIO guide: `https://render.com/docs/deploy-minio`
- Railway MinIO template: `https://railway.com/deploy/minio-object-storage`

Use hosted MinIO for development/internal environments unless a production
operations plan exists for backups, upgrades, credentials, monitoring and
capacity.

Recommended shape:

```text
Backend service
  -> managed PostgreSQL
  -> MinIO S3 API service with persistent disk/volume
```

MinIO setup requirements:

- Persistent disk/volume mounted at MinIO's data directory.
- Bucket exists before profile photo upload tests.
- Access key and secret key are stored as platform secrets.
- The S3 API endpoint is reachable by the backend.
- The presigned URL endpoint is reachable by Android/Bruno clients.
- Path-style access is enabled.

MinIO environment variables:

```text
STORAGE_S3_CREDENTIALS_MODE=STATIC
STORAGE_S3_ENDPOINT=<backend-reachable MinIO S3 API URL>
STORAGE_S3_PRESIGNED_URL_ENDPOINT=<client-reachable MinIO S3 API URL>
STORAGE_S3_REGION=us-east-1
STORAGE_S3_BUCKET=<minio-bucket-name>
STORAGE_S3_ACCESS_KEY_ID=<minio-access-key>
STORAGE_S3_SECRET_ACCESS_KEY=<minio-secret-key>
STORAGE_S3_PATH_STYLE_ACCESS_ENABLED=true
STORAGE_S3_READ_URL_MODE=PRESIGNED
STORAGE_S3_SIGNED_URL_DURATION_MINUTES=15
```

`STORAGE_S3_ENDPOINT` and `STORAGE_S3_PRESIGNED_URL_ENDPOINT` may be different.
For example, the backend might upload through a private/internal MinIO hostname
while clients need a public HTTPS hostname in returned presigned URLs.

If the platform exposes the MinIO console and S3 API on different hostnames or
ports, use the S3 API endpoint here, not the console URL.

Legacy `S3_*` environment variable names are still accepted as fallbacks. Prefer
the `STORAGE_S3_*` names for new shared/dev/prod-like deployments.
The legacy credential-mode fallback is `S3_CREDENTIALS_MODE`; new
configuration should use `STORAGE_S3_CREDENTIALS_MODE`.

## MVP Read Mode

Use:

```text
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

No public bucket or `STORAGE_S3_PUBLIC_BASE_URL` is required in `PRESIGNED` mode.
`PUBLIC` mode should be reserved for intentionally public media outside
production. The backend refuses to start with `SPRING_PROFILES_ACTIVE=prod` and
`STORAGE_S3_READ_URL_MODE=PUBLIC`; production profile-photo reads must use
`PRESIGNED`.

## Current Non-Goals

- No direct Android-to-object-storage upload.
- No generated thumbnails or previews.
- No object migration is needed before real user data exists.

## Manual Verification Checklist

1. Start the backend with the selected S3-compatible storage environment
   variables.
2. Upload a valid profile photo through `POST /api/me/profile/photos`.
3. Confirm an object appears in the bucket under `users/<userId>/profile-photos/<photoId>.<ext>`.
4. Confirm the upload response returns a renderable presigned `url`.
5. Call `GET /api/me/profile/photos` and confirm it returns fresh renderable URLs.
6. Replace a photo through `PUT /api/me/profile/photos/{photoId}/file`.
7. Confirm the replacement object appears, the response URL points at the new object key and the old object cleanup path behaves as expected.
8. Delete a photo and confirm object deletion behaves as expected.
9. Upload the required number of valid photos and activate the profile.
