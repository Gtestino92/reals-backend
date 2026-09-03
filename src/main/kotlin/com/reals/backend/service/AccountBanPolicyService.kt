package com.reals.backend.service

import com.reals.backend.config.AccountBanProperties
import com.reals.backend.domain.PenaltyType
import com.reals.backend.repository.PenaltyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AccountBanPolicyService(
    private val penaltyRepository: PenaltyRepository,
    private val accountBanProperties: AccountBanProperties,
    @param:Value("\${chat.second-chat.entry-window-minutes:20}")
    private val secondChatEntryWindowMinutes: Long
) {

    init {
        require(secondChatEntryWindowMinutes > 0) {
            "chat.second-chat.entry-window-minutes must be greater than 0"
        }
    }

    val temporaryResumeMargin: Duration
        get() = Duration.ofMinutes(accountBanProperties.temporaryResumeMarginMinutes)

    @Transactional(readOnly = true)
    fun resolveEffectiveBan(
        userId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): EffectiveAccountBan? {
        val effectiveBans = penaltyRepository.findEffectiveBans(
            userId = userId,
            now = now
        )

        if (effectiveBans.any { it.type == PenaltyType.PERMANENT_BAN }) {
            return EffectiveAccountBan(
                type = PenaltyType.PERMANENT_BAN,
                expiresAt = null
            )
        }

        val latestTemporaryExpiry =
            effectiveBans
                .asSequence()
                .filter { it.type == PenaltyType.TEMPORARY_BAN }
                .mapNotNull { it.expiresAt }
                .maxOrNull()

        return latestTemporaryExpiry?.let {
            EffectiveAccountBan(
                type = PenaltyType.TEMPORARY_BAN,
                expiresAt = it
            )
        }
    }

    fun isTemporaryBanDeadlineResumable(
        effectiveBanExpiresAt: OffsetDateTime,
        deadline: OffsetDateTime
    ): Boolean =
        !effectiveBanExpiresAt.plus(temporaryResumeMargin).isAfter(deadline)

    fun secondChatEntryClosesAt(confirmedDateTime: OffsetDateTime): OffsetDateTime =
        confirmedDateTime.plusMinutes(secondChatEntryWindowMinutes)

    @Transactional(readOnly = true)
    fun canConfirmSecondChatSlotForUsers(
        userIds: Collection<UUID>,
        confirmedDateTime: OffsetDateTime,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Boolean {
        val entryClosesAt = secondChatEntryClosesAt(confirmedDateTime)
        return userIds.distinct().all { userId ->
            when (val ban = resolveEffectiveBan(userId = userId, now = now)) {
                null -> true
                else ->
                    when (ban.type) {
                        PenaltyType.PERMANENT_BAN -> false
                        PenaltyType.TEMPORARY_BAN ->
                            ban.expiresAt?.let {
                                isTemporaryBanDeadlineResumable(
                                    effectiveBanExpiresAt = it,
                                    deadline = entryClosesAt
                                )
                            } == true
                    }
            }
        }
    }
}
