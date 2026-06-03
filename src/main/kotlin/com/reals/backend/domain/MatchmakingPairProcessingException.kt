package com.reals.backend.domain

import java.util.UUID

class MatchmakingPairProcessingException(
    val userAId: UUID,
    val userBId: UUID,
    cause: RuntimeException
) : RuntimeException(cause)
