package com.reals.backend.service

import com.reals.backend.config.s3.ProfilePhotoUploadProperties
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class ProfilePhotoUploadBusyException(
    val retryAfterSeconds: Long
) : RuntimeException("Profile photo upload capacity is temporarily exhausted")

@Component
class ProfilePhotoUploadGuard(
    private val properties: ProfilePhotoUploadProperties
) {
    private val semaphore = Semaphore(properties.maxConcurrent)

    fun <T> withPermit(block: () -> T): T {
        val acquired = acquire()
        if (!acquired) {
            throw ProfilePhotoUploadBusyException(properties.retryAfterSeconds)
        }

        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private fun acquire(): Boolean =
        try {
            if (properties.permitWaitDuration.isZero) {
                semaphore.tryAcquire()
            } else {
                semaphore.tryAcquire(
                    properties.permitWaitDuration.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
