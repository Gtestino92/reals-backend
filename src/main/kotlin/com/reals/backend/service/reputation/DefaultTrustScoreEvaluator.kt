package com.reals.backend.service.reputation

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Neutral stub: always returns TrustScore.NEUTRAL (1.0).
 * Behaviour is identical to the pre-reputation system — no penalty scaling applied.
 *
 * TODO(reputation): replace with a real implementation that computes the score from:
 * - PenaltyRepository: count, recency and severity of past penalties.
 * - Abandonment rate derived from chat history.
 * - Positive engagement signals: completed connections, etc.
 *
 * See docs/technical-debt.md for pending reputation decisions.
 */
@Component
class DefaultTrustScoreEvaluator : TrustScoreEvaluator {

    override fun evaluate(userId: UUID): TrustScore =
        TrustScore.NEUTRAL
}
