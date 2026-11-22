package com.reals.backend.service.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.SocketTimeoutException

@ExtendWith(OutputCaptureExtension::class)
class FirebasePasswordResetEmailServiceTest {

    @Test
    fun `sends password reset oob code and logs acceptance without leaking request at debug`(output: CapturedOutput) {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """
                    {
                      "requestType": "PASSWORD_RESET",
                      "email": "user@example.com"
                    }
                    """.trimIndent()
                )
            )
            .andRespond(withSuccess("""{"email":"user@example.com"}""", MediaType.APPLICATION_JSON))

        val restClientLogger = LoggerFactory.getLogger("org.springframework.web.client.DefaultRestClient") as Logger
        val serviceLogger = LoggerFactory.getLogger(FirebasePasswordResetEmailService::class.java) as Logger
        val previousRestClientLevel = restClientLogger.level
        val previousServiceLevel = serviceLogger.level
        try {
            restClientLogger.level = Level.DEBUG
            serviceLogger.level = Level.INFO
            fixture.service.sendPasswordResetEmail("user@example.com")
        } finally {
            serviceLogger.level = previousServiceLevel
            restClientLogger.level = previousRestClientLevel
        }
        fixture.server.verify()
        assertTrue(output.out.contains("Writing [FirebaseSendOobCodeRequest]"))
        assertTrue(output.out.contains("event=password_reset outcome=firebase_accepted http_status=200"))
        assertFalse(output.out.contains("user@example.com"))
        assertFalse(output.out.contains("test-key"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMAIL_NOT_FOUND", "RESET_PASSWORD_EXCEED_LIMIT", "OPERATION_NOT_ALLOWED"])
    fun `logs an allowlisted rejection code without provider context`(code: String, output: CapturedOutput) {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"$code : private context for absent@example.com"}}""")
            )

        fixture.service.sendPasswordResetEmail("absent@example.com")

        fixture.server.verify()
        assertTrue(output.out.contains("event=password_reset outcome=firebase_rejected http_status=400 firebase_code=$code"))
        assertFalse(output.out.contains("absent@example.com"))
        assertFalse(output.out.contains("private context"))
    }

    @Test
    fun `classifies an API key restriction from structured provider details`(output: CapturedOutput) {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """{"error":{"message":"Blocked for test-key","details":[
                            {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_SERVICE_BLOCKED"}
                        ]}}"""
                    )
            )

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
        assertTrue(output.out.contains(
            "event=password_reset outcome=firebase_rejected http_status=403 firebase_code=API_KEY_SERVICE_BLOCKED"
        ))
        assertFalse(output.out.contains("test-key"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "malformed private-token", """{"error":{"message":"private-token EMAIL_NOT_FOUND"}}"""])
    fun `unknown or malformed error body stays private`(body: String, output: CapturedOutput) {
        val fixture = fixture()
        fixture.server.expect(requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(body))

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
        assertTrue(output.out.contains("event=password_reset outcome=firebase_rejected http_status=500 firebase_code=UNKNOWN"))
        assertFalse(output.out.contains("private-token"))
        assertFalse(output.out.contains("EMAIL_NOT_FOUND"))
    }

    @Test
    fun `redirect is classified as rejection`(output: CapturedOutput) {
        val fixture = fixture()
        fixture.server.expect(requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(withStatus(HttpStatus.FOUND))

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
        assertTrue(output.out.contains("event=password_reset outcome=firebase_rejected http_status=302 firebase_code=UNKNOWN"))
        assertFalse(output.out.contains("firebase_accepted"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `distinguishes transport timeout from other IO failures`(timeout: Boolean, output: CapturedOutput) {
        val fixture = fixture()
        val failure = if (timeout) SocketTimeoutException("private upstream detail") else ConnectException("private upstream detail")
        fixture.server.expect(requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(withException(failure))

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
        val outcome = if (timeout) "firebase_timeout" else "firebase_transport_failure"
        assertTrue(output.out.contains("event=password_reset outcome=$outcome"))
        assertFalse(output.out.contains("private upstream detail"))
        assertFalse(output.out.contains("test-key"))
    }

    @Test
    fun `unconfigured api key skips Firebase call`(output: CapturedOutput) {
        val builder = RestClient.builder().baseUrl(BASE_URL)
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = FirebasePasswordResetEmailService(
            firebaseAuthRestClient = builder.build(),
            properties = FirebaseAuthRestProperties(
                webApiKey = "",
                baseUrl = BASE_URL
            )
        )

        service.sendPasswordResetEmail("user@example.com")

        server.verify()
        assertTrue(output.out.contains("event=password_reset outcome=firebase_unconfigured"))
    }

    private fun fixture(): Fixture {
        val builder = RestClient.builder().baseUrl(BASE_URL)
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = FirebasePasswordResetEmailService(
            firebaseAuthRestClient = builder.build(),
            properties = FirebaseAuthRestProperties(
                webApiKey = "test-key",
                baseUrl = BASE_URL
            )
        )
        return Fixture(service, server)
    }

    private data class Fixture(
        val service: FirebasePasswordResetEmailService,
        val server: MockRestServiceServer
    )

    private companion object {
        const val BASE_URL = "https://identitytoolkit.example.test/v1"
    }
}

