package com.reals.backend.service.photo

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
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
import java.net.SocketTimeoutException
import java.util.UUID

class SightenginePhotoAnalysisProviderTest {

    @Test
    fun `analysis sends one multipart request with media credentials and fixed models then maps signals`() {
        val fixture = fixture()
        val bytes = "image-bytes".toByteArray()
        fixture.server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("""name="media"""")))
            .andExpect(content().string(containsString("image-bytes")))
            .andExpect(content().string(containsString("""name="models"""")))
            .andExpect(content().string(containsString("face-analysis,nudity-2.1,violence,gore-2.0,offensive-2.0")))
            .andExpect(content().string(containsString("""name="api_user"""")))
            .andExpect(content().string(containsString("dummy-user")))
            .andExpect(content().string(containsString("""name="api_secret"""")))
            .andExpect(content().string(containsString("dummy-secret")))
            .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON))

        val result = fixture.provider.analyze(request(bytes = bytes))

        val success = assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.Success::class.java,
            result
        )
        assertEquals("sightengine", success.provider)
        assertEquals(1, success.signals.realFaceCount)
        assertEquals(0.30, success.signals.moderation.sexualExplicit)
        assertEquals(0.70, success.signals.moderation.sexualSuggestive)
        assertEquals(0.40, success.signals.moderation.violenceOrThreat)
        assertEquals(0.35, success.signals.moderation.gore)
        assertEquals(0.45, success.signals.moderation.hateOrExtremism)
        fixture.server.verify()
    }

    @Test
    fun `multiple real faces map to real face count`() {
        val result = analyze(successResponse(faces = "[{},{}]"))

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(2, success.signals.realFaceCount)
    }

    @Test
    fun `no real faces maps to zero real face count`() {
        val result = analyze(successResponse(faces = "[]"))

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0, success.signals.realFaceCount)
    }

    @Test
    fun `artificial faces do not increase real face count`() {
        val result = analyze(successResponse(faces = "[]", artificialFaces = "[{}]"))

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0, success.signals.realFaceCount)
    }

    @Test
    fun `sexual explicit uses max of sexual activity display and erotica`() {
        val result = analyze(
            successResponse(
                nudity = """
                    {
                      "sexual_activity": 0.20,
                      "sexual_display": 0.90,
                      "erotica": 0.40,
                      "very_suggestive": 0.10,
                      "suggestive": 0.20,
                      "mildly_suggestive": 1.0
                    }
                """.trimIndent()
            )
        )

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0.90, success.signals.moderation.sexualExplicit)
    }

    @Test
    fun `sexual suggestive uses max of very suggestive and suggestive and ignores mildly suggestive`() {
        val result = analyze(
            successResponse(
                nudity = """
                    {
                      "sexual_activity": 0.10,
                      "sexual_display": 0.20,
                      "erotica": 0.30,
                      "very_suggestive": 0.20,
                      "suggestive": 0.60,
                      "mildly_suggestive": 1.0
                    }
                """.trimIndent()
            )
        )

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0.60, success.signals.moderation.sexualSuggestive)
    }

    @Test
    fun `violence uses physical violence and firearm threat and ignores combat sport`() {
        val result = analyze(
            successResponse(
                violence = """
                    {
                      "prob": 1.0,
                      "classes": {
                        "physical_violence": 0.30,
                        "firearm_threat": 0.70,
                        "combat_sport": 1.0
                      }
                    }
                """.trimIndent()
            )
        )

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0.70, success.signals.moderation.violenceOrThreat)
    }

    @Test
    fun `gore maps from gore probability`() {
        val result = analyze(successResponse(gore = """{"prob": 0.88}"""))

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0.88, success.signals.moderation.gore)
    }

    @Test
    fun `hate extremism uses nazi supremacist and terrorist only`() {
        val result = analyze(
            successResponse(
                offensive = """
                    {
                      "nazi": 0.10,
                      "supremacist": 0.70,
                      "terrorist": 0.20,
                      "asian_swastika": 1.0,
                      "confederate": 1.0,
                      "middle_finger": 1.0
                    }
                """.trimIndent()
            )
        )

        val success = result as ProfilePhotoAnalysisProviderResult.Success
        assertEquals(0.70, success.signals.moderation.hateOrExtremism)
    }

    @Test
    fun `http error becomes provider failure`() {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"status":"failure"}"""))

        val result = fixture.provider.analyze(request())

        assertInstanceOf(ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java, result)
        fixture.server.verify()
    }

    @Test
    fun `status failure becomes provider failure`() {
        assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java,
            analyze("""{"status":"failure"}""")
        )
    }

    @Test
    fun `missing required model block becomes provider failure`() {
        assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java,
            analyze(successResponse(nudity = null))
        )
    }

    @Test
    fun `invalid score becomes provider failure`() {
        assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java,
            analyze(successResponse(gore = """{"prob": 1.20}"""))
        )
    }

    @Test
    fun `malformed json becomes provider failure`() {
        assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java,
            analyze("""{"status":""")
        )
    }

    @Test
    fun `client exception becomes provider failure`() {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
            .andRespond(withException(SocketTimeoutException("timeout")))

        val result = fixture.provider.analyze(request())

        assertInstanceOf(ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java, result)
        fixture.server.verify()
    }

    private fun analyze(responseJson: String): ProfilePhotoAnalysisProviderResult {
        val fixture = fixture()
        fixture.server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON))
        val result = fixture.provider.analyze(request())
        fixture.server.verify()
        return result
    }

    private fun fixture(): ProviderFixture {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val provider = SightenginePhotoAnalysisProvider(
            restClient = builder.build(),
            properties = SightenginePhotoAnalysisProperties(
                endpoint = ENDPOINT,
                apiUser = "dummy-user",
                apiSecret = "dummy-secret"
            )
        )
        return ProviderFixture(provider, server)
    }

    private fun request(bytes: ByteArray = "image-bytes".toByteArray()): ProfilePhotoAnalysisRequest =
        ProfilePhotoAnalysisRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            bytes = bytes
        )

    private fun successResponse(
        faces: String = "[{}]",
        artificialFaces: String = "[]",
        nudity: String? = """
            {
              "sexual_activity": 0.10,
              "sexual_display": 0.30,
              "erotica": 0.20,
              "very_suggestive": 0.70,
              "suggestive": 0.60,
              "mildly_suggestive": 1.0
            }
        """.trimIndent(),
        violence: String = """
            {
              "prob": 0.90,
              "classes": {
                "physical_violence": 0.40,
                "firearm_threat": 0.20,
                "combat_sport": 0.90
              }
            }
        """.trimIndent(),
        gore: String = """{"prob": 0.35}""",
        offensive: String = """
            {
              "nazi": 0.10,
              "supremacist": 0.45,
              "terrorist": 0.20,
              "asian_swastika": 1.0,
              "confederate": 1.0,
              "middle_finger": 1.0
            }
        """.trimIndent()
    ): String =
        """
        {
          "status": "success",
          "request": { "id": "req_123" },
          "faces": $faces,
          "artificial_faces": $artificialFaces,
          ${if (nudity != null) """"nudity": $nudity,""" else ""}
          "violence": $violence,
          "gore": $gore,
          "offensive": $offensive
        }
        """.trimIndent()

    private data class ProviderFixture(
        val provider: SightenginePhotoAnalysisProvider,
        val server: MockRestServiceServer
    )

    private companion object {
        const val ENDPOINT = "https://api.sightengine.com/1.0/check.json"
    }
}
