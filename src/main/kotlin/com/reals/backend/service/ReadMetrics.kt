package com.reals.backend.service

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class ReadMetrics(
    private val meterRegistry: MeterRegistry
) {

    fun <T> recordHomeLoad(
        variant: String,
        operation: () -> T
    ): T {
        val sample = Timer.start(meterRegistry)
        var outcome = SUCCESS

        try {
            return operation()
        } catch (ex: Throwable) {
            outcome = ERROR
            throw ex
        } finally {
            sample.stop(
                Timer.builder(HOME_LOAD)
                    .tag(VARIANT, variant)
                    .tag(OUTCOME, outcome)
                    .register(meterRegistry)
            )
        }
    }

    fun <T> recordChatMessageRead(
        mode: String,
        operation: () -> T
    ): T {
        val sample = Timer.start(meterRegistry)
        var outcome = SUCCESS

        try {
            return operation()
        } catch (ex: Throwable) {
            outcome = ERROR
            throw ex
        } finally {
            sample.stop(
                Timer.builder(CHAT_MESSAGES_READ)
                    .tag(MODE, mode)
                    .tag(OUTCOME, outcome)
                    .register(meterRegistry)
            )
        }
    }

    fun recordReturnedChatMessages(
        mode: String,
        count: Int
    ) {
        DistributionSummary.builder(CHAT_MESSAGES_RETURNED)
            .tag(MODE, mode)
            .register(meterRegistry)
            .record(count.toDouble())
    }

    companion object {
        const val HOME_LOAD = "reals.home.load"
        const val CHAT_MESSAGES_READ = "reals.chat.messages.read"
        const val CHAT_MESSAGES_RETURNED = "reals.chat.messages.returned"

        const val HOME_VARIANT_FULL = "full"
        const val HOME_VARIANT_PENDING = "pending"
        const val CHAT_MODE_INITIAL = "initial"
        const val CHAT_MODE_INCREMENTAL = "incremental"

        private const val VARIANT = "variant"
        private const val MODE = "mode"
        private const val OUTCOME = "outcome"
        private const val SUCCESS = "success"
        private const val ERROR = "error"
    }
}
