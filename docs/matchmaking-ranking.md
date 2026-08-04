# Matchmaking Ranking

This document describes partner ranking after hard matchmaking eligibility has
already run.

## Goals

- Hard eligibility remains deterministic: queue state, active users, active
  profiles, penalties, profile filters, distance, user blocks, active-pair
  policy and historical cooldown policy are resolved before ranking.
- Compatibility quality affects selection probability.
- Reliability similarity affects selection probability using the gap between
  the two users' individual reliability scores.
- Absolute reliability average does not directly affect probabilistic ranking:
  `120/120`, `100/100` and `80/80` have the same reliability-similarity
  component when compatibility and waiting time are equal.
- Waiting gradually relaxes reliability similarity so older partner candidates
  are not permanently disadvantaged by a reliability gap.
- There is no probabilistic `NO_MATCH` result. If at least one valid candidate
  remains after deterministic gates, ranking returns a complete fallback order.
- Every valid candidate remains available for exact queue-row claim fallback.
- Anchor FIFO and the bounded partner window remain fairness controls.

## Non-goals

- No attractiveness or popularity score.
- No Elo competition system.
- No hard reliability tiers.
- No global best-pair search across the whole queue.
- No deterministic segregation by reliability band.
- No automatic rejection solely due to reliability distance.
- No Android/user-facing score exposure in this task.

## Ranking Modes

`matchmaking.ranking.mode` controls the application ranking strategy:

- `LEGACY_EARLY_ACCEPT`: preserves the previous behavior. It combines
  compatibility with the bounded legacy reliability modifier, applies
  `matchmaking.min-compatibility-score` to that combined legacy score, accepts
  FIFO candidates at or above `matchmaking.early-accept-compatibility-score`,
  then tries remaining candidates by score descending with FIFO tie-break.
- `PROBABILISTIC_WEIGHTED`: gates by raw compatibility, calculates pair
  log-weights, adds Gumbel noise and sorts into a complete weighted permutation
  without replacement.

Global, dev and prod defaults remain `LEGACY_EARLY_ACCEPT`. The
`local-firebase` profile defaults to `PROBABILISTIC_WEIGHTED` for manual
experimentation and can be switched with `MATCHMAKING_RANKING_MODE`.

`USER_RELIABILITY_MATCHMAKING_MAX_MODIFIER` only affects
`LEGACY_EARLY_ACCEPT`. Probabilistic ranking uses individual reliability-score
similarity instead.

Minimum-score semantics intentionally differ by mode:

- In `LEGACY_EARLY_ACCEPT`, `matchmaking.min-compatibility-score` applies to
  `compatibilityScore + boundedReliabilityModifier`.
- In `PROBABILISTIC_WEIGHTED`, `matchmaking.min-compatibility-score` applies to
  raw `compatibilityScore`; reliability similarity and Gumbel randomness are
  applied only after that raw compatibility gate passes.

## Formula

For a candidate pair:

```text
c = compatibilityScore(anchor, partner), in [0, 1]
rA = anchor reliability score
rB = partner reliability score
gap = abs(rA - rB)
```

Compatibility contributes:

```text
compatibilityLogWeight =
    (compatibilityScore - 1) / compatibilityTemperature
```

Waiting relaxes the reliability gap penalty:

```text
waitingMultiplier =
    min(
        maximumSimilarityScaleMultiplier,
        1 + waitingHours / waitingRelaxationPeriodHours
    )

effectiveSimilarityScale =
    reliabilitySimilarityScale * waitingMultiplier
```

Reliability similarity contributes:

```text
reliabilityLogWeight =
    -abs(anchorReliability - partnerReliability)
    / effectiveSimilarityScale
```

The final pair log-weight is:

```text
logWeight = compatibilityLogWeight + reliabilityLogWeight
```

Log space avoids underflow when weights become very small and makes the
components additive.

## Affinity Shadow Ranking

`matchmaking.ranking.affinity.mode` controls private affinity evidence:

- `OFF`: does not load affinity answers, evaluate affinity or emit affinity
  metrics/logs. This is the global, dev and prod default.
- `SHADOW`: only in `PROBABILISTIC_WEIGHTED`, batch-loads affinity answers for
  the bounded partner window, evaluates hypothetical factors and records
  privacy-safe logs and metrics. It does not change eligibility, weights,
  random draws, claim order or selected matches.
- `ACTIVE`: only in `PROBABILISTIC_WEIGHTED`, performs the same observation as
  `SHADOW` and adds affinity log-weight to the actual probabilistic weight.
  `ACTIVE` is rejected with `LEGACY_EARLY_ACCEPT`.

