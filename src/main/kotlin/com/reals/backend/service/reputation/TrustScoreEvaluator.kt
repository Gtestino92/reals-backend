package com.reals.backend.service.reputation

import java.util.UUID

interface TrustScoreEvaluator {
    fun evaluate(userId: UUID): TrustScore
}
