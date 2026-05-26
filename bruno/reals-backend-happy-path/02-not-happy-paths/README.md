# Not Happy Paths

Run this folder with the `local` environment after starting the backend with `local-nodb`.

These requests are intentionally stateful and should be executed in order from `00`.

## Covered

- profile activation without required photos
- draft profile matchmaking rejection
- invalid and duplicate photo positions
- non-participant chat message rejection
- duplicate chat decision rejection
- non-participant visual profile/decision rejection
- non-participant scheduling proposal rejection
- past scheduling proposal rejection
- duplicate proposal in the same round
- own-proposal acceptance rejection
- accepting before submitting own proposal rejection
- proposal after confirmed negotiation rejection
- invalid first-chat close
- non-participant second-chat close

`43 Close First Chat Should Fail` runs after the first chat has already been finished by the approval flow, so it validates that the endpoint rejects an invalid close request. A stricter "active first chat cannot be closed through this endpoint" check would need a separate isolated setup or an admin/test fixture endpoint.

## Deferred

These are not covered here because they depend on time passing, scheduler jobs, direct database manipulation or not-yet-exposed admin/test endpoints:

- chat timeout
- inactivity timeout
- match expiration
- visual phase expiration
- scheduling timeout
- penalty expiration
- max active match/connection limits at scale
