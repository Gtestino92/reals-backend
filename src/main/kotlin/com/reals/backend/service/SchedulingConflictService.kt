package com.reals.backend.service

import com.reals.backend.domain.ConnectionState
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class SchedulingConflictService(
    private val negotiationRepository: ScheduleNegotiationRepository,
    private val userRepository: UserRepository,
    @param:Value("\${scheduling.second-chat-conflict-window-minutes:60}")
    val conflictWindowMinutes: Long
) {

    init {
        require(conflictWindowMinutes >= 0) {
            "scheduling.second-chat-conflict-window-minutes must be non-negative"
        }
    }

    @Transactional(readOnly = true)
    fun availabilityFor(
        userId: UUID,
        excludedConnectionId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): SchedulingAvailabilitySnapshot {
        val windows =
            confirmedDateTimesForUsers(
                userIds = setOf(userId),
                excludedConnectionId = excludedConnectionId
            )
                .map { unavailableWindowFor(it) }
                .filter { !it.endsAt.toInstant().isBefore(now.toInstant()) }
                .sortedBy { it.startsAt.toInstant() }

        return SchedulingAvailabilitySnapshot(
            conflictWindowMinutes = conflictWindowMinutes,
            unavailableWindows = windows,
            serverTime = now
        )
    }

    @Transactional(readOnly = true)
    fun requireSlotsAvailableForUser(
        userId: UUID,
        excludedConnectionId: UUID,
        candidateDateTimes: Collection<OffsetDateTime>
    ) {
        val confirmedDateTimes =
            confirmedDateTimesForUsers(
                userIds = setOf(userId),
                excludedConnectionId = excludedConnectionId
            )

        if (candidateDateTimes.any { candidate -> confirmedDateTimes.any { confirmed -> conflicts(candidate, confirmed) } }) {
            throw slotConflict()
        }
    }

    @Transactional
    fun requireSlotAvailableForUsers(
        userIds: Collection<UUID>,
        excludedConnectionId: UUID,
        candidateDateTime: OffsetDateTime
    ) {
        selectFirstAvailableSlotForUsers(
            userIds = userIds,
            excludedConnectionId = excludedConnectionId,
            candidateDateTimes = listOf(candidateDateTime)
        )
    }

    @Transactional
    fun selectFirstAvailableSlotForUsers(
        userIds: Collection<UUID>,
        excludedConnectionId: UUID,
        candidateDateTimes: Collection<OffsetDateTime>
    ): OffsetDateTime {
        lockUsers(userIds)

        val confirmedDateTimes =
            confirmedDateTimesForUsers(
                userIds = userIds.toSet(),
                excludedConnectionId = excludedConnectionId
            )

        return candidateDateTimes.firstOrNull { candidate ->
            confirmedDateTimes.none { confirmed -> conflicts(candidate, confirmed) }
        } ?: throw slotConflict()
    }

    private fun lockUsers(userIds: Collection<UUID>) {
        val orderedUserIds = userIds.distinct().sortedBy(UUID::toString)
        val lockedUsers = userRepository.findAllByIdForUpdate(orderedUserIds)
        check(lockedUsers.size == orderedUserIds.size) {
            "Cannot lock all scheduling participants"
        }
    }

    private fun confirmedDateTimesForUsers(
        userIds: Set<UUID>,
        excludedConnectionId: UUID
    ): List<OffsetDateTime> {
        if (userIds.isEmpty()) {
            return emptyList()
        }

        return negotiationRepository.findConfirmedReservedSecondChatSlotsForUsers(
            userIds = userIds,
            excludedConnectionId = excludedConnectionId,
            states = reservedStates
        )
            .asSequence()
            .mapNotNull { it.confirmedDateTime }
            .distinctBy { it.toInstant() }
            .sortedBy { it.toInstant() }
            .toList()
    }

    private fun conflicts(
        candidateDateTime: OffsetDateTime,
        confirmedDateTime: OffsetDateTime
    ): Boolean {
        val candidate = candidateDateTime.toInstant()
        val startsAt = confirmedDateTime.minusMinutes(conflictWindowMinutes).toInstant()
        val endsAt = confirmedDateTime.plusMinutes(conflictWindowMinutes).toInstant()
        return !candidate.isBefore(startsAt) && !candidate.isAfter(endsAt)
    }

    private fun unavailableWindowFor(confirmedDateTime: OffsetDateTime): SchedulingUnavailableWindow =
        SchedulingUnavailableWindow(
            startsAt = confirmedDateTime.minusMinutes(conflictWindowMinutes),
            endsAt = confirmedDateTime.plusMinutes(conflictWindowMinutes)
        )

    private fun slotConflict(): DomainConflictException =
        DomainConflictException(
            code = DomainErrorCode.SCHEDULING_SLOT_CONFLICT,
            message = "The selected time conflicts with another confirmed second chat"
        )

    data class SchedulingAvailabilitySnapshot(
        val conflictWindowMinutes: Long,
        val unavailableWindows: List<SchedulingUnavailableWindow>,
        val serverTime: OffsetDateTime
    )

    data class SchedulingUnavailableWindow(
        val startsAt: OffsetDateTime,
        val endsAt: OffsetDateTime
    )

    private companion object {
        val reservedStates = listOf(
            ConnectionState.SECOND_CHAT_SCHEDULED,
            ConnectionState.SECOND_CHAT_AVAILABLE
        )
    }
}