`local-firebase` defaults affinity to `SHADOW` because it already defaults
ranking to `PROBABILISTIC_WEIGHTED`. Affinity observation requires
probabilistic ranking mode; under legacy mode shadow affinity remains dormant
and does not query answers. Roll back by setting affinity mode to `OFF`.

Affinity answers are private. Matchmaking does not log, serialize or expose
answer codes, question-answer pairs, user ids, profile ids, match ids,
sensitive-question evidence, category ids or per-user affinity profiles.
Affinity is never a hard eligibility filter: missing answers, no shared
answers, semantic-version mismatches, conversation-only evidence and absent rows
are neutral; negative affinity lowers weight only and never removes a candidate.

Category aggregation uses only shared valid signals whose active catalog
question has `rankingEnabled = true`:

```text
rawCategoryAffinity =
    arithmetic mean(rankingAffinityContribution)

categoryEvidenceConfidence =
    min(1, rankingEligibleSharedQuestionCount / categoryFullConfidenceQuestions)
```

Categories are then confidence-weighted equally at category level:

```text
overallAffinity =
    sum(rawCategoryAffinity * categoryEvidenceConfidence)
    / sum(categoryEvidenceConfidence)
```

No ranking evidence produces `overallAffinity = 0`. The final value is clamped
to `[-1, 1]`, so a category with more questions does not dominate solely by
count.

Global confidence requires both shared-question depth and category breadth:

```text
sharedQuestionConfidence =
    min(1, rankingEligibleSharedQuestionCount / fullConfidenceSharedQuestions)

categoryBreadthConfidence =
    min(1, categoriesWithRankingEvidence / fullConfidenceCategories)

evidenceConfidence =
    min(sharedQuestionConfidence, categoryBreadthConfidence)
```

The bounded multiplicative factor is:

```text
relativeAdjustment =
    maxRelativeAdjustment * evidenceConfidence * overallAffinity

affinityFactor = 1 + relativeAdjustment
affinityLogWeight = ln(affinityFactor)
```

With defaults, `maxRelativeAdjustment = 0.10`, so `affinityFactor` is bounded to
`[0.90, 1.10]`. In `ACTIVE`:

```text
logWeight =
    compatibilityLogWeight
    + reliabilityLogWeight
    + affinityLogWeight
```

Shadow diagnostics do not compare randomized permutations. They calculate two
deterministic rankings for observation only: baseline `baseLogWeight`
descending then FIFO order, and shadow `baseLogWeight + affinityLogWeight`
descending then FIFO order. These ranks never consume random values and never
affect the actual Gumbel weighted permutation.

## Weighted Permutation

The claim flow needs a full fallback order, not a single random pick.
Probabilistic ranking uses a Gumbel weighted permutation:

```text
priority = logWeight + Gumbel(0,1)
```

Candidates are sorted by priority descending, with original FIFO position as
the final deterministic tie-break. This gives a weighted random ordering
without replacement:

```text
candidate 1
candidate 2
candidate 3
...
```

If the first candidate's exact queue row is already locked, deleted or newly
ineligible, the processor tries the next candidate in the same weighted order.

## Current Compatibility Behavior

`BasicCompatibilityScorer` is currently binary:

```text
compatible -> 1.0
incompatible -> 0.0
```

Because SQL already returns hard-compatible candidates, current valid
production candidates normally have:

```text
compatibilityScore = 1.0
compatibilityLogWeight = 0
```

The compatibility term is intentionally present now so a future gradual scorer
can affect ranking without redesigning the algorithm. With the current binary
scorer, relative probabilistic weights are initially driven by reliability
similarity, waiting relaxation and random Gumbel noise.

When reliability is disabled, probabilistic ranking treats
`reliabilityLogWeight = 0`. With binary compatibility, that means valid
candidate weights are equal and the weighted permutation is uniform within the
FIFO partner window.

## Examples

Reliability gap with scale `10` and no waiting:

| Pair scores | Gap | Reliability log-weight | Relative factor |
| --- | ---: | ---: | ---: |
| `100 / 100` | `0` | `0.0` | `1.000` |
| `100 / 95` | `5` | `-0.5` | `0.607` |
| `100 / 90` | `10` | `-1.0` | `0.368` |
| `100 / 80` | `20` | `-2.0` | `0.135` |

Waiting relaxation for a fixed gap of `20`, base scale `10`, period `72h`,
maximum multiplier `3`:

