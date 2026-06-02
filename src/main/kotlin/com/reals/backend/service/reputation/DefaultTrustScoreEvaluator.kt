package com.reals.backend.service.reputation

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Neutral stub: always returns TrustScore.NEUTRAL (1.0).
 * Behaviour is identical to the pre-reputation system: no penalty scaling applied.
 * See docs/technical-debt.md for pending production reputation decisions.
 */
@Component
class DefaultTrustScoreEvaluator : TrustScoreEvaluator {

    override fun evaluate(userId: UUID): TrustScore =
        TrustScore.NEUTRAL
}
