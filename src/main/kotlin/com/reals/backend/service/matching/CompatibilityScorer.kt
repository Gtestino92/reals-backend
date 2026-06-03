package com.reals.backend.service.matching

import com.reals.backend.domain.Profile

interface CompatibilityScorer {
    fun score(
        profileA: Profile,
        profileB: Profile
    ): Double
}
