# Affinity Shadow Matchmaking Bruno Flow

Manual local flow for observing private affinity evidence in matchmaking `SHADOW` mode with three existing Firebase users.

## Prerequisites

- Docker backend rebuilt from the current branch.
- `local-firebase` profile active.
- The backend was restarted after Compose environment changes:

```powershell
docker compose up -d --build --force-recreate backend
```

- Health checks pass:

```powershell
curl http://localhost:8080/api/ping
curl http://localhost:8080/actuator/health/readiness
```

- The three Firebase accounts already exist.
- Each account has an existing backend user or can be provisioned.
- All three accounts have `ACTIVE` profiles.
- Profiles are mutually compatible by hard matchmaking filters: gender, age, intention and distance.
- Legal requirements are current for all three users.
- User A is allowlisted locally as admin through `BACKOFFICE_ADMIN_EMAILS`.
- No automatic scheduler is running; keep `scheduler.enabled=false`.
- Select the ignored `bruno/environments/local.affinity-shadow.bru` environment, or copy its affinity variables into your own ignored local environment.
- Local-only passwords stay in ignored Bruno environments. Do not copy secrets to tracked files.

## User Mapping

| Role | Email | Evidence |
| --- | --- | --- |
| User A / anchor | `gtestino1992@gmail.com` | Reference answers |
| User B / aligned candidate | `roger@test.com` | Same answers as A |
| User C / contrasting candidate | `android.counterpart@example.com` | Substantially different answers |

A and B use identical answers across 12 ranking-enabled questions. C uses contrasting answers. The selected questions span four categories: cinema/stories, music, values/shared life, and relationship/communication.

## Execution Order

Do not run the whole folder blindly. Execute this initial three-user sequence manually:

1. `00 Get Affinity Catalog`
2. `10` through `15` for User A
3. `20` through `25` for User B
4. `30` through `35` for User C
5. `40`, `41`, `42` cleanup/dequeue
6. `43 Enqueue User A`
7. `44 Enqueue User C`
8. `45 Enqueue User B`
9. `46`, `47`, `48` queue diagnostics
10. `50 Process One Match`
11. `60` through `65` metrics inspection
12. `90`, `91`, `92` cleanup/dequeue

If `00 Get Affinity Catalog` returns `401` or `403` because `affinity_user_a_id_token` is blank or expired, run `10 Sign In User A`, rerun `00`, then continue from `11`. Do not weaken backend security for this flow.

## Why C Is Enqueued Before B

The flow intentionally queues A, then C, then B. A should become the oldest eligible anchor. C should be the first FIFO partner candidate and B the second. With reliability disabled and current binary compatibility, deterministic baseline weights should tie and FIFO would prefer C. Shadow affinity should prefer B in deterministic diagnostics. Because `SHADOW` does not apply affinity, actual selected partner remains randomized and may be B or C.

Expected diagnostic log characteristics after `50 Process One Match`:

```text
mode=shadow
candidateCount=2
candidatesWithRankingEvidence=2
averageSharedQuestionCount=12
maximumSharedQuestionCount=12
averageEvidenceConfidence=1.0
maximumEvidenceConfidence=1.0
minimumOverallAffinity=<negative>
maximumOverallAffinity=<positive>
maximumAbsoluteRankDelta>=1
deterministicTopCandidateChanged=true
affinityApplied=false
```

Do not hardcode exact overall-affinity decimals; catalog calibration can change them.

## Log Verification

Bruno cannot read Docker logs and this flow does not add a backend log endpoint. Use PowerShell:

```powershell
docker compose logs --since=10m backend |
  Select-String "affinity_matchmaking_window"
```

## Metrics To Inspect

Requests `60` through `65` inspect Actuator metrics:

- `reals.matchmaking.affinity.evaluations`
- `reals.matchmaking.affinity.shared_questions`
- `reals.matchmaking.affinity.evidence_confidence`
- `reals.matchmaking.affinity.factor`
- `reals.matchmaking.affinity.absolute_rank_delta`

Expected bounded tags include `mode=shadow`, `evidence=present`, and both positive and negative `direction` values for this candidate window. Metrics are operational diagnostics, not user-facing compatibility output.

## Cleanup

Run `90`, `91`, and `92` after the experiment. Matched users may already be removed from the queue automatically; idempotent dequeue should still return `inQueue=false`. Do not delete matches, profiles, users, affinity answers or Firebase accounts as part of this flow.

## No-answer Control

This focused control scenario uses one control user with zero affinity answers
as the queue anchor and User B as the only candidate. Do not patch affinity
answers for the control user.

Manual execution order:

```text
70 Sign In Control User
71 Provision Control User
72 Get Control User Profile
73 Get Control User Affinity Answers

20 Sign In User B
25 Get User B Affinity After

74 Dequeue Control User
41 Dequeue User B

75 Enqueue Control User
45 Enqueue User B

76 Get Control User Queue Status
47 Get User B Queue Status

77 Process Control Match

80 through 84 control metrics

89 Cleanup Control User Queue
91 Cleanup User B Queue
```

Expected behavior:

- The control user has zero affinity answers.
- Missing affinity evidence does not block matchmaking.
- The pair is treated neutrally.
- The affinity factor remains neutral.
- Rank delta remains zero.
- Metrics use `evidence=none`.
- An `affinity_matchmaking_window` INFO log may be absent when there are no evidence-bearing candidates.

Optional log check:

```powershell
docker compose logs --since=5m backend |
  Select-String "affinity_matchmaking_window"
```

## Troubleshooting

- `401`: refresh Firebase sign-in for that user; ensure the dedicated `affinity_user_*_id_token` variable is populated.
- `403`: for Actuator metrics or hosted dev tooling, ensure the bearer belongs to an admin-allowlisted user; User A should be locally allowlisted.
- Profile request fails or `status != ACTIVE`: activate the profile before matchmaking. Matchmaking requires active profiles.
- No match created: inspect inactive profiles, gender/age/intention/distance filters, legal requirements, penalties, queue state, and existing active-pair restrictions.
- Metrics absent: confirm Compose is using `MATCHMAKING_RANKING_MODE=PROBABILISTIC_WEIGHTED` and `MATCHMAKING_RANKING_AFFINITY_MODE=SHADOW`, then rebuild/recreate backend.
- Deterministic shadow rank does not move: verify all 12 answers were patched for A/B/C and the catalog request passed drift validation.
- Actual selected partner is B or C: this is expected. `SHADOW` leaves Gumbel random ordering active and unaffected by affinity.
