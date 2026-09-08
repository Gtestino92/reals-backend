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
