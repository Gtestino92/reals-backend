package com.reals.backend.config.security

import com.reals.backend.controller.dev.DevJobController
import com.reals.backend.controller.dev.DevMatchmakingController
import com.reals.backend.controller.dev.DevPairHistoryResetController
import com.reals.backend.controller.dev.DevTimeoutController
import com.reals.backend.controller.dev.DevUserController
import com.reals.backend.controller.dev.DevUserReliabilityController
import com.reals.backend.controller.dev.DevVisualReviewController
import com.reals.backend.repository.ChatMessageRepository
import com.reals.backend.repository.ChatRepository
import com.reals.backend.repository.ConnectionRepository
import com.reals.backend.repository.PenaltyRepository
import com.reals.backend.repository.ScheduleNegotiationRepository
import com.reals.backend.repository.SecondChatResolutionRequestRepository
import com.reals.backend.repository.VisualReviewRepository
import com.reals.backend.service.UserService
import com.reals.backend.service.VisualReviewService
import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
import com.reals.backend.service.matching.MatchmakingProcessorService
import com.reals.backend.service.reliability.UserReliabilityScoreService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import java.util.function.Supplier

class DevControllerProfileRegistrationTest {

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(DevControllerScanConfig::class.java)
            .withBean(
                MatchmakingProcessorService::class.java,
                Supplier { Mockito.mock(MatchmakingProcessorService::class.java) }
            )
            .withBean(
                LocalDevPairHistoryResetService::class.java,
                Supplier { Mockito.mock(LocalDevPairHistoryResetService::class.java) }
            )
            .withBean(
                ChatRepository::class.java,
                Supplier { Mockito.mock(ChatRepository::class.java) }
            )
            .withBean(
                ChatMessageRepository::class.java,
                Supplier { Mockito.mock(ChatMessageRepository::class.java) }
            )
            .withBean(
                ConnectionRepository::class.java,
                Supplier { Mockito.mock(ConnectionRepository::class.java) }
            )
            .withBean(
                PenaltyRepository::class.java,
                Supplier { Mockito.mock(PenaltyRepository::class.java) }
            )
            .withBean(
                ScheduleNegotiationRepository::class.java,
                Supplier { Mockito.mock(ScheduleNegotiationRepository::class.java) }
            )
            .withBean(
                SecondChatResolutionRequestRepository::class.java,
                Supplier { Mockito.mock(SecondChatResolutionRequestRepository::class.java) }
            )
            .withBean(
                VisualReviewRepository::class.java,
                Supplier { Mockito.mock(VisualReviewRepository::class.java) }
            )
            .withBean(
                UserService::class.java,
                Supplier { Mockito.mock(UserService::class.java) }
            )
            .withBean(
                UserReliabilityScoreService::class.java,
                Supplier { Mockito.mock(UserReliabilityScoreService::class.java) }
            )
            .withBean(
                VisualReviewService::class.java,
                Supplier { Mockito.mock(VisualReviewService::class.java) }
            )

    @Test
    fun `dev profile registers current dev tooling controllers`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=dev")
            .run { context ->
                assertTrue(context.containsBeanDefinition("devJobController"))
                assertTrue(context.containsBeanDefinition("devMatchmakingController"))
                assertTrue(context.containsBeanDefinition("devPairHistoryResetController"))
                assertTrue(context.containsBeanDefinition("devTimeoutController"))
                assertTrue(context.containsBeanDefinition("devUserController"))
                assertTrue(context.containsBeanDefinition("devUserReliabilityController"))
                assertTrue(context.containsBeanDefinition("devVisualReviewController"))
            }
    }

    @Test
    fun `prod profile does not register dev tooling controllers`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertFalse(context.containsBeanDefinition("devJobController"))
                assertFalse(context.containsBeanDefinition("devMatchmakingController"))
                assertFalse(context.containsBeanDefinition("devPairHistoryResetController"))
                assertFalse(context.containsBeanDefinition("devTimeoutController"))
                assertFalse(context.containsBeanDefinition("devUserController"))
                assertFalse(context.containsBeanDefinition("devUserReliabilityController"))
                assertFalse(context.containsBeanDefinition("devVisualReviewController"))
            }
    }

    @Configuration
    @ComponentScan(basePackageClasses = [
        DevJobController::class,
        DevMatchmakingController::class,
        DevPairHistoryResetController::class,
        DevTimeoutController::class,
        DevUserController::class,
        DevUserReliabilityController::class,
        DevVisualReviewController::class
    ])
    class DevControllerScanConfig
}
