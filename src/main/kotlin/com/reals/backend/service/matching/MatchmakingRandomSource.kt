package com.reals.backend.service.matching

import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

fun interface MatchmakingRandomSource {
    fun nextUnitDouble(): Double
}

@Component
class ThreadLocalMatchmakingRandomSource : MatchmakingRandomSource {
    override fun nextUnitDouble(): Double =
        ThreadLocalRandom.current().nextDouble()
}
