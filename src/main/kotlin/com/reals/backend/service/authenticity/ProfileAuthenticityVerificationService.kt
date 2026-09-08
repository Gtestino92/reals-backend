package com.reals.backend.service.authenticity

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ProfileAuthenticityVerificationService(
    private val provider: ProfileAuthenticityVerificationProvider,
    private val policy: ProfileAuthenticityPolicy,
    private val environmentExposurePolicy: EnvironmentExposurePolicy,

    @param:Value("\${profile.authenticity-verification.fail-on-provider-error:false}")
    private val failOnProviderError: Boolean
) {

    private val logger = LoggerFactory.getLogger(ProfileAuthenticityVerificationService::class.java)

    fun verify(request: ProfileAuthenticityVerificationRequest): ProfileAuthenticityVerificationResult {
        val result = try {
            provider.verify(request)
        } catch (ex: DomainException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(
                "Profile authenticity verification provider threw an exception of type {}",
                ex.javaClass.simpleName
            )
            ProfileAuthenticityVerificationProviderResult.ProviderFailure(
                provider = "provider-error",
                reason = "Profile authenticity verification provider failed"
            )
        }

        return when (result) {
            is ProfileAuthenticityVerificationProviderResult.Success -> evaluateSuccessfulSignals(request, result)
            is ProfileAuthenticityVerificationProviderResult.NotConfigured -> notConfiguredDecision(result.provider)
            is ProfileAuthenticityVerificationProviderResult.ProviderFailure -> providerFailureDecision(
                provider = result.provider,
                reason = result.reason
            )
        }
    }

    private fun evaluateSuccessfulSignals(
        request: ProfileAuthenticityVerificationRequest,
        result: ProfileAuthenticityVerificationProviderResult.Success
    ): ProfileAuthenticityVerificationResult =
        try {
            policy.evaluate(request, result.signals)
        } catch (ex: MalformedProfileAuthenticitySignalsException) {
            logger.warn(
                "Profile authenticity verification provider returned malformed output: {}",
                ex.message
            )
            providerFailureDecision(
                provider = result.provider,
                reason = "Profile authenticity verification provider returned malformed output"
            )
        }

    private fun notConfiguredDecision(provider: String): ProfileAuthenticityVerificationResult {
        if (environmentExposurePolicy.isProduction()) {
            throw DomainConflictException(
                code = DomainErrorCode.AUTHENTICITY_VERIFICATION_NOT_CONFIGURED,
                message = "Profile authenticity verification is not configured"
            )
        }

        // Local/dev/test compatibility only; this does not represent liveness or face comparison.
        return ProfileAuthenticityVerificationResult(
            status = ProfileAuthenticityVerificationStatus.VERIFIED,
            provider = provider
        )
    }

    private fun providerFailureDecision(
        provider: String,
        reason: String?
    ): ProfileAuthenticityVerificationResult {
        if (failOnProviderError) {
            throw DomainConflictException(
                code = DomainErrorCode.AUTHENTICITY_VERIFICATION_PROVIDER_ERROR,
                message = "Profile authenticity verification provider failed"
            )
        }

        return ProfileAuthenticityVerificationResult(
            status = ProfileAuthenticityVerificationStatus.NEEDS_REVIEW,
            provider = provider,
            reason = reason
        )
    }
}
