package com.reals.backend.service

import com.reals.backend.config.s3.MediaCleanupProperties
import com.reals.backend.domain.MediaCleanupOperation
import com.reals.backend.domain.MediaCleanupTask
import com.reals.backend.domain.MediaCleanupTaskStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.StoredObject
import com.reals.backend.repository.MediaCleanupTaskRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.min

data class ClaimedMediaCleanupTask(
    val id: UUID,
    val storageProvider: PhotoStorageProvider,
    val bucket: String,
    val objectKey: String
)

@Service
class MediaCleanupTaskService(
    private val repository: MediaCleanupTaskRepository,
    private val properties: MediaCleanupProperties,
    private val transactionTemplate: TransactionTemplate
) {

    fun createGuardTask(
        storageProvider: PhotoStorageProvider,
        bucket: String,
        objectKey: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupTask =
        createDeleteTask(
            storageProvider = storageProvider,
            bucket = bucket,
            objectKey = objectKey,
            nextAttemptAt = now.plus(properties.guardDelay),
            now = now
        )

    fun createImmediateDeleteTask(
        storedObject: StoredObject,
        storageProvider: PhotoStorageProvider = PhotoStorageProvider.S3,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupTask =
        createDeleteTask(
            storageProvider = storageProvider,
            bucket = storedObject.bucket,
            objectKey = storedObject.key,
            nextAttemptAt = now,
            now = now
        )

    fun createImmediateDeleteTaskInNewTransaction(
        storedObject: StoredObject,
        storageProvider: PhotoStorageProvider = PhotoStorageProvider.S3,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupTask =
        createDeleteTask(
            storageProvider = storageProvider,
            bucket = storedObject.bucket,
            objectKey = storedObject.key,
            nextAttemptAt = now,
            now = now
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun createImmediateDeleteTaskInCurrentTransaction(
        storedObject: StoredObject,
        storageProvider: PhotoStorageProvider = PhotoStorageProvider.S3,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupTask {
        val existing = repository.findByOperationAndStorageProviderAndBucketAndObjectKey(
            operation = MediaCleanupOperation.DELETE_OBJECT,
            storageProvider = storageProvider,
            bucket = storedObject.bucket,
            objectKey = storedObject.key
        )
        if (existing != null) {
            return existing
        }

        return repository.saveAndFlush(
            MediaCleanupTask(
                operation = MediaCleanupOperation.DELETE_OBJECT,
                status = MediaCleanupTaskStatus.PENDING,
                storageProvider = storageProvider,
                bucket = storedObject.bucket,
                objectKey = storedObject.key,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteTaskInCurrentTransaction(taskId: UUID) {
        repository.deleteById(taskId)
        repository.flush()
    }

    fun claimTask(
        taskId: UUID,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ClaimedMediaCleanupTask? =
        transactionTemplate.execute {
            val task = repository.findByIdForUpdate(taskId) ?: return@execute null
            if (!task.isEligibleAt(now)) {
                return@execute null
            }

            task.status = MediaCleanupTaskStatus.PROCESSING
            task.leaseUntil = now.plus(properties.leaseDuration)
            task.updatedAt = now
            val saved = repository.save(task)
            ClaimedMediaCleanupTask(
                id = saved.id,
                storageProvider = saved.storageProvider,
                bucket = saved.bucket,
                objectKey = saved.objectKey
            )
        }

    fun completeTask(taskId: UUID) {
        transactionTemplate.executeWithoutResult {
            if (repository.existsById(taskId)) {
                repository.deleteById(taskId)
                repository.flush()
            }
        }
    }

    fun failTask(
        taskId: UUID,
        failure: Throwable,
        now: OffsetDateTime = OffsetDateTime.now()
    ) {
        transactionTemplate.executeWithoutResult {
            val task = repository.findByIdForUpdate(taskId) ?: return@executeWithoutResult
            val nextAttemptCount = task.attemptCount + 1
            task.attemptCount = nextAttemptCount
            task.leaseUntil = null
            task.lastError = sanitizeError(failure)
            task.updatedAt = now

            if (nextAttemptCount >= properties.maxAttempts) {
                task.status = MediaCleanupTaskStatus.FAILED
                task.nextAttemptAt = now
            } else {
                task.status = MediaCleanupTaskStatus.PENDING
                task.nextAttemptAt = now.plus(retryDelay(nextAttemptCount))
            }

            repository.save(task)
        }
    }

    private fun createDeleteTask(
        storageProvider: PhotoStorageProvider,
        bucket: String,
        objectKey: String,
        nextAttemptAt: OffsetDateTime,
        now: OffsetDateTime
    ): MediaCleanupTask {
        return try {
            createDeleteTaskAttempt(storageProvider, bucket, objectKey, nextAttemptAt, now)
        } catch (ex: DataIntegrityViolationException) {
            findDeleteTask(storageProvider, bucket, objectKey) ?: throw ex
        }
    }

    private fun createDeleteTaskAttempt(
        storageProvider: PhotoStorageProvider,
        bucket: String,
        objectKey: String,
        nextAttemptAt: OffsetDateTime,
        now: OffsetDateTime
    ): MediaCleanupTask {
        return transactionTemplate.execute {
            val existing = repository.findByOperationAndStorageProviderAndBucketAndObjectKey(
                operation = MediaCleanupOperation.DELETE_OBJECT,
                storageProvider = storageProvider,
                bucket = bucket,
                objectKey = objectKey
            )
            if (existing != null) {
                return@execute existing
            }

            repository.saveAndFlush(
                MediaCleanupTask(
                    operation = MediaCleanupOperation.DELETE_OBJECT,
                    status = MediaCleanupTaskStatus.PENDING,
                    storageProvider = storageProvider,
                    bucket = bucket,
                    objectKey = objectKey,
                    nextAttemptAt = nextAttemptAt,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun findDeleteTask(
        storageProvider: PhotoStorageProvider,
        bucket: String,
        objectKey: String
    ): MediaCleanupTask? =
        transactionTemplate.execute {
            repository.findByOperationAndStorageProviderAndBucketAndObjectKey(
                operation = MediaCleanupOperation.DELETE_OBJECT,
                storageProvider = storageProvider,
                bucket = bucket,
                objectKey = objectKey
            )
        }

    private fun MediaCleanupTask.isEligibleAt(now: OffsetDateTime): Boolean =
        when (status) {
            MediaCleanupTaskStatus.PENDING -> !nextAttemptAt.isAfter(now)
            MediaCleanupTaskStatus.PROCESSING -> leaseUntil?.let { !it.isAfter(now) } == true
            MediaCleanupTaskStatus.FAILED -> false
        }

    private fun retryDelay(attemptCount: Int): java.time.Duration {
        val multiplier = 1L shl min(attemptCount - 1, 20)
        val candidateSeconds = properties.initialRetryDelay.seconds * multiplier
        val boundedSeconds = min(candidateSeconds, properties.maxRetryDelay.seconds)
        return java.time.Duration.ofSeconds(boundedSeconds)
    }

    private fun sanitizeError(failure: Throwable): String {
        val raw = listOfNotNull(
            failure::class.simpleName,
            failure.message?.replace(Regex("\\s+"), " ")?.trim()
        ).joinToString(": ")
        return raw.take(500)
    }
}
