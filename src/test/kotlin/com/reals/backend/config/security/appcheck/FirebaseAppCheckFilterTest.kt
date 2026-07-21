package com.reals.backend.config.security.appcheck

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirebaseAppCheckFilterTest {

    @Test
    fun `disabled does not require header`() {
        val verifier = FakeVerifier(FirebaseAppCheckVerificationResult.Invalid)
        val response = runFilter(filter(FirebaseAppCheckMode.DISABLED, verifier), request())

        assertEquals(200, response.status)
        assertEquals(0, verifier.calls)
    }

    @Test
    fun `monitor allows missing invalid and unavailable tokens`() {
        listOf(
            null to FirebaseAppCheckVerificationResult.Invalid,
            "invalid-token" to FirebaseAppCheckVerificationResult.Invalid,
            "unavailable-token" to FirebaseAppCheckVerificationResult.Unavailable("RemoteKeySourceException")
        ).forEach { (token, result) ->
            val verifier = FakeVerifier(result)
            val response = runFilter(filter(FirebaseAppCheckMode.MONITOR, verifier), request(token = token))

            assertEquals(200, response.status)
        }
    }

    @Test
    fun `enforced rejects missing token`() {
        val response = runFilter(filter(FirebaseAppCheckMode.ENFORCED), request())

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("MISSING_APP_CHECK_TOKEN"))
    }

    @Test
    fun `enforced rejects invalid token`() {
        val response = runFilter(
            filter(FirebaseAppCheckMode.ENFORCED, FakeVerifier(FirebaseAppCheckVerificationResult.Invalid)),
            request(token = "invalid")
        )

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("INVALID_APP_CHECK_TOKEN"))
    }

    @Test
    fun `enforced returns unavailable when verifier is unavailable`() {
        val response = runFilter(
            filter(
                FirebaseAppCheckMode.ENFORCED,
                FakeVerifier(FirebaseAppCheckVerificationResult.Unavailable("RemoteKeySourceException"))
            ),
            request(token = "temporarily-unverifiable")
        )

        assertEquals(503, response.status)
        assertTrue(response.contentAsString.contains("APP_CHECK_VERIFICATION_UNAVAILABLE"))
    }

    @Test
    fun `enforced allows valid token and attaches app id`() {
        val request = request(token = "valid")
        val response = runFilter(
            filter(
                FirebaseAppCheckMode.ENFORCED,
                FakeVerifier(FirebaseAppCheckVerificationResult.Valid("1:123:android:app"))
            ),
            request
        )

        assertEquals(200, response.status)
        assertEquals(
            "1:123:android:app",
            request.getAttribute(FirebaseAppCheckFilter.VALID_APP_ID_ATTRIBUTE)
        )
    }

    @Test
    fun `excluded paths bypass app check`() {
        val excludedRequests = listOf(
            MockHttpServletRequest("OPTIONS", "/api/me"),
            request(path = "/api/ping"),
            request(path = "/actuator/health"),
            request(path = "/actuator/health/readiness"),
            request(path = "/actuator/info"),
            request(path = "/actuator/metrics"),
            request(path = "/actuator/metrics/jvm.memory.used"),
            request(path = "/api/local-dev/matchmaking/process"),
            request(path = "/h2-console/")
        )

        excludedRequests.forEach { excluded ->
            val verifier = FakeVerifier(FirebaseAppCheckVerificationResult.Invalid)
            val response = runFilter(filter(FirebaseAppCheckMode.ENFORCED, verifier), excluded)

            assertEquals(200, response.status, excluded.requestURI)
            assertEquals(0, verifier.calls, excluded.requestURI)
        }
    }

    @Test
    fun `local firebase email verification endpoint is protected`() {
        val response = runFilter(
            filter(FirebaseAppCheckMode.ENFORCED),
            request(path = "/api/me/local-dev/email-verification")
        )

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("MISSING_APP_CHECK_TOKEN"))
    }

    @Test
    fun `raw tokens are absent from logs`() {
        val logger = LoggerFactory.getLogger(FirebaseAppCheckFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        val originalLevel = logger.level
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.WARN

        try {
            val rawToken = "secret-app-check-token"
            runFilter(
                filter(
                    FirebaseAppCheckMode.MONITOR,
                    FakeVerifier(FirebaseAppCheckVerificationResult.Unavailable("RemoteKeySourceException"))
                ),
                request(token = rawToken)
            )

            assertFalse(
                appender.list.joinToString("\n") { it.formattedMessage }.contains(rawToken)
            )
        } finally {
            logger.detachAppender(appender)
            logger.level = originalLevel
        }
    }

    @Test
    fun `metrics use bounded tags`() {
        val registry = SimpleMeterRegistry()
        runFilter(
            filter(FirebaseAppCheckMode.MONITOR, meterRegistry = registry),
            request(path = "/api/me/profile/photos", token = null)
        )

        val counter = registry.get(FirebaseAppCheckFilter.METER_NAME)
            .tag("mode", "monitor")
            .tag("outcome", "missing")
            .tag("endpoint_group", "profile-photo")
            .tag("exception", "none")
            .counter()

        assertEquals(1.0, counter.count())
    }

    private fun filter(
        mode: FirebaseAppCheckMode,
        verifier: FirebaseAppCheckVerifier = FakeVerifier(FirebaseAppCheckVerificationResult.Valid("app-id")),
        meterRegistry: SimpleMeterRegistry? = null
    ): FirebaseAppCheckFilter =
        FirebaseAppCheckFilter(
            FirebaseAppCheckProperties(mode = mode),
            verifier,
            meterRegistry
        )

    private fun runFilter(
        filter: FirebaseAppCheckFilter,
        request: MockHttpServletRequest
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    private fun request(
        path: String = "/api/me",
        token: String? = null
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", path).apply {
            if (token != null) {
                addHeader(FirebaseAppCheckFilter.HEADER_NAME, token)
            }
        }

    private class FakeVerifier(
        private val result: FirebaseAppCheckVerificationResult
    ) : FirebaseAppCheckVerifier {
        var calls = 0

        override fun verify(token: String): FirebaseAppCheckVerificationResult {
            calls += 1
            return result
        }
    }
}
