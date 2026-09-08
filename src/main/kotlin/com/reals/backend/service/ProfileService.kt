package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileAuthenticityVerificationStatus
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.repository.ProfileRepository
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationRequest
import com.reals.backend.service.authenticity.ProfileAuthenticityVerificationService
import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.photo.ProfilePhotoService
import com.reals.backend.validation.PlainText
import com.reals.backend.validation.SingleLinePlainText
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.util.UUID

@Service
@Transactional
class ProfileService(
    private val profileRepository: ProfileRepository,
    private val profilePhotoService: ProfilePhotoService,
    private val profileAuthenticityVerificationService: ProfileAuthenticityVerificationService,
    private val auditEventService: AuditEventService,
    private val homeStateInvalidationService: HomeStateInvalidationService,
    private val countryReferenceService: CountryReferenceService,

    @param:Value("\${profile.authenticity-verification.require-for-activation:false}")
    private val requireAuthenticityVerificationForActivation: Boolean
) {
    fun findByUserId(userId: UUID): Profile? =
        profileRepository.findByUserId(userId)

    fun findByUserIds(userIds: Collection<UUID>): List<Profile> =
        profileRepository.findByUserIdIn(userIds)

    fun findByIdOrThrow(profileId: UUID): Profile =
        profileRepository.findById(profileId)
            .orElseThrow {
                NoSuchElementException("Profile not found: $profileId")
            }

    fun createProfile(
        userId: UUID,
        displayName: String,
        birthDate: LocalDate,
        gender: Gender,
        lookingForGenders: Set<Gender>,
        intention: Intention,
        city: String,
        countryCode: String,
        bio: String? = null,
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ): Profile {
        val normalizedDisplayName = displayName.trim()
        val normalizedCity = city.trim()
        val normalizedCountryCode = countryReferenceService.normalizeAndValidateCountryCode(countryCode)
        val normalizedBio = normalizeOptionalText(bio)

        validateDisplayName(normalizedDisplayName)
        validateBirthDate(birthDate)
        validateLocation(normalizedCity)
        validateLookingForGenders(lookingForGenders)
        normalizedBio?.let { validateMultilineText("Bio", it, BIO_MAX_LENGTH) }
        validateDynamicMatchFilters(
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm
        )

        if (profileRepository.findByUserId(userId) != null) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_ALREADY_EXISTS,
                message = "User already has a profile"
            )
        }

        val profile = Profile(
            userId = userId,
            displayName = normalizedDisplayName,
            birthDate = birthDate,
            authenticityVerified = false,
            gender = gender,
            lookingForGenders = lookingForGenders.toMutableSet(),
            intention = intention,
            city = normalizedCity,
            countryCode = normalizedCountryCode,
            bio = normalizedBio,
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm,
            status = ProfileStatus.DRAFT
        )

        val saved = profileRepository.save(profile)
        homeStateInvalidationService.bump(
            userId = saved.userId,
            reason = "profile_created"
        )
        return saved
    }

    fun verifyProfileAuthenticity(profileId: UUID): Profile {
        val profile = findByIdOrThrow(profileId)
        val oldStatus = profile.authenticityVerificationStatus
        val personPhotos = profilePhotoService.authenticityCandidatesFor(profile.id)

        val authenticityVerification = profileAuthenticityVerificationService.verify(
            ProfileAuthenticityVerificationRequest(
                userId = profile.userId,
                profileId = profile.id,
                personPhotos = personPhotos
            )
        )

        profile.authenticityVerificationStatus = authenticityVerification.status
        profile.authenticityVerified =
            authenticityVerification.status == ProfileAuthenticityVerificationStatus.VERIFIED
        profile.updatedAt = OffsetDateTime.now()

        val saved = profileRepository.save(profile)
        recordProfileAuthenticityVerificationUpdated(
            profile = saved,
            oldStatus = oldStatus
        )
        return saved
    }

    fun activateProfile(profileId: UUID): Profile {
        val profile = findByIdOrThrow(profileId)
        val previousStatus = profile.status

        if (
            profile.status != ProfileStatus.DRAFT &&
            profile.status != ProfileStatus.INACTIVE
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_NOT_ACTIVATABLE,
                message = "Profile cannot be activated from status ${profile.status}"
            )
        }

        profilePhotoService.validatePhotosForActivation(profileId)
        validateAuthenticityVerificationForActivation(profile)

        profile.status = ProfileStatus.ACTIVE
        profile.updatedAt = OffsetDateTime.now()

        val saved = profileRepository.save(profile)
        auditEventService.record(
            eventType = AuditEventType.PROFILE_ACTIVATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = saved.id,
            actorUserId = saved.userId,
            metadata = mapOf(
                "previousStatus" to previousStatus.name,
                "newStatus" to saved.status.name
            )
        )
        homeStateInvalidationService.bump(
            userId = saved.userId,
            reason = "profile_activated"
        )
        return saved
    }

    fun updateProfile(
        profileId: UUID,
        displayName: String? = null,
        bio: String? = null,
        city: String? = null,
        countryCode: String? = null
    ): Profile {
        val profile = findByIdOrThrow(profileId)

        displayName?.trim()?.let {
            validateDisplayName(it)
            profile.displayName = it
        }

        bio?.let {
            val normalizedBio = normalizeOptionalText(it)
            normalizedBio?.let { value -> validateMultilineText("Bio", value, BIO_MAX_LENGTH) }
            profile.bio = normalizedBio
        }

        city?.trim()?.let {
            validateSingleLineText("City", it, LOCATION_MAX_LENGTH)
            profile.city = it
        }

        countryCode?.let {
            profile.countryCode = countryReferenceService.normalizeAndValidateCountryCode(it)
        }

        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    fun updateDynamicMatchFilters(
        profileId: UUID,
        intention: Intention,
        lookingForGenders: Set<Gender>,
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ): Profile {
        val profile = findByIdOrThrow(profileId)

        validateDynamicMatchFilters(
            preferredMinAge = preferredMinAge,
            preferredMaxAge = preferredMaxAge,
            maxDistanceKm = maxDistanceKm
        )
        validateLookingForGenders(lookingForGenders)

        profile.intention = intention
        profile.lookingForGenders.clear()
        profile.lookingForGenders.addAll(lookingForGenders)
        profile.preferredMinAge = preferredMinAge
        profile.preferredMaxAge = preferredMaxAge
        profile.maxDistanceKm = maxDistanceKm
        profile.updatedAt = OffsetDateTime.now()

        return profileRepository.save(profile)
    }

    private fun validateAuthenticityVerificationForActivation(profile: Profile) {
        if (
            requireAuthenticityVerificationForActivation &&
            profile.authenticityVerificationStatus != ProfileAuthenticityVerificationStatus.VERIFIED
        ) {
            throw DomainConflictException(
                code = DomainErrorCode.PROFILE_AUTHENTICITY_VERIFICATION_REQUIRED,
                message = "Profile authenticity must be verified before activation"
            )
        }
    }

    private fun recordProfileAuthenticityVerificationUpdated(
        profile: Profile,
        oldStatus: ProfileAuthenticityVerificationStatus,
        reason: String? = null
    ) {
        auditEventService.record(
            eventType = AuditEventType.PROFILE_AUTHENTICITY_VERIFICATION_UPDATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = profile.id,
            actorUserId = profile.userId,
            metadata = mapOf(
                "oldStatus" to oldStatus.name,
                "newStatus" to profile.authenticityVerificationStatus.name,
                "authenticityVerified" to profile.authenticityVerified,
                "reason" to reason
            )
        )
    }

    private fun normalizeOptionalText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun validateDisplayName(displayName: String) {
        require(displayName.length in DISPLAY_NAME_MIN_LENGTH..DISPLAY_NAME_MAX_LENGTH) {
            "Display name must be between $DISPLAY_NAME_MIN_LENGTH and $DISPLAY_NAME_MAX_LENGTH characters"
        }

        SingleLinePlainText.requireValid("Display name", displayName)
    }

    private fun validateBirthDate(birthDate: LocalDate) {
        if (!birthDate.isBefore(LocalDate.now())) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_BIRTH_DATE,
                message = "Birth date must be in the past"
            )
        }

        val age = Period.between(birthDate, LocalDate.now()).years

        if (age !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_PROFILE_BIRTH_DATE,
                message = "Profile age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }
    }

    private fun validateDynamicMatchFilters(
        preferredMinAge: Int,
        preferredMaxAge: Int,
        maxDistanceKm: Int
    ) {
        if (preferredMinAge !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred minimum age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }

        if (preferredMaxAge !in MIN_PROFILE_AGE..MAX_PROFILE_AGE) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred maximum age must be between $MIN_PROFILE_AGE and $MAX_PROFILE_AGE"
            )
        }

        if (preferredMinAge > preferredMaxAge) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Preferred minimum age must be less than or equal to preferred maximum age"
            )
        }

        if (maxDistanceKm !in 1..1000) {
            throw DomainBadRequestException(
                code = DomainErrorCode.INVALID_MATCH_FILTERS,
                message = "Maximum distance must be between 1 and 1000 kilometers"
            )
        }
    }

    private fun validateLocation(city: String) {
        validateSingleLineText("City", city, LOCATION_MAX_LENGTH)
        // TODO: Validate city against a future canonical location dataset.
    }

    private fun validateSingleLineText(
        fieldName: String,
        value: String,
        maxLength: Int
    ) {
        require(value.isNotBlank()) {
            "$fieldName is required"
        }

        require(value.length <= maxLength) {
            "$fieldName must be at most $maxLength characters"
        }

        SingleLinePlainText.requireValid(fieldName, value)
    }

    private fun validateMultilineText(
        fieldName: String,
        value: String,
        maxLength: Int
    ) {
        require(value.isNotBlank()) {
            "$fieldName is required"
        }

        require(value.length <= maxLength) {
            "$fieldName must be at most $maxLength characters"
        }

        PlainText.requireValid(fieldName, value)
    }

    private fun validateLookingForGenders(lookingForGenders: Set<Gender>) {
        require(lookingForGenders.isNotEmpty()) {
            "Looking for genders must contain at least one gender"
        }

        require(lookingForGenders.size <= Gender.entries.size) {
            "Looking for genders must contain at most ${Gender.entries.size} genders"
        }
    }

    private companion object {
        const val DISPLAY_NAME_MIN_LENGTH = 2
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val LOCATION_MAX_LENGTH = 100
        const val BIO_MAX_LENGTH = 1000
        const val MIN_PROFILE_AGE = 18
        const val MAX_PROFILE_AGE = 99
    }
}
