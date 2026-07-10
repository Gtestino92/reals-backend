package com.reals.backend.service.photo

import com.google.cloud.vision.v1.AnnotateImageRequest
import com.google.cloud.vision.v1.BatchAnnotateImagesRequest
import com.google.cloud.vision.v1.Feature
import com.google.cloud.vision.v1.Image
import com.google.cloud.vision.v1.ImageAnnotatorClient
import com.google.cloud.vision.v1.Likelihood
import com.google.protobuf.ByteString
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "profile.photos.moderation",
    name = ["provider"],
    havingValue = "google-vision"
)
class GoogleVisionPhotoAnalysisProvider(
    private val client: ImageAnnotatorClient
) : ProfilePhotoAnalysisProvider {

    private val logger = LoggerFactory.getLogger(GoogleVisionPhotoAnalysisProvider::class.java)

    override fun analyze(request: ProfilePhotoAnalysisRequest): ProfilePhotoAnalysisProviderResult =
        try {
            val image = Image.newBuilder()
                .setContent(ByteString.copyFrom(request.bytes))
                .build()
            val annotationRequest = AnnotateImageRequest.newBuilder()
                .setImage(image)
                .addFeatures(feature(Feature.Type.FACE_DETECTION))
                .addFeatures(feature(Feature.Type.SAFE_SEARCH_DETECTION))
                .build()
            val batchRequest = BatchAnnotateImagesRequest.newBuilder()
                .addRequests(annotationRequest)
                .build()

            val response = client.batchAnnotateImages(batchRequest)

            if (response.responsesCount != 1) {
                return providerFailure("Unexpected Google Vision response cardinality")
            }

            val annotationResponse = response.getResponses(0)
            if (annotationResponse.hasError()) {
                logger.warn(
                    "Google Vision profile photo analysis failed with response-level error code {}",
                    annotationResponse.error.code
                )
                return providerFailure("Google Vision response-level error")
            }

            if (!annotationResponse.hasSafeSearchAnnotation()) {
                return providerFailure("Google Vision response did not include SafeSearch annotation")
            }

            val safeSearch = annotationResponse.safeSearchAnnotation
            ProfilePhotoAnalysisProviderResult.Success(
                provider = PROVIDER,
                signals = ProfilePhotoAnalysisSignals(
                    provider = PROVIDER,
                    faceDetectionConfidences = annotationResponse.faceAnnotationsList
                        .map { it.detectionConfidence.toDouble() },
                    safeSearch = PhotoSafeSearchSignals(
                        adult = safeSearch.adult.toContentLikelihood(),
                        spoof = safeSearch.spoof.toContentLikelihood(),
                        medical = safeSearch.medical.toContentLikelihood(),
                        violence = safeSearch.violence.toContentLikelihood(),
                        racy = safeSearch.racy.toContentLikelihood()
                    )
                )
            )
        } catch (ex: Exception) {
            logger.warn(
                "Google Vision profile photo analysis request failed with exception type {}",
                ex.javaClass.simpleName
            )
            providerFailure("Google Vision client exception")
        }

    private fun feature(type: Feature.Type): Feature =
        Feature.newBuilder()
            .setType(type)
            .build()

    private fun providerFailure(reason: String): ProfilePhotoAnalysisProviderResult.ProviderFailure =
        ProfilePhotoAnalysisProviderResult.ProviderFailure(
            provider = PROVIDER,
            reason = reason
        )

    private fun Likelihood.toContentLikelihood(): PhotoContentLikelihood =
        when (this) {
            Likelihood.UNKNOWN -> PhotoContentLikelihood.UNKNOWN
            Likelihood.VERY_UNLIKELY -> PhotoContentLikelihood.VERY_UNLIKELY
            Likelihood.UNLIKELY -> PhotoContentLikelihood.UNLIKELY
            Likelihood.POSSIBLE -> PhotoContentLikelihood.POSSIBLE
            Likelihood.LIKELY -> PhotoContentLikelihood.LIKELY
            Likelihood.VERY_LIKELY -> PhotoContentLikelihood.VERY_LIKELY
            Likelihood.UNRECOGNIZED -> PhotoContentLikelihood.UNKNOWN
        }

    private companion object {
        const val PROVIDER = "google-vision"
    }
}
