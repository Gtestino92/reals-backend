package com.reals.backend.service.contacts

import com.reals.backend.config.*
import com.reals.backend.domain.Match
import com.reals.backend.domain.MatchmakingPartnerCandidate
import com.reals.backend.repository.SharedContactRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

enum class SharedContactRange {
    ONE_TO_FIVE, SIX_TO_TEN, MORE_THAN_TEN;
    companion object {
        fun forCount(n: Int) = when {
            n <= 0 -> null
            n <= 5 -> ONE_TO_FIVE
            n <= 10 -> SIX_TO_TEN
            else -> MORE_THAN_TEN
        }
    }
}

@Service
class MatchContactService(
    private val settings: SharedContactsProperties,
    private val ranking: MatchmakingRankingProperties,
    private val repository: SharedContactRepository,
    private val entities: EntityManager,
    private val metrics: MeterRegistry
) {
    fun rankingWeights(candidates: List<MatchmakingPartnerCandidate>): Map<UUID, Double> {
        val social = ranking.social
        if (!settings.enabled || social.mode == SocialRankingMode.OFF || candidates.isEmpty()) return emptyMap()
        val ids = candidates.map { it.pair.userBId }
        val overlaps = metrics.timer("matchmaking.social.overlap.duration", "mode", social.mode.name).recordCallable {
            repository.overlaps(candidates.first().pair.userAId, ids, settings.keyVersion, settings.consentVersion)
        }!!
        val distribution = metrics.summary("matchmaking.social.log.weight", "mode", social.mode.name)
        val weights = ids.associateWith { id -> social.weight(overlaps[id]?.count ?: 0).also(distribution::record) }
        return if (social.mode == SocialRankingMode.ACTIVE) weights else emptyMap()
    }

    // Called only after final admission, inside createMatch's transaction with both owners locked.
    fun capture(match: Match) {
        if (!settings.enabled || !settings.disclosureEnabled) return
        val overlap = repository.overlaps(match.userAId, listOf(match.userBId), settings.keyVersion, settings.consentVersion)[match.userBId] ?: return
        val bucket = SharedContactRange.forCount(overlap.count) ?: return
        entities.flush() // JDBC snapshot references the JPA-created match.
        repository.snapshot(match.id, bucket.name, overlap)
    }

    fun range(matchId: UUID, viewer: UUID, inactivityExpiry: OffsetDateTime?): SharedContactRange? {
        val now = OffsetDateTime.now()
        if (!settings.enabled || !settings.disclosureEnabled || inactivityExpiry?.let { !now.isBefore(it) } == true) return null
        return repository.visibleRange(matchId, viewer, settings.keyVersion, settings.consentVersion, now)?.let(SharedContactRange::valueOf)
    }
}
