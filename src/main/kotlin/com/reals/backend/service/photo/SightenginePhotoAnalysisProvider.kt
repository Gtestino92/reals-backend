package com.reals.backend.service.photo

import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import com.fasterxml.jackson.annotation.JsonProperty

@Component
@Conditional(ProductionSightenginePhotoAnalysisCondition::class)
class SightenginePhotoAnalysisProvider(
    private val restClient: RestClient,
    private val properties: SightenginePhotoAnalysisProperties
) : ProfilePhotoAnalysisProvider {

    private val logger = LoggerFactory.getLogger(SightenginePhotoAnalysisProvider::class.java)

    init {
        properties.requireCredentials()
    }

    override fun analyze(request: ProfilePhotoAnalysisRequest): ProfilePhotoAnalysisProviderResult =
        try {
            val response = restClient.post()
                .uri(properties.endpoint)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipartBody(request))
                .retrieve()
                .body(SightengineCheckResponse::class.java)
                ?: return providerFailure("Empty Sightengine response")

            mapResponse(response)
        } catch (ex: Exception) {
            logger.warn(
                "Sightengine profile photo analysis failed with exception type {}",
                ex.javaClass.simpleName
            )
            providerFailure("Sightengine request failed")
        }

    private fun multipartBody(request: ProfilePhotoAnalysisRequest): LinkedMultiValueMap<String, Any> {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("media", mediaPart(request))
        body.add("models", MODELS)
        body.add("api_user", properties.apiUser)
        body.add("api_secret", properties.apiSecret)
        return body
    }

    private fun mediaPart(request: ProfilePhotoAnalysisRequest): HttpEntity<ByteArrayResource> {
        val resource = object : ByteArrayResource(request.bytes) {
            override fun getFilename(): String = filenameFor(request.contentType)
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(request.contentType)
            contentDisposition = ContentDisposition.formData()
                .name("media")
                .filename(resource.filename)
                .build()
        }
        return HttpEntity(resource, headers)
    }

    private fun filenameFor(contentType: String): String =
        when (contentType.lowercase()) {
            MediaType.IMAGE_JPEG_VALUE -> "profile-photo.jpg"
            MediaType.IMAGE_PNG_VALUE -> "profile-photo.png"
            else -> "profile-photo"
        }

    private fun mapResponse(response: SightengineCheckResponse): ProfilePhotoAnalysisProviderResult {
        if (response.status != "success") {
            return providerFailure("Sightengine status was not success")
        }

        val faces = response.faces ?: return providerFailure("Sightengine faces block missing")
        response.artificialFaces ?: return providerFailure("Sightengine artificial_faces block missing")
        val nudity = response.nudity ?: return providerFailure("Sightengine nudity block missing")
        val violence = response.violence ?: return providerFailure("Sightengine violence block missing")
        val gore = response.gore ?: return providerFailure("Sightengine gore block missing")
        val offensive = response.offensive ?: return providerFailure("Sightengine offensive block missing")
        val violenceClasses = violence.classes ?: return providerFailure("Sightengine violence classes missing")

        val signals = ProfilePhotoAnalysisSignals(
            provider = PROVIDER,
            realFaceCount = faces.size,
            moderation = ProfilePhotoModerationSignals(
                sexualExplicit = maxScore(
                    nudity.sexualActivity,
                    nudity.sexualDisplay,
                    nudity.erotica
                ),
                sexualSuggestive = maxScore(
                    nudity.verySuggestive,
                    nudity.suggestive
                ),
                violenceOrThreat = maxScore(
                    violenceClasses.physicalViolence,
                    violenceClasses.firearmThreat
                ),
                gore = score(gore.prob),
                hateOrExtremism = maxScore(
                    offensive.nazi,
                    offensive.supremacist,
                    offensive.terrorist
                )
            )
        )

        return ProfilePhotoAnalysisProviderResult.Success(
            provider = PROVIDER,
            signals = signals
        )
    }

    private fun maxScore(vararg values: Double?): Double =
        values.maxOf { score(it) }

    private fun score(value: Double?): Double {
        val score = value ?: throw IllegalArgumentException("Missing Sightengine score")
        require(score.isFinite() && score in 0.0..1.0) {
            "Invalid Sightengine score"
        }
        return score
    }

    private fun providerFailure(reason: String): ProfilePhotoAnalysisProviderResult.ProviderFailure =
        ProfilePhotoAnalysisProviderResult.ProviderFailure(
            provider = PROVIDER,
            reason = reason
        )

    private companion object {
        const val PROVIDER = "sightengine"
        const val MODELS = "face-analysis,nudity-2.1,violence,gore-2.0,offensive-2.0"
    }
}

data class SightengineCheckResponse(
    val status: String? = null,
    val request: Map<String, Any?>? = null,
    val faces: List<SightengineFace>? = null,
    @param:JsonProperty("artificial_faces")
    val artificialFaces: List<SightengineFace>? = null,
    val nudity: SightengineNudity? = null,
    val violence: SightengineViolence? = null,
    val gore: SightengineGore? = null,
    val offensive: SightengineOffensive? = null
)

data class SightengineFace(
    val x1: Double? = null,
    val y1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null
)

data class SightengineNudity(
    @param:JsonProperty("sexual_activity")
    val sexualActivity: Double? = null,
    @param:JsonProperty("sexual_display")
    val sexualDisplay: Double? = null,
    val erotica: Double? = null,
    @param:JsonProperty("very_suggestive")
    val verySuggestive: Double? = null,
    val suggestive: Double? = null,
    @param:JsonProperty("mildly_suggestive")
    val mildlySuggestive: Double? = null
)

data class SightengineViolence(
    val prob: Double? = null,
    val classes: SightengineViolenceClasses? = null
)

data class SightengineViolenceClasses(
    @param:JsonProperty("physical_violence")
    val physicalViolence: Double? = null,
    @param:JsonProperty("firearm_threat")
    val firearmThreat: Double? = null,
    @param:JsonProperty("combat_sport")
    val combatSport: Double? = null
)

data class SightengineGore(
    val prob: Double? = null
)

data class SightengineOffensive(
    val nazi: Double? = null,
    val supremacist: Double? = null,
    val terrorist: Double? = null,
    @param:JsonProperty("asian_swastika")
    val asianSwastika: Double? = null,
    val confederate: Double? = null,
    @param:JsonProperty("middle_finger")
    val middleFinger: Double? = null
)
