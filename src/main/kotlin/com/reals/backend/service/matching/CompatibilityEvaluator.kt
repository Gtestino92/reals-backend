package com.reals.backend.service.matching

import com.reals.backend.domain.Profile

interface CompatibilityEvaluator {
    fun compatible(profileA: Profile, profileB: Profile): Boolean
}
