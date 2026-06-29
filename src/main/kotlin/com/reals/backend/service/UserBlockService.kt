package com.reals.backend.service

import com.reals.backend.domain.UserBlock
import com.reals.backend.domain.UserBlockSource
import com.reals.backend.repository.UserBlockRepository
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainErrorCode
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Transactional
class UserBlockService(
    private val userBlockRepository: UserBlockRepository
) {

    fun blockUser(
        blockerUserId: UUID,
        blockedUserId: UUID,
        source: UserBlockSource,
        sourceReportId: UUID? = null
    ): UserBlock {
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

        userBlockRepository.findByBlockerUserIdAndBlockedUserId(
            blockerUserId = blockerUserId,
            blockedUserId = blockedUserId
        )?.let { return it }

        return try {
            userBlockRepository.saveAndFlush(
                UserBlock(
                    blockerUserId = blockerUserId,
                    blockedUserId = blockedUserId,
                    source = source,
                    sourceReportId = sourceReportId
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            userBlockRepository.findByBlockerUserIdAndBlockedUserId(
                blockerUserId = blockerUserId,
                blockedUserId = blockedUserId
            ) ?: throw ex
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
}
