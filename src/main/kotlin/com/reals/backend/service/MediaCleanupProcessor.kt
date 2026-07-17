package com.reals.backend.service

import com.reals.backend.domain.PhotoStorageProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.UUID

enum class MediaCleanupProcessResult {
    SUCCEEDED,
    SKIPPED,
    FAILED
}

@Service
class MediaCleanupProcessor(
    private val taskService: MediaCleanupTaskService,
    private val storageService: S3StorageService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processTask(
        taskId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupProcessResult {
        val command = taskService.claimTask(taskId = taskId, now = now)
            ?: return MediaCleanupProcessResult.SKIPPED

        return try {
            check(!TransactionSynchronizationManager.isActualTransactionActive()) {
                "Object storage deletion must not run inside an active database transaction"
            }

            when (command.storageProvider) {
                PhotoStorageProvider.S3 -> storageService.deleteObject(
                    bucket = command.bucket,
                    key = command.objectKey
                )
            }
            taskService.completeTask(command.id)
            MediaCleanupProcessResult.SUCCEEDED
        } catch (ex: Exception) {
            log.warn("MediaCleanupProcessor - failed to delete object for task={}", command.id, ex)
            taskService.failTask(
                taskId = command.id,
                failure = ex,
                now = now
            )
            MediaCleanupProcessResult.FAILED
        }
    }
}
