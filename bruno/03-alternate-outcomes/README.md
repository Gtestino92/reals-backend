# Alternate Outcomes

These are valid business outcomes that do not end in a successful second chat.

Run this folder with the `local` environment after starting the backend with `local-nodb` or `local-postgres`.

Execute requests in order from `00`.

## Covered

- first-chat rejection ends the match in `CHAT_REJECTED`
- visual rejection ends the match in `VISUAL_REJECTED`
- independent partner scheduling-proposal rejection reaches max rounds and closes the connection without second chat
- incompatible queued users produce no match
- mutual first-chat cancellation ends the match without penalty

## Scheduling rejection sequence

Requests `40` through `48b` demonstrate that rejection resolves only the
partner's pending proposal list. After users A and B submit non-overlapping
lists for the same `expectedRoundNumber`, request `42` rejects only B's list and
keeps round 1 open; request `42b` rejects A's list and opens round 2. The same
pattern repeats for rounds 2 and 3, where the second rejection in round 3 fails
the negotiation and closes the connection.

Manual one-list sequence for a fresh scheduling connection:

1. User A submits list A with `expectedRoundNumber: 1`.
2. User B posts `/negotiation/rejections` with `{ "expectedRoundNumber": 1 }`.
3. Negotiation remains `PENDING` in round 1.
4. User B submits list B with `expectedRoundNumber: 1`.
5. User A posts `/negotiation/rejections` with `{ "expectedRoundNumber": 1 }`.
6. Round 2 opens because both round-1 lists are now rejected.

Requests `40` and `41` also remain a manual fixture for clients submitting
same-round proposal lists without first polling the partner: both requests use
`expectedRoundNumber: 1` and both submissions are valid when there is no overlap.

## Not Covered Here

Time-driven outcomes are deferred until scheduler trigger/test endpoints exist:

- first chat timeout
- visual phase timeout
- scheduling timeout
- inactivity timeout
- penalty expiration
