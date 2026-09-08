package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.UserBlock
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Transactional
class UserBlockService(
    private val userBlockRepository: UserBlockRepository,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService
) {

    fun blockUser(
        blockerUserId: UUID,
        blockedUserId: UUID,
        source: UserBlockSource,
        sourceReportId: UUID? = null
    ): UserBlock {
        return blockUserWithResult(blockerUserId, blockedUserId, source, sourceReportId).block
    }

    fun blockUserWithResult(
        blockerUserId: UUID,
        blockedUserId: UUID,
        source: UserBlockSource,
        sourceReportId: UUID? = null
    ): UserBlockCreationResult {
        if (blockerUserId == blockedUserId) {
            throw DomainBadRequestException(
                code = DomainErrorCode.USER_BLOCK_SELF_NOT_ALLOWED,
                message = "A user cannot block themselves"
            )
        }

        if (source == UserBlockSource.SAFETY_REPORT) {
            require(sourceReportId != null) {
                "sourceReportId is required for safety-report blocks"
            }
        }

        val orderedIds = listOf(blockerUserId, blockedUserId).sortedBy(UUID::toString)
        check(userRepository.findAllByIdForUpdate(orderedIds).size == 2) {
            "Cannot block user pair: one or more users were not found"
        }

        userBlockRepository.findByBlockerUserIdAndBlockedUserId(
            blockerUserId = blockerUserId,
            blockedUserId = blockedUserId
        )?.let { return UserBlockCreationResult(it, created = false) }

        return try {
            val block = userBlockRepository.saveAndFlush(
                UserBlock(
                    blockerUserId = blockerUserId,
                    blockedUserId = blockedUserId,
                    source = source,
                    sourceReportId = sourceReportId
                )
            )
            auditEventService.record(
                eventType = AuditEventType.USER_BLOCK_CREATED,
                aggregateType = AuditAggregateType.USER_BLOCK,
                aggregateId = block.id,
                actorUserId = blockerUserId,
                targetUserId = blockedUserId,
                metadata = mapOf(
                    "source" to source.name,
                    "sourceReportId" to sourceReportId
                )
            )
            homeStateInvalidationService.bumpBoth(
                userAId = blockerUserId,
                userBId = blockedUserId,
                reason = "user_block_created"
            )
            UserBlockCreationResult(block, created = true)
        } catch (ex: DataIntegrityViolationException) {
            val existing = userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                blockerUserId = blockerUserId,
                blockedUserId = blockedUserId
            ) ?: throw ex
            UserBlockCreationResult(existing, created = false)
        }
    }

    fun isBlockedPair(userAId: UUID, userBId: UUID): Boolean {
        if (userAId == userBId) {
            return false
        }

        return userBlockRepository.existsBetweenUsers(
            userAId = userAId,
            userBId = userBId
        )
    }

    fun requirePairNotBlocked(userAId: UUID, userBId: UUID) {
        if (isBlockedPair(userAId, userBId)) {
            throw DomainConflictException(
                code = DomainErrorCode.USER_PAIR_BLOCKED,
                message = "Interaction is not available for this user pair"
            )
        }
    }

    fun findBlockedCounterpartUserIds(userId: UUID): Set<UUID> =
        userBlockRepository.findByBlockerUserId(userId).mapTo(mutableSetOf()) { it.blockedUserId }
            .apply {
                addAll(userBlockRepository.findByBlockedUserId(userId).map { it.blockerUserId })
            }
}

data class UserBlockCreationResult(
    val block: UserBlock,
    val created: Boolean
)
