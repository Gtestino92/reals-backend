package com.reals.backend.service.identity

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class FirebasePasswordResetEmailServiceTest {

    @Test
    fun `sends password reset oob code request to Firebase`() {
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

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
    }

    @Test
    fun `email not found response is treated as no-op`() {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"EMAIL_NOT_FOUND"}}""")
            )

        fixture.service.sendPasswordResetEmail("absent@example.com")

        fixture.server.verify()
    }

    @Test
    fun `generic upstream failure is swallowed by adapter`() {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo("$BASE_URL/accounts:sendOobCode?key=test-key"))
            .andRespond(
                withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"INTERNAL"}}""")
            )

        fixture.service.sendPasswordResetEmail("user@example.com")

        fixture.server.verify()
    }

    @Test
    fun `unconfigured api key skips Firebase call`() {
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

