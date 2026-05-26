---
apply: always
---

# Review

Prioritize findings over summaries.

Look first for:

- invalid or missing state-transition checks
- lock leaks or incorrect `ActiveEngagementLock` updates
- controller logic that belongs in services
- scheduler logic that duplicates services
- repository queries that encode business decisions
- authentication/current-user bypasses
- schema/entity/DTO mismatches
- missing tests or missing manual verification for changed business behavior

Report issues with file and line references when possible.
