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
- visual approval rejection when the partner personal message exists but was not read
- non-participant scheduling proposal rejection
- past scheduling proposal rejection
- duplicate proposal list in the same round
- own-proposal acceptance rejection
- accepting before submitting own proposal rejection
- proposal after confirmed negotiation rejection
- non-participant first-chat cancellation
- non-participant second-chat cancellation
- safety cancellation of an active second chat, including penalty application to the reported participant

`49 Stranger Cancel First Chat Should Fail` and `50 Stranger Cancel Second Chat Should Fail` validate that users outside the match cannot close another pair's chat.

## Deferred

These are not covered here because they depend on time passing, scheduler jobs, direct database manipulation or not-yet-exposed admin/test endpoints:

- chat timeout
- inactivity timeout
- match expiration
- visual phase expiration
- scheduling timeout
- penalty expiration
- max active match/connection limits at scale
