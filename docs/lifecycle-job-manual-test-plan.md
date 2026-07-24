# Lifecycle Manual Test Plan

This plan is for local end-to-end validation with two real clients:

- User A on the Android emulator.
- User B on a physical Android phone.

The backend remains the source of truth for lifecycle expiration. Android should
show countdowns/warnings from backend timestamps, disable local actions when a
known deadline is reached and refresh when the backend rejects or expires a
flow.

Local profiles keep schedulers disabled, so use Bruno requests under:

```text
bruno/reals-backend-happy-path/10 - Local Dev Jobs
```

or the matching `/api/local-dev/jobs/.../run` and
`/api/local-dev/timeouts/...` endpoints when waiting for a long timeout is not
practical.

## Setup

1. Start the backend with the local profile used by the Android app.
2. Start the emulator and physical phone against the same backend base URL.
3. Log in with two different Firebase users.
4. Confirm both users provision successfully and have active profiles.
5. Confirm both devices register FCM tokens through `PUT /api/me/push-tokens`.
6. Keep Bruno configured with `baseUrl` only. Local-dev job and timeout
   endpoints do not require bearer auth.

Useful local helpers:

- `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`
- `POST /api/local-dev/jobs/chat-timeout/run`
- `POST /api/local-dev/jobs/inactivity-check/run`
- `POST /api/local-dev/jobs/visual-phase-expiration/run`
- `POST /api/local-dev/jobs/visual-review-reminder/run`
- `POST /api/local-dev/jobs/scheduling-activation/run`
- `POST /api/local-dev/jobs/scheduling-timeout/run`
- `POST /api/local-dev/jobs/second-chat-reminder/run`
- `POST /api/local-dev/jobs/second-chat-lifecycle/run`
- `POST /api/local-dev/jobs/user-reliability-cleanup/run`
- `POST /api/local-dev/timeouts/chats/{chatId}/expire-now`
- `POST /api/local-dev/timeouts/chats/{chatId}/read-only-expire-now`
- `POST /api/local-dev/timeouts/matches/{matchId}/visual-expire-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-available-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-expire-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-late-window-now`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-before-hard-cutoff`
- `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-past-hard-cutoff`

## Baseline Happy Path

Purpose: validate the complete user flow before forcing any timeout.

1. From both Android clients, enter matchmaking.
2. In Bruno, run `POST /api/local-dev/matchmaking/process?maxPairsPerRun=10`.
3. Refresh Home on both clients.
4. Confirm both clients show a first-chat action.
5. Open first chat on both clients.
6. Confirm the first-chat response exposes:
   - `expiresAt` / `timeoutAt`
   - `inactivityExpiresAt`
7. Send at least one message from each client.
8. Confirm `inactivityExpiresAt` moves forward after a new message.
9. Approve first chat from both clients.
10. Confirm both clients move to visual review.
11. Confirm visual review responses expose `visualExpiresAt`, and Home
    `VISUAL_REVIEW` pending actions expose `visualStartedAt` and
    `visualExpiresAt`.
12. Submit required visual-review personal messages/reads if the UI requires
    them.
13. Approve visual review from both clients.
14. Confirm Home shows scheduling as preparing or pending.
15. If you do not want to wait for `schedulingAvailableAt`, run:
    `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-available-now`
16. Run `POST /api/local-dev/jobs/scheduling-activation/run`.
17. Refresh Home on both clients and confirm scheduling is actionable.
18. Open scheduling on both clients.
19. Confirm negotiation response exposes `schedulingExpiresAt`.
20. Submit overlapping proposal slots from both clients and confirm scheduling.
21. Confirm Home shows second chat scheduled with `availableAt`, `expiresAt`
    and `durationMinutes`.
22. If you do not want to wait for the scheduled start, run:
    `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now`
23. Run `POST /api/local-dev/jobs/second-chat-lifecycle/run`.
24. Refresh Home and confirm second chat is available.
25. Call `POST /api/connections/{connectionId}/second-chat/join` from both clients and send messages.

Expected frontend behavior:

- First-chat countdown uses `expiresAt` for absolute timeout.
- First-chat inactivity warning/countdown uses `inactivityExpiresAt`.
- Visual warning uses `visualExpiresAt`.
- Scheduling warning uses `schedulingExpiresAt`.
- Second-chat entry is disabled until `availableAt`.
- Frontend never performs lifecycle transitions on its own.

## First Chat Inactivity Abandonment

Purpose: validate the 5-minute inactivity path and `CHAT_ABANDONED`.

Recommended because the wait is short enough to test naturally.

1. Create a fresh match and first chat.
2. Do not send messages.
3. Keep the chat screen open on both clients.
4. Wait until after `inactivityExpiresAt`.
5. Try sending a message from one client.
6. Confirm the backend rejects with `CHAT_ABANDONED`.
7. Refresh Home on both clients.
8. Confirm the first-chat action disappears.

Job-driven variant:

1. Create another fresh match and first chat.
2. Wait until after `inactivityExpiresAt`.
3. Run `POST /api/local-dev/jobs/inactivity-check/run`.
4. Refresh Home on both clients.
5. Confirm the first chat is abandoned/closed from the user perspective and no
   first-chat action remains.

Expected frontend behavior:

- Show an inactivity countdown/warning.
- Disable send locally when the countdown reaches zero.
- If a send races the backend, map `CHAT_ABANDONED` to the inactivity-closed
  message and navigate/refresh.

## First Chat Absolute Timeout

Purpose: validate the 15-minute hard first-chat cap and `CHAT_EXPIRED`.

Use local tooling instead of waiting, because inactivity would usually close the
chat first.

1. Create a fresh match and first chat.
2. Send a message if needed to confirm normal chat UI works.
3. Run `POST /api/local-dev/timeouts/chats/{chatId}/expire-now`.
4. Try sending another message.
5. Confirm the backend rejects with `CHAT_EXPIRED`.
6. Run `POST /api/local-dev/jobs/chat-timeout/run`.
7. Refresh Home on both clients.
8. Confirm the first-chat action disappears.

Expected frontend behavior:

- Show absolute first-chat countdown from `expiresAt`.
- Disable send/decision when the countdown reaches zero.
- Map `CHAT_EXPIRED` to the absolute-expired message.

## Visual Phase Expiration

Purpose: validate visual-review deadline handling and
`VISUAL_REVIEW_EXPIRED`.

Use local tooling because the configured visual phase is long.

1. Move a fresh match to visual review.
2. Confirm visual screen receives `visualExpiresAt`.
3. Run `POST /api/local-dev/timeouts/matches/{matchId}/visual-expire-now`.
4. Try submitting a visual decision from one client.
5. Confirm the backend rejects with `VISUAL_REVIEW_EXPIRED`.
6. Run `POST /api/local-dev/jobs/visual-phase-expiration/run`.
7. Refresh Home on both clients.
8. Confirm visual-review actions disappear.

Expected frontend behavior:

- Show visual-phase warning/countdown from `visualExpiresAt`.
- Disable visual decision after the deadline.
- Refresh/navigate away if the backend returns `VISUAL_REVIEW_EXPIRED`.

## Visual Review Reminder

Purpose: validate the backend-generated visual-review reminder push and
deduplication.

The immediate visual-review availability push has been removed. Reminder
eligibility is persisted as `VisualReview.reminderEligibleAt` when the visual
review is created. With the default configuration, the reminder becomes eligible
when 40% of the visual-review duration remains. The job runs every 30 minutes,
so delivery is approximate. Legacy rows with `reminderEligibleAt = null` are
ignored unless manually backfilled outside Flyway.

1. Move a fresh match to visual review.
2. Confirm both Android clients have registered FCM tokens.
3. Wait until `reminderEligibleAt` is due, or in local-only manual testing update
   that column for the match to a past timestamp.
4. Run `POST /api/local-dev/jobs/visual-review-reminder/run`.
5. Confirm only users whose own visual decision is still pending receive a push.
6. Run the reminder job again.
7. Confirm duplicate pushes are not sent for the same user and match.

Expected frontend behavior:

- Notification tap should navigate using normal Home/state refresh.
- Do not infer partner decision state from reminder presence or absence.
- Do not rely on an in-app notification inbox or unread counter.

## Scheduling Activation

Purpose: validate the pending-to-actionable scheduling transition.

Use local tooling unless you want to wait for the configured activation delay.

1. Move a match through mutual visual approval.
2. Confirm Home shows scheduling as preparing/pending, not actionable.
3. Run `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-available-now`.
4. Run `POST /api/local-dev/jobs/scheduling-activation/run`.
5. Refresh Home on both clients.
6. Confirm scheduling becomes actionable.
7. Open scheduling and confirm `schedulingExpiresAt` is visible in the response.
8. Run the activation job again.
9. Confirm no duplicate negotiation appears and UI remains stable.

Expected frontend behavior:

- Show pending/preparing state until Home exposes actionable scheduling.
- Do not call proposal endpoints while the connection is still preparing.

## Scheduling Negotiation Timeout

Purpose: validate scheduling expiration, `SCHEDULING_EXPIRED`, and connection
cleanup.

Use local tooling because scheduling lasts much longer than a manual test
session.

1. Move a connection into actionable scheduling.
2. Open scheduling on both clients and confirm `schedulingExpiresAt`.
3. Run `POST /api/local-dev/timeouts/connections/{connectionId}/scheduling-expire-now`.
4. Try submitting, accepting or rejecting a proposal from Android.
5. Confirm the backend rejects with `SCHEDULING_EXPIRED`.
6. Run `POST /api/local-dev/jobs/scheduling-timeout/run`.
7. Refresh Home on both clients.
8. Confirm scheduling disappears and no second-chat action appears.

Expected frontend behavior:

- Show scheduling countdown from `schedulingExpiresAt`.
- Disable scheduling actions after the deadline.
- Map `SCHEDULING_EXPIRED` to a timeout state and refresh Home.

## Second Chat Reminder

Purpose: validate the reminder job uses existing FCM tokens and does not create
an in-app notification.

This is easiest to test naturally when the confirmed `availableAt` is inside a
configured reminder window. With the default `minutes-before = 10`, the job sends
when the 10-minute-before reminder instant is due. If the confirmed second-chat
time is much farther away, running the job should do nothing.

1. Confirm both Android clients have registered FCM tokens.
2. Confirm a second-chat schedule.
3. If the agreed `availableAt` is close enough to the configured reminder
   window, run `POST /api/local-dev/jobs/second-chat-reminder/run`.
4. Confirm both devices receive a push notification if their reminder window is
   due.
5. Run the reminder job again.
6. Confirm duplicate pushes are not sent for the same user, connection and lead
   time.

Expected frontend behavior:

- Notification tap should navigate using normal Home/state refresh.
- Do not rely on an in-app notification inbox or unread counter.
- Do not expect a reminder once the connection reaches `SECOND_CHAT_AVAILABLE`.

## Scheduled Second Chat No-Show

Purpose: validate explicit attendance, manual no-show claims and hard cutoff
resolution.

1. Confirm a second-chat schedule.
2. Run `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-late-window-now`.
3. Join with one participant and inspect `GET /api/connections/{connectionId}/second-chat/status`.
4. Create `POST /api/connections/{connectionId}/second-chat/no-show-claims`.
5. Either join with the partner before the returned `expiresAt` and confirm the claim is `CANCELLED`, or run the lifecycle job after `expiresAt` and confirm the absent partner is `NO_SHOW`.
6. For both-absent behavior, use `second-chat-past-hard-cutoff`, do not join either participant, run the lifecycle job, and confirm both are `NO_SHOW`, the connection is `CLOSED`, and no empty chat exists.

## Active Second Chat Conversation Lifecycle

Purpose: validate mutual completion, partner inactivity and initial silence.

1. Confirm a second-chat schedule and make it available.
2. Join both participants; `conversationStartedAt` should be set after the second join.
3. Send at least one message from each participant, force `second-chat-conversation-started-past`, create `POST /api/connections/{connectionId}/second-chat/completion-requests`, and accept before expiry to confirm `FINISHED / SECOND_CHAT_MUTUAL_COMPLETION`.
4. Repeat with rejection, timeout and message cancellation to confirm the requester-only 60-second cooldown.
5. Send one latest message, force `latest-message-claimable`, create `POST /api/connections/{connectionId}/second-chat/inactivity-claims`, then either send before expiry to cancel or force request expiry and run the lifecycle job to confirm `ABANDONED / SECOND_CHAT_PARTNER_INACTIVITY`. Also test a waiting message sent before the partner joins with `latest-message-before-conversation-started`; the five- and ten-minute deadlines should be calculated from `conversationStartedAt`.
6. With both joined and no messages, force `second-chat-conversation-started-past`, run the lifecycle job and confirm `ABANDONED / SECOND_CHAT_NO_CONVERSATION_STARTED` with both-user reliability events.

## Active Second Chat Timeout And Read-Only Cleanup

Purpose: validate second-chat write timeout, read-only mode and final cleanup.

Use local tooling.

1. Confirm a second-chat schedule.
2. Run `POST /api/local-dev/timeouts/connections/{connectionId}/second-chat-available-now`.
3. Run `POST /api/local-dev/jobs/second-chat-lifecycle/run`.
4. Call `POST /api/connections/{connectionId}/second-chat/join` from both clients so the chat becomes active.
5. Send messages from both clients.
6. Run `POST /api/local-dev/timeouts/chats/{chatId}/expire-now`.
7. Run `POST /api/local-dev/jobs/second-chat-lifecycle/run`.
8. Refresh both clients.
9. Confirm messages are still readable and sending a new message fails.
10. Confirm Home shows the second chat as read-only if it is still within
    retention.
11. Run `POST /api/local-dev/timeouts/chats/{chatId}/read-only-expire-now`.
12. Run `POST /api/local-dev/jobs/second-chat-lifecycle/run`.
13. Refresh Home and confirm the second chat disappears.

Expected frontend behavior:

- Before `expiresAt`, second chat is writable.
- After `expiresAt`, show read-only state and block sending.
- After read-only retention, navigate away/refresh because the interaction is
  closed.

## Match Expiration Fallback

Purpose: validate the fallback job does not leave stale first-chat matches
forever if primary chat timeout handling missed them.

This is mostly backend safety-net coverage. Use it after the first-chat timeout
test if a stale match remains unexpectedly.

1. Create a first-chat match.
2. Force the first-chat deadline into the past.
3. Run `POST /api/local-dev/jobs/match-expiration/run`.
4. Refresh Home on both clients.
5. Confirm no stale first-chat action remains.

Expected frontend behavior:

- No special UI. Home should simply stop returning the stale action.
