package com.reals.backend.service.photo

import com.google.cloud.vision.v1.AnnotateImageResponse
import com.google.cloud.vision.v1.BatchAnnotateImagesRequest
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse
import com.google.cloud.vision.v1.FaceAnnotation
import com.google.cloud.vision.v1.Feature
import com.google.cloud.vision.v1.ImageAnnotatorClient
import com.google.cloud.vision.v1.Likelihood
import com.google.cloud.vision.v1.SafeSearchAnnotation
import com.google.protobuf.ByteString
import com.google.rpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.util.UUID

class GoogleVisionPhotoAnalysisProviderTest {

    @Test
    fun `analysis sends one request with face detection and safe search and maps response`() {
        val client = Mockito.mock(ImageAnnotatorClient::class.java)
        Mockito.`when`(client.batchAnnotateImages(anyBatchRequest()))
            .thenReturn(
                BatchAnnotateImagesResponse.newBuilder()
                    .addResponses(
                        AnnotateImageResponse.newBuilder()
                            .addFaceAnnotations(
                                FaceAnnotation.newBuilder()
                                    .setDetectionConfidence(0.75f)
                            )
                            .addFaceAnnotations(
                                FaceAnnotation.newBuilder()
                                    .setDetectionConfidence(0.25f)
                            )
                            .setSafeSearchAnnotation(
                                SafeSearchAnnotation.newBuilder()
                                    .setAdult(Likelihood.POSSIBLE)
                                    .setSpoof(Likelihood.LIKELY)
                                    .setMedical(Likelihood.UNLIKELY)
                                    .setViolence(Likelihood.VERY_UNLIKELY)
                                    .setRacy(Likelihood.VERY_LIKELY)
                            )
                    )
                    .build()
            )
        val bytes = byteArrayOf(1, 2, 3)
        val provider = GoogleVisionPhotoAnalysisProvider(client)

        val result = provider.analyze(request(bytes))

        val success = assertInstanceOf(
            ProfilePhotoAnalysisProviderResult.Success::class.java,
            result
        )
        assertEquals("google-vision", success.provider)
        assertEquals(listOf(0.75, 0.25), success.signals.faceDetectionConfidences)
        assertEquals(PhotoContentLikelihood.POSSIBLE, success.signals.safeSearch.adult)
        assertEquals(PhotoContentLikelihood.LIKELY, success.signals.safeSearch.spoof)
        assertEquals(PhotoContentLikelihood.UNLIKELY, success.signals.safeSearch.medical)
        assertEquals(PhotoContentLikelihood.VERY_UNLIKELY, success.signals.safeSearch.violence)
        assertEquals(PhotoContentLikelihood.VERY_LIKELY, success.signals.safeSearch.racy)

        val captor = ArgumentCaptor.forClass(BatchAnnotateImagesRequest::class.java)
        Mockito.verify(client, Mockito.times(1)).batchAnnotateImages(captor.capture())
        val visionRequest = captor.value.requestsList.single()
        assertEquals(ByteString.copyFrom(bytes), visionRequest.image.content)
        assertEquals(
            setOf(Feature.Type.FACE_DETECTION, Feature.Type.SAFE_SEARCH_DETECTION),
            visionRequest.featuresList.map { it.type }.toSet()
        )
    }

    @Test
    fun `response-level Vision error becomes provider failure`() {
        val client = Mockito.mock(ImageAnnotatorClient::class.java)
        Mockito.`when`(client.batchAnnotateImages(anyBatchRequest()))
            .thenReturn(
                BatchAnnotateImagesResponse.newBuilder()
                    .addResponses(
                        AnnotateImageResponse.newBuilder()
                            .setError(
                                Status.newBuilder()
                                    .setCode(13)
                                    .setMessage("internal")
                            )
                    )
                    .build()
            )
        val provider = GoogleVisionPhotoAnalysisProvider(client)

        val result = provider.analyze(request())

        assertInstanceOf(ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java, result)
        Mockito.verify(client, Mockito.times(1)).batchAnnotateImages(anyBatchRequest())
    }

    @Test
    fun `client exception becomes provider failure`() {
        val client = Mockito.mock(ImageAnnotatorClient::class.java)
        Mockito.`when`(client.batchAnnotateImages(anyBatchRequest()))
            .thenThrow(RuntimeException("network unavailable"))
        val provider = GoogleVisionPhotoAnalysisProvider(client)

        val result = provider.analyze(request())

        assertInstanceOf(ProfilePhotoAnalysisProviderResult.ProviderFailure::class.java, result)
        Mockito.verify(client, Mockito.times(1)).batchAnnotateImages(anyBatchRequest())
    }

    private fun request(bytes: ByteArray = byteArrayOf(1)): ProfilePhotoAnalysisRequest =
        ProfilePhotoAnalysisRequest(
            userId = UUID.randomUUID(),
            profileId = UUID.randomUUID(),
            photoId = UUID.randomUUID(),
            contentType = "image/jpeg",
            bytes = bytes
        )

    private fun anyBatchRequest(): BatchAnnotateImagesRequest {
        any(BatchAnnotateImagesRequest::class.java)
        return BatchAnnotateImagesRequest.getDefaultInstance()
    }
}
