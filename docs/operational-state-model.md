# Operational State Model

This document explains how to read Reals state during operations and debugging.
Some rows intentionally remain in historical successful states after the active
work has moved to another aggregate or has closed.

## State Categories

Active/actionable state is the state that can still affect a user or require a
job to act. It should be determined from the current aggregate owner, not from a
single older source row.

Historical successful state records that a step succeeded. It can remain useful
for debugging and audit-like inspection even after downstream work has ended.

Terminal/closed state means the aggregate is no longer actionable. Closed
connections should not hold active engagement locks.

Home visibility is the user-facing signal for actionable work. Internal records
can remain for history after they are no longer visible or actionable in Home.

## Expected Historical States

- A `Match` in `VISUAL_APPROVED` can remain as historical evidence that mutual
  visual approval succeeded, even after the derived `Connection` is `CLOSED`.
- A `ScheduleNegotiation` in `CONFIRMED` can remain as historical evidence that
  a second-chat time was agreed, even after the second-chat lifecycle ended.
- Accepted or rejected `ScheduleProposal` rows can remain as historical artifacts
  after the connection or chat is closed.
- A second `Chat` in `EXPIRED` can be an intermediate read-only state before it
  becomes `CLOSED`.

These states should not be treated as dangling work by themselves.

## Operational Signals

Use these signals to decide whether work is still active/actionable:

- `active_engagement_locks` for current capacity consumption.
- `Connection.state` for the current visual/scheduling/second-chat aggregate.
- `Chat.status` for current chat actionability.
- Deadlines such as `timeout_at`, `scheduling_expires_at`, and `read_only_until`.
- Home response visibility and actionability.
- Pending proposals or negotiations only when the parent connection is still
  actionable.

## Not Dangling By Itself

- `matches.state = 'VISUAL_APPROVED'` with a derived closed connection is
  historical, not necessarily stuck.
- `schedule_negotiations.status = 'CONFIRMED'` with a closed connection or an
  expired/closed second chat is historical, not necessarily stuck.
- Old accepted or rejected proposals are expected historical artifacts.
- Closed connections should not retain rows in `active_engagement_locks`.

## Query Guidance

Active locks by type:

```sql
select engagement_type, count(*)
from active_engagement_locks
group by engagement_type;
```

Dynamic engagement capacity can be inspected with
`docs/engagement-capacity-diagnostics.sql`. It derives current/recent
reliability score, effective Match and Connection caps, active lock counts,
headroom and natural overshoot from `users`, `user_reliability_events` and
`active_engagement_locks`. The query is for operational/product analysis only,
not runtime application logic, and it cannot reconstruct indefinite historical
score/cap trajectories after expired reliability events have been deleted.

Active/actionable connections:

```sql
select state, count(*)
from connections
where state in (
  'SCHEDULING_PENDING',
  'SCHEDULING_PHASE',
  'SECOND_CHAT_SCHEDULED',
  'SECOND_CHAT_AVAILABLE',
  'SECOND_CHAT'
)
group by state;
```

Active/actionable chats:

```sql
select chat_type, status, count(*)
from chats
where status in ('AVAILABLE', 'ACTIVE')
group by chat_type, status;
```

Read-only second chats still waiting for final closure:

```sql
select count(*)
from chats
where chat_type = 'SECOND_CHAT'
  and status = 'EXPIRED'
  and read_only_until > now();
```

Historical visual-approved matches whose derived connection is already closed:

```sql
select count(*)
from matches m
join connections c on c.match_id = m.id
where m.state = 'VISUAL_APPROVED'
  and c.state = 'CLOSED';
```

Confirmed negotiations whose parent connection is already closed:

```sql
select count(*)
from schedule_negotiations n
join connections c on c.id = n.connection_id
where n.status = 'CONFIRMED'
  and c.state = 'CLOSED';
```

Closed connections that still hold active locks should be investigated:

```sql
select count(*)
from connections c
join active_engagement_locks l on l.engagement_id = c.id
where c.state = 'CLOSED'
  and l.engagement_type = 'CONNECTION';
```
