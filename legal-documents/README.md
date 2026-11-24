# Legal Documents

This directory is the canonical backend repository source area for immutable,
versioned legal-document HTML files.

Canonical layout:

```text
legal-documents/
  terms/
    <version>/
      document.html
  privacy/
    <version>/
      document.html
  community-guidelines/
    <version>/
      document.html
```

Type-to-directory mapping:

```text
TERMS_OF_USE          -> terms
PRIVACY_NOTICE        -> privacy
COMMUNITY_GUIDELINES  -> community-guidelines
```

Once a legal document version has been published/configured as current, its
`document.html` must never be edited in place.

Any substantive content change requires a new version directory.

The configured content identity is SHA-256 calculated from the exact raw bytes
of `document.html`. The bytes are not trimmed, normalized, parsed as HTML,
serialized, or fetched from the public URL. Line-ending changes change the
hash. Format-only edits, whitespace changes, and HTML indentation changes also
change the hash because hashing is byte-exact.

Any public publication target must publish the exact canonical file bytes
without HTML transformation. The configured public URL is publication metadata;
the bundled canonical HTML file is the backend content identity source.

Do not add draft or placeholder production legal text here. Production legal
documents should only be added after legal review.

## Development fixture catalog

The three Spanish HTML files under version `dev-fixture-2026-11-24-v2` are fictional technical fixtures. Each page identifies itself as having no legal validity. Maven packages the canonical files through its existing resources mapping. Git checks out their HTML with LF line endings. For a content change, create a new immutable version and update the catalog and publication together.

The opt-in catalog lives in the final YAML document of [application-dev.yml](../src/main/resources/application-dev.yml). Its activation expression requires both `dev` and `dev-legal-fixtures` and excludes `prod`. The three dev-fixture-2026-11-23-v1 documents remain at their original URLs. Actions recorded against those versions do not satisfy v2. If the development service already has the opt-in profile enabled, deploying this revision selects v2 immediately. Publish and verify the Web files first. The ordinary development configuration has an empty catalog. The backend verifies bundled bytes on startup. The operator verifies the public HTML separately before activation.

| Type | Required action | Public URL | Raw SHA-256 |
| --- | --- | --- | --- |
| `TERMS_OF_USE` | `ACCEPTED` | [Open HTML](https://reals-web.pages.dev/legal/terms/dev-fixture-2026-11-24-v2/document.html) | `b652320230b6931f94c477f5f4345d759d5041a45ccd1164d4f4bdf172a2cc57` |
| `PRIVACY_NOTICE` | `ACKNOWLEDGED` | [Open HTML](https://reals-web.pages.dev/legal/privacy/dev-fixture-2026-11-24-v2/document.html) | `a83ebdb61fb16bdea3f6d195ba5ae8065976704f00b23e460e307d5d2b5377dc` |
| `COMMUNITY_GUIDELINES` | `ACCEPTED` | [Open HTML](https://reals-web.pages.dev/legal/community-guidelines/dev-fixture-2026-11-24-v2/document.html) | `e2e927cbd73db4ddf37050d349d67e962bac4b270f7ce276a35268db9fd35020` |

### Historical v1 publication

| Type | Required action | Public URL | Raw SHA-256 |
| --- | --- | --- | --- |
| `TERMS_OF_USE` | `ACCEPTED` | [Open HTML](https://reals-web.pages.dev/legal/terms/dev-fixture-2026-11-23-v1/document.html) | `c41d99b3c29a49a1628c3e6cb1166aa27590a5e3c5d2ab5092c4239e07254d63` |
| `PRIVACY_NOTICE` | `ACKNOWLEDGED` | [Open HTML](https://reals-web.pages.dev/legal/privacy/dev-fixture-2026-11-23-v1/document.html) | `cf4c1c0949c678d1a9d7ef300eeb1919ef785d021fd9726c9cd0f1a3a81cb831` |
| `COMMUNITY_GUIDELINES` | `ACCEPTED` | [Open HTML](https://reals-web.pages.dev/legal/community-guidelines/dev-fixture-2026-11-23-v1/document.html) | `12af43253f497dc4a89d99de0faf68919be04d33b2e331b62ca44795c66957b7` |

### Publication and activation order

1. Publish the reviewed Web development build and verify the bytes at all six public URLs against its manifest.
2. Deploy the reviewed backend revision to development. An existing `dev,dev-legal-fixtures` configuration selects v2 on startup. For a service using only `dev`, activating the fixtures requires explicitly adding `dev-legal-fixtures` and redeploying. Check the catalog for three v2 entries and the expected actions.
3. To restore v1 selection, use the previous backend revision with the opt-in profile. To disable fixtures, use only `dev`. Both rollback paths preserve published files and historical actions.

The [Web deployment procedure](https://github.com/Gtestino92/reals-web/blob/development/docs/deployment.md#development-legal-validation) describes publication, HTTP checks and the end-to-end rehearsal. The service, compliance gate, schema and action identity follow the existing [domain contract](../docs/commons/domain.md).
