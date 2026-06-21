package com.reals.backend.controller.dev

import com.reals.backend.scheduler.AccountDeletionFinalizationJob
import com.reals.backend.scheduler.ChatTimeoutJob
import com.reals.backend.scheduler.InactivityCheckJob
import com.reals.backend.scheduler.MatchExpirationJob
import com.reals.backend.scheduler.PenaltyExpirationJob
import com.reals.backend.scheduler.SchedulingActivationJob
import com.reals.backend.scheduler.SchedulingNegotiationTimeoutJob
import com.reals.backend.scheduler.SecondChatLifecycleJob
import com.reals.backend.scheduler.VisualPhaseExpirationJob
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@Profile("local", "local-nodb", "local-postgres", "local-firebase")
@RequestMapping("/api/local-dev/jobs")
class DevJobController(
    private val accountDeletionFinalizationJob: ObjectProvider<AccountDeletionFinalizationJob>,
    private val chatTimeoutJob: ObjectProvider<ChatTimeoutJob>,
    private val inactivityCheckJob: ObjectProvider<InactivityCheckJob>,
    private val matchExpirationJob: ObjectProvider<MatchExpirationJob>,
    private val penaltyExpirationJob: ObjectProvider<PenaltyExpirationJob>,
    private val schedulingActivationJob: ObjectProvider<SchedulingActivationJob>,
    private val schedulingNegotiationTimeoutJob: ObjectProvider<SchedulingNegotiationTimeoutJob>,
    private val secondChatLifecycleJob: ObjectProvider<SecondChatLifecycleJob>,
    private val visualPhaseExpirationJob: ObjectProvider<VisualPhaseExpirationJob>
) {

    @PostMapping("/chat-timeout/run")
    fun runChatTimeout(): ResponseEntity<DevJobRunResponse> =
        runJob("ChatTimeoutJob") {
            requireJob(chatTimeoutJob, "ChatTimeoutJob").runNowForDev()
        }

    @PostMapping("/account-deletion-finalization/run")
    fun runAccountDeletionFinalization(): ResponseEntity<DevJobRunResponse> =
        runJob("AccountDeletionFinalizationJob") {
            requireJob(accountDeletionFinalizationJob, "AccountDeletionFinalizationJob").runNowForDev()
        }

    @PostMapping("/inactivity-check/run")
    fun runInactivityCheck(): ResponseEntity<DevJobRunResponse> =
        runJob("InactivityCheckJob") {
            requireJob(inactivityCheckJob, "InactivityCheckJob").run()
        }

    @PostMapping("/match-expiration/run")
    fun runMatchExpiration(): ResponseEntity<DevJobRunResponse> =
        runJob("MatchExpirationJob") {
            requireJob(matchExpirationJob, "MatchExpirationJob").run()
        }

    @PostMapping("/penalty-expiration/run")
    fun runPenaltyExpiration(): ResponseEntity<DevJobRunResponse> =
        runJob("PenaltyExpirationJob") {
            requireJob(penaltyExpirationJob, "PenaltyExpirationJob").run()
        }

    @PostMapping("/scheduling-timeout/run")
    fun runSchedulingTimeout(): ResponseEntity<DevJobRunResponse> =
        runJob("SchedulingNegotiationTimeoutJob") {
            requireJob(schedulingNegotiationTimeoutJob, "SchedulingNegotiationTimeoutJob").run()
        }

    @PostMapping("/scheduling-activation/run")
    fun runSchedulingActivation(): ResponseEntity<DevJobRunResponse> =
        runJob("SchedulingActivationJob") {
            requireJob(schedulingActivationJob, "SchedulingActivationJob").run()
        }

    @PostMapping("/second-chat-lifecycle/run")
    fun runSecondChatLifecycle(): ResponseEntity<DevJobRunResponse> =
        runJob("SecondChatLifecycleJob") {
            requireJob(secondChatLifecycleJob, "SecondChatLifecycleJob").runNowForDev()
        }

    @PostMapping("/visual-phase-expiration/run")
    fun runVisualPhaseExpiration(): ResponseEntity<DevJobRunResponse> =
        runJob("VisualPhaseExpirationJob") {
            requireJob(visualPhaseExpirationJob, "VisualPhaseExpirationJob").run()
        }

    private fun runJob(
        name: String,
        run: () -> Unit
    ): ResponseEntity<DevJobRunResponse> {
        run()
        return ResponseEntity.ok(
            DevJobRunResponse(
                job = name,
                ranAt = OffsetDateTime.now()
            )
        )
    }

    private fun <T : Any> requireJob(
        provider: ObjectProvider<T>,
        name: String
    ): T =
        provider.getIfAvailable()
            ?: throw IllegalStateException("$name is not available. Check scheduler/ShedLock configuration.")
}

data class DevJobRunResponse(
    val job: String,
    val ranAt: OffsetDateTime
)