| Waiting | Multiplier | Effective scale | Reliability log-weight |
| ---: | ---: | ---: | ---: |
| `0h` | `1.0` | `10` | `-2.000` |
| `72h` | `2.0` | `20` | `-1.000` |
| `144h` | `3.0` | `30` | `-0.667` |
| `240h` | `3.0` | `30` | `-0.667` |

Future gradual compatibility can trade off against reliability similarity. With
temperature `0.20`:

| Compatibility | Compatibility log-weight |
| ---: | ---: |
| `1.00` | `0.00` |
| `0.90` | `-0.50` |
| `0.80` | `-1.00` |

Equal gaps receive equal similarity treatment regardless of absolute level:

| Pair scores | Gap | Reliability log-weight |
| --- | ---: | ---: |
| `120 / 120` | `0` | `0.0` |
| `100 / 100` | `0` | `0.0` |
| `80 / 80` | `0` | `0.0` |

## Operational Configuration

Properties:

| Property | Default | Notes |
| --- | ---: | --- |
| `matchmaking.ranking.mode` | `LEGACY_EARLY_ACCEPT` | `local-firebase` defaults to `PROBABILISTIC_WEIGHTED`. |
| `matchmaking.ranking.compatibility-temperature` | `0.20` | Must be finite and greater than `0`. Lower values make compatibility differences stronger. |
| `matchmaking.ranking.reliability-similarity-scale` | `10.0` | Must be finite and greater than `0`. Larger values make reliability gaps less punitive. |
| `matchmaking.ranking.waiting-relaxation-period-hours` | `72.0` | Must be finite and greater than `0`. Controls how quickly waiting relaxes similarity. |
| `matchmaking.ranking.maximum-similarity-scale-multiplier` | `3.0` | Must be finite and at least `1`. Caps waiting relaxation. |
| `matchmaking.ranking.affinity.mode` | `OFF` | `OFF`, `SHADOW` or `ACTIVE`; `local-firebase` defaults to `SHADOW`. |
| `matchmaking.ranking.affinity.max-relative-adjustment` | `0.10` | Must be finite and in `[0.0, 0.25]`. |
| `matchmaking.ranking.affinity.full-confidence-shared-questions` | `12` | Positive integer. |
| `matchmaking.ranking.affinity.full-confidence-categories` | `4` | Positive integer. |
| `matchmaking.ranking.affinity.category-full-confidence-questions` | `3` | Positive integer. |

Environment variables:

```text
MATCHMAKING_RANKING_MODE
MATCHMAKING_RANKING_COMPATIBILITY_TEMPERATURE
MATCHMAKING_RANKING_RELIABILITY_SIMILARITY_SCALE
MATCHMAKING_RANKING_WAITING_RELAXATION_PERIOD_HOURS
MATCHMAKING_RANKING_MAXIMUM_SIMILARITY_SCALE_MULTIPLIER
MATCHMAKING_RANKING_AFFINITY_MODE
MATCHMAKING_RANKING_AFFINITY_MAX_RELATIVE_ADJUSTMENT
MATCHMAKING_RANKING_AFFINITY_FULL_CONFIDENCE_SHARED_QUESTIONS
MATCHMAKING_RANKING_AFFINITY_FULL_CONFIDENCE_CATEGORIES
MATCHMAKING_RANKING_AFFINITY_CATEGORY_FULL_CONFIDENCE_QUESTIONS
```

Examples:

```text
MATCHMAKING_RANKING_MODE=PROBABILISTIC_WEIGHTED
```

```text
MATCHMAKING_RANKING_MODE=LEGACY_EARLY_ACCEPT
```

## Calibration

Initial defaults are hypotheses, not empirically calibrated production truth.
Before production rollout, inspect:

- distribution of effective reliability scores;
- reliability gaps of formed pairs;
- partner waiting time;
- candidate-window size;
- selected candidate's original FIFO rank;
- match creation throughput;
- claim fallback frequency;
- waiting-time percentiles by reliability band;
- evidence of starvation or behavioral stratification.

This task does not implement a metrics platform.

## Functional Scalability Tests

The test suite includes two non-benchmark scalability checks:

- an in-memory large candidate-window service test that ranks hundreds of
  partner candidates deterministically and verifies one batched reliability
  load for the window;
- a PostgreSQL large-queue behavioral test that persists hundreds of queued
  users and verifies bounded partner discovery, queue-row removal and repeated
  processor progress.

These tests prove functional behavior around bounded windows and large queues.
They are not production latency guarantees or load benchmarks.

## User Transparency

Future product work should explain that constructive participation,
responsiveness, scheduling commitment and safety behavior may influence
matching opportunities. Current APIs do not expose exact internal formulas or
user reliability scores.
