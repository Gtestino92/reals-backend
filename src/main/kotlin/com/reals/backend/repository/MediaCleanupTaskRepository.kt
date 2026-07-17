package com.reals.backend.repository

import com.reals.backend.domain.MediaCleanupOperation
import com.reals.backend.domain.MediaCleanupTask
import com.reals.backend.domain.MediaCleanupTaskStatus
import com.reals.backend.domain.PhotoStorageProvider
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface MediaCleanupTaskRepository : JpaRepository<MediaCleanupTask, UUID> {

    @Query(
        """
        select t.id
        from MediaCleanupTask t
        where
            (t.status = :pendingStatus and t.nextAttemptAt <= :now)
            or (t.status = :processingStatus and t.leaseUntil is not null and t.leaseUntil <= :now)
        order by t.nextAttemptAt asc, t.createdAt asc, t.id asc
        """
    )
    fun findEligibleTaskIds(
        @Param("now") now: OffsetDateTime,
        @Param("pendingStatus") pendingStatus: MediaCleanupTaskStatus = MediaCleanupTaskStatus.PENDING,
        @Param("processingStatus") processingStatus: MediaCleanupTaskStatus = MediaCleanupTaskStatus.PROCESSING,
        pageable: Pageable
    ): List<UUID>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MediaCleanupTask t where t.id = :taskId")
    fun findByIdForUpdate(
        @Param("taskId") taskId: UUID
    ): MediaCleanupTask?

    fun findByOperationAndStorageProviderAndBucketAndObjectKey(
        operation: MediaCleanupOperation,
        storageProvider: PhotoStorageProvider,
        bucket: String,
        objectKey: String
    ): MediaCleanupTask?
}
