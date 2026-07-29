package com.reals.backend.service

import com.reals.backend.config.ChatAudioProperties
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

class ChatAudioUploadBusyException(
    val retryAfterSeconds: Long
) : RuntimeException("Chat audio upload capacity is temporarily exhausted")

@Component
class ChatAudioUploadGuard(
    properties: ChatAudioProperties
) {
    private val uploadProperties = properties.upload
    private val semaphore = Semaphore(uploadProperties.maxConcurrent)

    fun <T> withPermit(block: () -> T): T {
        val acquired =
            try {
                if (uploadProperties.permitWaitDuration.isZero) {
                    semaphore.tryAcquire()
                } else {
                    semaphore.tryAcquire(
                        uploadProperties.permitWaitDuration.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        if (!acquired) {
            throw ChatAudioUploadBusyException(uploadProperties.retryAfterSeconds)
        }

        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}
