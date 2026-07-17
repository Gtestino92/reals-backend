package com.reals.backend.integration.service

import com.reals.backend.domain.MediaCleanupTask
import com.reals.backend.domain.MediaCleanupTaskStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.integration.ControllerIT
import com.reals.backend.repository.MediaCleanupTaskRepository
import com.reals.backend.scheduler.MediaCleanupJob
import com.reals.backend.service.MediaCleanupProcessResult
import com.reals.backend.service.MediaCleanupProcessor
import com.reals.backend.service.MediaCleanupTaskService
import com.reals.backend.service.S3StorageService
import com.reals.backend.service.exception.ObjectStorageException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.UUID

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MediaCleanupTaskIntegrationTest : ControllerIT() {

    @Autowired
    private lateinit var mediaCleanupTaskRepository: MediaCleanupTaskRepository

    @Autowired
    private lateinit var mediaCleanupTaskService: MediaCleanupTaskService

    @Autowired
    private lateinit var mediaCleanupProcessor: MediaCleanupProcessor

    @Autowired
    private lateinit var mediaCleanupJob: MediaCleanupJob

    @MockitoBean
    private lateinit var storageService: S3StorageService

    @BeforeEach
    fun cleanMediaCleanupTasks() {
        mediaCleanupTaskRepository.deleteAll()
        Mockito.reset(storageService)
    }

    @Test
    fun `creating new object guard persists delayed cleanup task`() {
        val now = OffsetDateTime.parse("2026-07-16T12:00:00Z")

        val task = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = "test-bucket",
            objectKey = "guarded-object.jpg",
            now = now
        )

        val saved = mediaCleanupTaskRepository.findById(task.id).orElseThrow()
        assertEquals(MediaCleanupTaskStatus.PENDING, saved.status)
        assertEquals(now.plusMinutes(30), saved.nextAttemptAt)
        assertEquals("guarded-object.jpg", saved.objectKey)
    }

    @Test
    fun `successful processing calls storage outside transaction and removes task`() {
        val task = immediateTask("delete-me.jpg")
        val transactionStates = mutableListOf<Boolean>()
        Mockito.doAnswer {
            transactionStates += TransactionSynchronizationManager.isActualTransactionActive()
            null
        }.`when`(storageService).deleteObject("test-bucket", "delete-me.jpg")

        val result = mediaCleanupProcessor.processTask(task.id)

        assertEquals(MediaCleanupProcessResult.SUCCEEDED, result)
        assertEquals(listOf(false), transactionStates)
        assertFalse(mediaCleanupTaskRepository.existsById(task.id))
    }

    @Test
    fun `failed processing retries with bounded attempts then marks failed`() {
        val now = OffsetDateTime.parse("2026-07-16T12:00:00Z")
        val task = immediateTask("flaky.jpg", now)
        Mockito.doThrow(ObjectStorageException("storage unavailable"))
            .`when`(storageService).deleteObject("test-bucket", "flaky.jpg")

        mediaCleanupProcessor.processTask(task.id, now)
        mediaCleanupProcessor.processTask(task.id, now.plusHours(2))
        mediaCleanupProcessor.processTask(task.id, now.plusHours(4))

        val saved = mediaCleanupTaskRepository.findById(task.id).orElseThrow()
        assertEquals(3, saved.attemptCount)
        assertEquals(MediaCleanupTaskStatus.FAILED, saved.status)
        assertNull(saved.leaseUntil)
        assertTrue(saved.lastError!!.contains("ObjectStorageException"))
    }

    @Test
    fun `expired processing lease can be reclaimed but active lease cannot`() {
        val now = OffsetDateTime.parse("2026-07-16T12:00:00Z")
        val active = mediaCleanupTaskRepository.saveAndFlush(
            MediaCleanupTask(
                status = MediaCleanupTaskStatus.PROCESSING,
                bucket = "test-bucket",
                objectKey = "leased.jpg",
                nextAttemptAt = now.minusMinutes(10),
                leaseUntil = now.plusMinutes(5),
                createdAt = now.minusMinutes(10),
                updatedAt = now.minusMinutes(10)
            )
        )
        assertNull(mediaCleanupTaskService.claimTask(active.id, now))

        active.leaseUntil = now.minusSeconds(1)
        mediaCleanupTaskRepository.saveAndFlush(active)

        val claimed = mediaCleanupTaskService.claimTask(active.id, now)
        assertNotNull(claimed)
        val saved = mediaCleanupTaskRepository.findById(active.id).orElseThrow()
        assertEquals(MediaCleanupTaskStatus.PROCESSING, saved.status)
        assertEquals(now.plusMinutes(5), saved.leaseUntil)
    }

    @Test
    fun `duplicate delete task creation returns existing outstanding task`() {
        val first = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = "test-bucket",
            objectKey = "same.jpg"
        )
        val second = mediaCleanupTaskService.createGuardTask(
            storageProvider = PhotoStorageProvider.S3,
            bucket = "test-bucket",
            objectKey = "same.jpg"
        )

        assertEquals(first.id, second.id)
        assertEquals(1, mediaCleanupTaskRepository.findAll().size)
    }

    @Test
    fun `job processes at most batch size and continues after a failing task`() {
        val now = OffsetDateTime.parse("2026-07-16T12:00:00Z")
        immediateTask("one.jpg", now)
        immediateTask("two.jpg", now.plusSeconds(1))
        immediateTask("three.jpg", now.plusSeconds(2))
        Mockito.doThrow(ObjectStorageException("storage unavailable"))
            .`when`(storageService).deleteObject("test-bucket", "one.jpg")

        val summary = mediaCleanupJob.processMediaCleanup()

        assertEquals(2, summary.processed)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(
            listOf("one.jpg", "three.jpg"),
            mediaCleanupTaskRepository.findAll().map { it.objectKey }.sorted()
        )
    }

    private fun immediateTask(
        key: String,
        now: OffsetDateTime = OffsetDateTime.now()
    ): MediaCleanupTask =
        mediaCleanupTaskRepository.saveAndFlush(
            MediaCleanupTask(
                bucket = "test-bucket",
                objectKey = key,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
}
