# S3-Compatible Application Media Storage

Application media storage uses the S3-compatible `S3StorageService`. Shared dev
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

## Private media HTTP caching

Every application media PUT stores `Cache-Control: no-store`. A replacement
photo gets a fresh object key and the same policy as a new JPEG or PNG.
First and Second Chat use the same M4A upload path. Persisting the directive
lets storage return it on full and range GETs and instructs HTTP caches to
avoid storing private bytes. Expect more reads, including repeated range
requests, and potentially higher latency, bandwidth and storage charges.
URL issuance still signs the persisted bucket and key locally with the
configured duration and browser-facing endpoint. It needs no HEAD, rewrite,
query suffix or GetObject response override. Production requires presigned mode.

`no-store` does not revoke an issued bearer URL, retroactively delete cached
bytes, guarantee eviction from Coil, browser application storage or other
independent caches, or prevent a recipient from retaining a download.

Disposable MinIO returned the stored directive on signed 200 and 206
responses. Hosted R2 and intermediary delivery still require verification.
[Local provider evidence](media-security-assessment-phase2.md#backend-cache-policy-verification-2026-11-25)

### Rollout and existing objects

1. Validate the candidate build on disposable storage. Repeat HEAD, anonymous
   GET, signed full/range GET, expiry and deletion with synthetic media in a
   separately approved hosted R2 environment, including each delivery hop.
   Rehearse the single-object replacement below with representative headers and
   storage classes. Check access logs separately for signed URL redaction.
2. Roll out the writer to every instance, verify metadata on synthetic new
   and replacement uploads, and monitor storage read volume. Existing objects
   keep their old metadata until explicitly processed.
3. Run the following operator procedure once per approved environment and
   bucket. Include historical persisted buckets separately. After reopening
   traffic, check cleanup backlog and signed delivery of synthetic media.

### Operator procedure for existing private media

Use a private operator session with SDK/wire logging and shell tracing off.
Keep identifiers, values of custom metadata, signed URLs, payloads and secrets
out of reports. The default operation is a read-only inventory.

1. Explicitly select and verify the environment, account, endpoint, region,
   bucket and matching database. Use a read-only identity for the dry-run.
   Inspect versioning, retention/holds, encryption, tags, access controls,
   lifecycle rules and external writers. A copy can create a new version or
   reset last-modified time. Exclude objects where that would affect evidence,
   deadlines or access, or preservation cannot be demonstrated.
2. For each prefix `users/` and `chats/`, issue `ListObjectsV2` with
   `MaxKeys=100`. Follow `NextContinuationToken` until complete. Process at
   most one page at a time. Accept only full keys matching
   `users/<uuid>/profile-photos/<uuid>.jpg`, the same pattern with `.png`,
   or `chats/<uuid>/messages/<uuid>.m4a`. Require canonical UUID segments.
   Match bucket **and** key to the persisted photo or audio reference.
   Defer null or ambiguous bucket references, unknown keys,
   missing references, and keys with any outstanding media cleanup task
   (including guards, leased and failed tasks). Preserve administrative
   evidence and retained account data. Never infer disposability from block,
   moderation or account status. Leave ambiguous and evidence-sensitive cases
   for review. Do not delete objects, edit references or acknowledge tasks.
3. HEAD eligible objects. Count compliant (`CacheControl == "no-store"`),
   eligible, missing, deferred and failed entries. Inventory the full set of
   writable HTTP and custom metadata and the effective storage class. Review
   only aggregate counts and estimated operations before authorizing writes.
4. For mutation, obtain explicit operator approval for this bucket, batch
   limit and maintenance window. Take exclusive control: stop all application
   replicas, uploads, replacements, deletes, account deletion/finalization,
   cleanup workers, retries, local tools and other writers. Drain in-flight
   requests, transactions, storage calls and worker leases. If provider
   lifecycle deletion, replication or another writer can still race, defer.
   Re-inventory under this freeze and keep it until all copies have settled.
5. An in-flight copy may read old bytes, a concurrent delete may succeed, and
   the copy may then recreate the key. Source ETag matching checks the source
   and gives no portable destination-existence guarantee at commit. A DB lock
   or an extra HEAD cannot fence an object-store delete. R2 documents
   destination conditions for CopyObject as a provider-specific beta. This
   procedure relies on exclusive access across both providers.
   [R2 destination conditions for CopyObject](https://developers.cloudflare.com/r2/api/s3/extensions/#conditional-operations-in-copyobject-for-the-destination-object)
6. Within the approved bound, recheck eligibility and HEAD immediately before
   each copy. Skip compliant or missing objects. Capture a trusted full-object
   checksum, or stream a GET to SHA-256 in memory before and after the copy
   without retaining bytes. An ETag alone is not a portable content checksum.
   Issue one SDK `CopyObject` with source and destination the **same bucket
   and key**, `CopySourceIfMatch` from the fresh HEAD, and
   `MetadataDirective=REPLACE`. Set `CacheControl=no-store` and explicitly
   carry forward ContentType, ContentDisposition, ContentEncoding,
   ContentLanguage, Expires, all user metadata and effective storage class.
   Preserve any additional applicable headers, tags, ACL and encryption
   settings with provider-supported semantics verified in rehearsal. Defer
   unsupported cases. The server copies the original body.
7. Require SDK success and HEAD the original key. Compare type, length, class,
   custom metadata, all preserved attributes and the checksum, and verify the
   new directive. On a timeout or ambiguous result, keep the freeze, allow
   outstanding operations to settle and verify against the pre-copy record
   before retrying. Halt on mismatch, precondition failure or loss of
   exclusivity. Keep restricted verification records for unresolved objects.
   Never recreate a missing key from a manifest or backup.
8. Resume incomplete batches only under a confirmed freeze with fresh checks.
   Re-list from the beginning if a checkpoint is stale. Already verified
   compliant objects need no write. Resolve ambiguous copies before counting
   them as complete. Publish aggregate totals for pages, examined, compliant,
   copied and verified, deferred, missing and failed. Release the window
   only after settling all operations. Resume existing lifecycle workers with
   their original queues and deadlines.

Photo replacement and deletion enqueue cleanup of the old object, and failed
uploads can trigger immediate compensation or delayed guard cleanup. Current
account deletion removes ephemeral operational data and retains media/evidence
references. The maintenance window covers both manual and scheduled account
transitions so their eligibility and retention decisions remain authoritative.

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
`STORAGE_S3_READ_URL_MODE=PUBLIC`; production media reads must use `PRESIGNED`.

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
