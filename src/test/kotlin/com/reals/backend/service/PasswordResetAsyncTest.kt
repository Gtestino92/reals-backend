package com.reals.backend.service

import com.reals.backend.config.PasswordResetExecutionConfig
import com.reals.backend.controller.AuthController
import com.reals.backend.domain.User
import com.reals.backend.domain.UserAuthOrigin
import com.reals.backend.repository.UserRepository
import com.reals.backend.service.identity.FirebaseAuthRestProperties
import com.reals.backend.service.identity.FirebasePasswordResetEmailService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class PasswordResetAsyncTest {
    private val repository = mock(UserRepository::class.java)
    private val executor = PasswordResetExecutionConfig().passwordResetExecutor().apply { initialize() }

    @Test
    fun `HTTP accepted completes while Firebase response is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val builder = RestClient.builder().baseUrl("https://firebase.example.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
            .andRespond { request ->
                entered.countDown()
                try {
                    check(release.await(10, SECONDS)) { "Test did not release Firebase response" }
                    withSuccess().createResponse(request)
                } finally {
                    finished.countDown()
                }
            }
        val delivery = FirebasePasswordResetEmailService(builder.build(), FirebaseAuthRestProperties(webApiKey = "test"))
        givenEligibleUser()
        val mvc = MockMvcBuilders.standaloneSetup(AuthController(PasswordResetService(repository, delivery, executor))).build()
        try {
            // The response must complete before we release Firebase; no latency equalization or sleeps.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5)) {
                mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"user@example.com"}"""))
                    .andExpect(status().isAccepted)
                    .andExpect(content().string(""))
            }
            assertTrue(entered.await(5, SECONDS))
            assertEquals(1L, finished.count)
        } finally {
            release.countDown()
            executor.destroy()
        }
        server.verify()
    }

    @Test
    fun `bounded saturation rejects delivery without delaying HTTP or running on caller`() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val delivery = PasswordResetEmailDeliveryService {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(10, SECONDS))
        }
        givenEligibleUser()
        val service = PasswordResetService(repository, delivery, executor)
        val mvc = MockMvcBuilders.standaloneSetup(AuthController(service)).build()
        try {
            repeat(2) { service.requestPasswordReset("user@example.com") }
            assertTrue(started.await(5, SECONDS))
            repeat(32) { service.requestPasswordReset("user@example.com") }
            assertEquals(32, executor.queueSize)
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5)) {
                mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"user@example.com"}"""))
                    .andExpect(status().isAccepted).andExpect(content().string(""))
            }
            assertEquals(2, calls.get())
            assertEquals(32, executor.queueSize)
        } finally {
            executor.destroy()
            release.countDown()
        }
    }

    @Test
    fun `shutdown rejects new requests cancels queued deliveries and interrupts active delivery`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val calls = AtomicInteger()
        val delivery = PasswordResetEmailDeliveryService {
            calls.incrementAndGet()
            started.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                interrupted.countDown()
                Thread.currentThread().interrupt()
            }
        }
        givenEligibleUser()
        val service = PasswordResetService(repository, delivery, executor)
        try {
            executor.corePoolSize = 1
            executor.maxPoolSize = 1
            service.requestPasswordReset("user@example.com")
            assertTrue(started.await(5, SECONDS))
            service.requestPasswordReset("user@example.com")
            val context = GenericApplicationContext()
            executor.setApplicationContext(context)
            executor.onApplicationEvent(ContextClosedEvent(context))
            service.requestPasswordReset("user@example.com")
            assertEquals(1, executor.queueSize)
            executor.destroy()
            assertTrue(interrupted.await(5, SECONDS))
            service.requestPasswordReset("user@example.com")
            assertEquals(1, calls.get())
        } finally {
            executor.destroy()
        }
    }

    @Test
    fun `delivery failure does not stop later work`() {
        val completed = CountDownLatch(1)
        val delivery = mock(PasswordResetEmailDeliveryService::class.java)
        doThrow(IllegalStateException("sensitive upstream detail"))
            .doAnswer { completed.countDown(); null }.`when`(delivery).sendPasswordResetEmail("user@example.com")
        givenEligibleUser()
        executor.corePoolSize = 1
        executor.maxPoolSize = 1
        val service = PasswordResetService(repository, delivery, executor)
        try {
            repeat(2) { service.requestPasswordReset("user@example.com") }
            assertTrue(completed.await(5, SECONDS))
        } finally {
            executor.destroy()
        }
    }

    private fun givenEligibleUser() {
        `when`(repository.findByEmail("user@example.com")).thenReturn(
            User(email = "user@example.com", firebaseUid = "test-uid", authOrigin = UserAuthOrigin.EMAIL_PASSWORD)
        )
    }
}
