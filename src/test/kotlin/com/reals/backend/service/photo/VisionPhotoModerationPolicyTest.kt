package com.reals.backend.service.photo

import com.reals.backend.domain.PhotoModerationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisionPhotoModerationPolicyTest {

    private val policy = VisionPhotoModerationPolicy(GoogleVisionPhotoAnalysisProperties())

    @Test
    fun `all signals below review thresholds are approved`() {
        val result = policy.evaluate(signals())

        assertEquals(PhotoModerationStatus.APPROVED, result.status)
    }

    @Test
    fun `adult exactly at review threshold needs review`() {
        val result = policy.evaluate(signals(adult = PhotoContentLikelihood.POSSIBLE))

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
    }

    @Test
    fun `adult exactly at reject threshold is rejected`() {
        val result = policy.evaluate(signals(adult = PhotoContentLikelihood.LIKELY))

        assertEquals(PhotoModerationStatus.REJECTED, result.status)
    }

    @Test
    fun `violence reject threshold is rejected`() {
        val result = policy.evaluate(signals(violence = PhotoContentLikelihood.LIKELY))

        assertEquals(PhotoModerationStatus.REJECTED, result.status)
    }

    @Test
    fun `racy possible needs review`() {
        val result = policy.evaluate(signals(racy = PhotoContentLikelihood.POSSIBLE))

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
    }

    @Test
    fun `racy very likely is rejected`() {
        val result = policy.evaluate(signals(racy = PhotoContentLikelihood.VERY_LIKELY))

        assertEquals(PhotoModerationStatus.REJECTED, result.status)
    }

    @Test
    fun `medical review threshold needs review and does not reject`() {
        val result = policy.evaluate(signals(medical = PhotoContentLikelihood.LIKELY))

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
    }

    @Test
    fun `spoof review threshold needs review and does not reject`() {
        val result = policy.evaluate(signals(spoof = PhotoContentLikelihood.LIKELY))

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
    }

    @Test
    fun `unknown signal needs review when there is no reject signal`() {
        val result = policy.evaluate(signals(adult = PhotoContentLikelihood.UNKNOWN))

        assertEquals(PhotoModerationStatus.NEEDS_REVIEW, result.status)
    }

    @Test
    fun `reject takes precedence over review and unknown`() {
        val result = policy.evaluate(
            signals(
                adult = PhotoContentLikelihood.UNKNOWN,
                racy = PhotoContentLikelihood.VERY_LIKELY
            )
        )

        assertEquals(PhotoModerationStatus.REJECTED, result.status)
    }

    private fun signals(
        adult: PhotoContentLikelihood = PhotoContentLikelihood.UNLIKELY,
        spoof: PhotoContentLikelihood = PhotoContentLikelihood.UNLIKELY,
        medical: PhotoContentLikelihood = PhotoContentLikelihood.UNLIKELY,
        violence: PhotoContentLikelihood = PhotoContentLikelihood.UNLIKELY,
        racy: PhotoContentLikelihood = PhotoContentLikelihood.UNLIKELY
    ): ProfilePhotoAnalysisSignals =
        ProfilePhotoAnalysisSignals(
            provider = "google-vision",
            faceDetectionConfidences = emptyList(),
            safeSearch = PhotoSafeSearchSignals(
                adult = adult,
                spoof = spoof,
                medical = medical,
                violence = violence,
                racy = racy
            )
        )
}
