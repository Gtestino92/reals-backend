# Timeout Outcomes

Manual Bruno flow for local/dev job-driven outcomes.

This folder uses `/api/dev/...` endpoints that are only exposed with `local`, `local-nodb`, `local-postgres` or `dev` profiles. Those endpoints make deadlines deterministic: they move a deadline to the past and then trigger the real scheduled job.

Run with the `local` Bruno environment after starting the backend with `local-nodb` or `local-postgres`.

## Covered

- first chat timeout expires the match and makes the active first-chat lookup unusable
- visual phase timeout expires the match before a connection exists
- scheduling timeout fails the negotiation and closes the connection
- scheduled second-chat availability exposes the chat when the confirmed time is due
- entering the available second chat activates it before timeout testing
- second chat timeout expires the chat and closes the connection

## Not Covered Here

- inactivity abandonment and penalty expiration are covered by integration tests for now
- real cron cadence is not tested manually; these requests trigger the job directly
