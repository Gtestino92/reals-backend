# Alternate Outcomes

These are valid business outcomes that do not end in a successful second chat.

Run this folder with the `local` environment after starting the backend with `local-nodb`.

Execute requests in order from `00`.

## Covered

- first-chat rejection ends the match in `CHAT_REJECTED`
- visual rejection ends the match in `VISUAL_REJECTED`
- explicit scheduling round rejection reaches max rounds and closes the connection without second chat
- incompatible queued users produce no match
- mutual first-chat cancellation ends the match without penalty

## Not Covered Here

Time-driven outcomes are deferred until scheduler trigger/test endpoints exist:

- first chat timeout
- visual phase timeout
- scheduling timeout
- inactivity timeout
- penalty expiration
