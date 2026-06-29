# Cloudflare R2 Profile Photo Storage

Cloudflare R2 is used as the S3-compatible object store for shared/dev/prod-like
MVP environments. Local development continues to use MinIO through Docker
Compose.

The upload flow remains backend-mediated:

```text
Android multipart upload
-> Backend receives MultipartFile
-> Backend performs technical validation
-> Backend uploads to S3-compatible storage
-> Backend stores provider, bucket and object key
-> Backend returns a renderable read URL generated from the object key
```

There is no direct Android-to-R2 upload in the MVP flow.

## Local MinIO vs R2

- MinIO is for local development and is still provisioned by `docker-compose.yml`.
- R2 is for shared/dev/prod-like MVP environments.
- Both use the same `S3StorageService` and `storage.s3.*` configuration.
- Buckets should stay private by default.
- API responses expose renderable read URLs, not storage keys or bucket names.
- The database stores profile-photo storage metadata, not read URLs. `storageKey`
  is the source of truth for retrieving a profile photo.

## R2 Bucket Setup

Create one R2 bucket per environment, for example a dev bucket and a prod bucket.
Do not reuse local MinIO bucket credentials in shared environments.

Required R2 setup:

- Bucket exists before the backend starts.
- Bucket access is private.
- R2 API token or access key has object read/write/delete permissions for that bucket.
- CORS/public bucket hosting is not required for MVP because reads use presigned URLs.

## Environment Variables

Use environment variables or the deployment platform's secret manager. Do not
commit real account IDs, bucket names, access keys or secrets.

```text
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

Legacy `S3_*` environment variable names are still accepted as fallbacks. Prefer
the `STORAGE_S3_*` names for new shared/dev/prod-like deployments.

## MVP Read Mode

Use:

```text
STORAGE_S3_READ_URL_MODE=PRESIGNED
```

No public bucket or `STORAGE_S3_PUBLIC_BASE_URL` is required in `PRESIGNED` mode.
`PUBLIC` mode should be reserved for intentionally public media.

## Current Non-Goals

- No direct Android-to-R2 upload.
- No generated thumbnails or previews.
- No photo reordering endpoint in this storage setup.
- No object migration is needed before real user data exists.

## Manual R2 Verification Checklist

1. Start the backend with the R2 environment variables above.
2. Upload a valid profile photo through `POST /api/me/profile/photos`.
3. Confirm an object appears in the R2 bucket under `users/<userId>/profile-photos/<photoId>.<ext>`.
4. Confirm the upload response returns a renderable presigned `url`.
5. Call `GET /api/me/profile/photos` and confirm it returns fresh renderable URLs.
6. Replace a photo through `PUT /api/me/profile/photos/{photoId}/file`.
7. Confirm the replacement object appears, the response URL points at the new object key and the old object cleanup path behaves as expected.
8. Delete a photo and confirm object deletion behaves as expected.
9. Upload the required number of valid photos and activate the profile.
