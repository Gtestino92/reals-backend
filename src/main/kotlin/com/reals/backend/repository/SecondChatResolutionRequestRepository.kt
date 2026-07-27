package com.reals.backend.repository

import com.reals.backend.domain.SecondChatResolutionRequest
import com.reals.backend.domain.SecondChatResolutionRequestStatus
import com.reals.backend.domain.SecondChatResolutionRequestType
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface SecondChatResolutionRequestRepository : JpaRepository<SecondChatResolutionRequest, UUID> {

    fun findByConnectionIdAndTypeAndStatus(
        connectionId: UUID,
        type: SecondChatResolutionRequestType,
        status: SecondChatResolutionRequestStatus
    ): SecondChatResolutionRequest?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select r from SecondChatResolutionRequest r
        where r.connectionId = :connectionId
          and r.type = :type
          and r.status = :status
        """
    )
    fun findByConnectionIdAndTypeAndStatusForUpdate(
        @Param("connectionId") connectionId: UUID,
        @Param("type") type: SecondChatResolutionRequestType,
        @Param("status") status: SecondChatResolutionRequestStatus
    ): SecondChatResolutionRequest?

    fun findByConnectionIdAndStatus(
        connectionId: UUID,
        status: SecondChatResolutionRequestStatus
    ): List<SecondChatResolutionRequest>

    fun findByConnectionIdAndStatusOrderByCreatedAtDesc(
        connectionId: UUID,
        status: SecondChatResolutionRequestStatus
    ): List<SecondChatResolutionRequest>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select r from SecondChatResolutionRequest r
        where r.connectionId = :connectionId
          and r.status = :status
        """
    )
    fun findByConnectionIdAndStatusForUpdate(
        @Param("connectionId") connectionId: UUID,
        @Param("status") status: SecondChatResolutionRequestStatus
    ): SecondChatResolutionRequest?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SecondChatResolutionRequest r where r.id = :requestId")
    fun findByIdForUpdate(
        @Param("requestId") requestId: UUID
    ): SecondChatResolutionRequest?

    @Query(
        """
        select r from SecondChatResolutionRequest r
        where r.connectionId = :connectionId
          and r.requesterUserId = :requesterUserId
          and r.type = :type
          and r.status in :statuses
          and r.resolvedAt is not null
        order by r.resolvedAt desc, r.id desc
        """
    )
    fun findLatestResolvedByRequesterAndType(
        @Param("connectionId") connectionId: UUID,
        @Param("requesterUserId") requesterUserId: UUID,
        @Param("type") type: SecondChatResolutionRequestType,
        @Param("statuses") statuses: Collection<SecondChatResolutionRequestStatus>,
        pageable: Pageable
    ): List<SecondChatResolutionRequest>

    @Query(
        """
        select r.id from SecondChatResolutionRequest r
        where r.type = :type
          and r.status = :status
          and r.expiresAt <= :now
        order by r.expiresAt asc, r.id asc
        """
    )
    fun findExpiredPendingPartnerNoShowRequestIds(
        @Param("now") now: OffsetDateTime,
        @Param("type") type: SecondChatResolutionRequestType = SecondChatResolutionRequestType.PARTNER_NO_SHOW,
        @Param("status") status: SecondChatResolutionRequestStatus = SecondChatResolutionRequestStatus.PENDING,
        pageable: Pageable
    ): List<UUID>

    @Query(
        """
        select r.id from SecondChatResolutionRequest r
        where r.type = :type
          and r.status = :status
          and r.expiresAt <= :now
        order by r.expiresAt asc, r.id asc
        """
    )
    fun findExpiredPendingRequestIdsByType(
        @Param("now") now: OffsetDateTime,
        @Param("type") type: SecondChatResolutionRequestType,
        @Param("status") status: SecondChatResolutionRequestStatus = SecondChatResolutionRequestStatus.PENDING,
        pageable: Pageable
    ): List<UUID>
}
